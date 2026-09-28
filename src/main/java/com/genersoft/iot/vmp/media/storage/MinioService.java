package com.genersoft.iot.vmp.media.storage;

import com.genersoft.iot.vmp.conf.RecordStorageConfig;
import io.minio.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
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