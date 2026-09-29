package com.genersoft.iot.vmp.media.storage;

import com.genersoft.iot.vmp.conf.RecordStorageConfig;
import io.minio.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class MinioService {

    private final RecordStorageConfig config;
    private volatile MinioClient client;

    public MinioService(RecordStorageConfig config) {
        this.config = config;
    }

    /** 测试注入用 */
    public void setClient(MinioClient client) {
        this.client = client;
    }

    public boolean isConfigured() {
        RecordStorageConfig.Minio m = config.getMinio();
        return StringUtils.isNoneBlank(m.getEndpoint(), m.getAccessKey(), m.getSecretKey(), m.getBucket());
    }

    private MinioClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = MinioClient.builder()
                            .endpoint(config.getMinio().getEndpoint())
                            .credentials(config.getMinio().getAccessKey(), config.getMinio().getSecretKey())
                            .build();
                }
            }
        }
        return client;
    }

    @PostConstruct
    public void init() {
        if (!config.isMinio()) return;
        try {
            ensureBucket();
        } catch (Exception e) {
            // 不阻断启动
            log.error("[MinIO] 初始化检查 bucket 失败，服务继续启动：{}", e.getMessage());
        }
    }

    public void ensureBucket() throws Exception {
        String bucket = config.getMinio().getBucket();
        if (!client().bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client().makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("[MinIO] 已创建 bucket: {}", bucket);
        }
    }

    public String buildObjectKey(String app, String stream, String date, String fileName) {
        String prefix = config.getMinio().getPathPrefix();
        if (StringUtils.isNotBlank(prefix)) {
            return prefix + "/" + app + "/" + stream + "/" + date + "/" + fileName;
        }
        return app + "/" + stream + "/" + date + "/" + fileName;
    }

    public void upload(String objectKey, InputStream in, long size, String contentType) throws Exception {
        client().putObject(PutObjectArgs.builder()
                .bucket(config.getMinio().getBucket())
                .object(objectKey)
                .stream(in, size, -1)
                .contentType(contentType == null ? "video/mp4" : contentType)
                .build());
    }

    /** 校验对象大小是否与源文件一致 */
    public long statSize(String objectKey) throws Exception {
        return client().statObject(StatObjectArgs.builder()
                .bucket(config.getMinio().getBucket()).object(objectKey).build()).size();
    }

    public void delete(String objectKey) throws Exception {
        client().removeObject(RemoveObjectArgs.builder()
                .bucket(config.getMinio().getBucket()).object(objectKey).build());
    }

    public void downloadToLocal(String objectKey, String localPath) throws Exception {
        client().downloadObject(DownloadObjectArgs.builder()
                .bucket(config.getMinio().getBucket())
                .object(objectKey)
                .filename(localPath)
                .overwrite(true)
                .build());
    }

    /**
     * 多线程分片下载（便捷重载）：默认 4 线程、每片 10MB。
     *
     * @param objectKey MinIO 对象 key
     * @param localPath 本地保存路径
     */
    public void downloadMultipart(String objectKey, String localPath) throws Exception {
        downloadMultipart(objectKey, localPath, 4, 10 * 1024 * 1024L);
    }

    /**
     * 多线程分片下载：将对象按 partSize 拆分，多线程并发通过 Range 请求下载各分片，
     * 直接写入目标文件的对应位置，无需临时文件。
     *
     * @param objectKey   MinIO 对象 key
     * @param localPath   本地保存路径
     * @param threadCount  并发线程数（实际取 min(threadCount, partCount)）
     * @param partSize    每片大小（字节），小于 1 时取默认值 10MB
     */
    public void downloadMultipart(String objectKey, String localPath, int threadCount, long partSize) throws Exception {
        if (threadCount < 1) {
            threadCount = 1;
        }
        if (partSize < 1) {
            partSize = 10 * 1024 * 1024L;
        }

        long fileSize = statSize(objectKey);
        if (fileSize <= 0) {
            throw new IllegalStateException("对象大小为 0 或无法获取: " + objectKey);
        }

        int partCount = (int) Math.ceil((double) fileSize / partSize);
        int actualThreads = Math.min(threadCount, partCount);

        log.info("[MinIO] 分片下载: objectKey={}, fileSize={}, partCount={}, partSize={}, threads={}",
                objectKey, fileSize, partCount, partSize, actualThreads);

        // 创建目标文件并预分配空间
        File targetFile = new File(localPath);
        File parentDir = targetFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        try (RandomAccessFile raf = new RandomAccessFile(targetFile, "rw")) {
            raf.setLength(fileSize);
        }

        // 多线程下载，每片直接写入目标文件对应位置
        ExecutorService executor = Executors.newFixedThreadPool(actualThreads);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < partCount; i++) {
                final long offset = (long) i * partSize;
                final long length = Math.min(partSize, fileSize - offset);

                futures.add(executor.submit(() -> {
                    try (GetObjectResponse response = client().getObject(GetObjectArgs.builder()
                            .bucket(config.getMinio().getBucket())
                            .object(objectKey)
                            .offset(offset)
                            .length(length)
                            .build())) {
                        // 每个线程独立持有 RandomAccessFile，seek 到各自偏移量写入
                        try (RandomAccessFile raf = new RandomAccessFile(targetFile, "rw")) {
                            raf.seek(offset);
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = response.read(buf)) != -1) {
                                raf.write(buf, 0, n);
                            }
                        }
                    }
                    return null;
                }));
            }

            // 等待所有分片完成；任一失败则解包抛出原始异常
            for (Future<Void> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }
                    throw e;
                }
            }
        } finally {
            executor.shutdownNow();
        }

        log.info("[MinIO] 分片下载完成: {}, size={}", localPath, targetFile.length());
    }

    public String getPresignedUrl(String objectKey) throws Exception {
        return getPresignedUrl(objectKey, null);
    }

    /**
     * 生成预签名 URL，可选附带 response-content-disposition 使浏览器以指定文件名下载。
     *
     * @param objectKey MinIO 对象 key
     * @param fileName  下载文件名（为 null 或空则不附带 content-disposition）
     */
    public String getPresignedUrl(String objectKey, String fileName) throws Exception {
        int hours = config.getMinio().getUrlExpireHours() == null ? 24 : config.getMinio().getUrlExpireHours();
        GetPresignedObjectUrlArgs.Builder builder = GetPresignedObjectUrlArgs.builder()
                .method(io.minio.http.Method.GET)
                .bucket(config.getMinio().getBucket())
                .object(objectKey)
                .expiry(hours, TimeUnit.HOURS);
        if (StringUtils.isNotBlank(fileName)) {
            String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            Map<String, String> extra = new HashMap<>();
            extra.put("response-content-disposition", "attachment; filename=\"" + encoded + "\"");
            builder.extraQueryParams(extra);
        }
        String url = client().getPresignedObjectUrl(builder.build());
        return replaceHost(url, config.getMinio().getPublicEndpoint());
    }

    /** 预签名 URL 安全包装：失败返回 null 并打日志（供下载/播放使用，不抛异常） */
    public String getPresignedUrlSafe(String objectKey) {
        return getPresignedUrlSafe(objectKey, null);
    }

    /**
     * 预签名 URL 安全包装（带文件名）：失败返回 null 并打日志。
     *
     * @param objectKey MinIO 对象 key
     * @param fileName  下载文件名（为 null 或空则不附带 content-disposition）
     */
    public String getPresignedUrlSafe(String objectKey, String fileName) {
        try {
            return getPresignedUrl(objectKey, fileName);
        } catch (Exception e) {
            log.error("[MinIO] 生成预签名 URL 失败: {}, 原因: {}", objectKey, e.getMessage());
            return null;
        }
    }

    /** 用 public-endpoint 的 scheme+host+port 替换 URL 的对应部分 */
    static String replaceHost(String url, String publicEndpoint) {
        if (StringUtils.isBlank(publicEndpoint)) return url;
        try {
            URI src = URI.create(url);
            URI pub = URI.create(publicEndpoint);
            StringBuilder sb = new StringBuilder();
            sb.append(pub.getScheme()).append("://").append(pub.getAuthority());
            sb.append(src.getRawPath());
            if (src.getRawQuery() != null) {
                sb.append("?").append(src.getRawQuery());
            }
            return sb.toString();
        } catch (Exception e) {
            return url;
        }
    }
}