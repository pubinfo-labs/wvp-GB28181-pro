package com.genersoft.iot.vmp.media.storage;

import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.event.media.MediaRecordMp4Event;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.service.bean.CloudRecordItem;
import com.genersoft.iot.vmp.service.bean.DownloadFileInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;

@Slf4j
@Service
public class MinioRecordStorageServiceImpl implements IRecordStorageService {

    private final MinioService minioService;
    private final IMediaServerService mediaServerService;

    public MinioRecordStorageServiceImpl(MinioService minioService, IMediaServerService mediaServerService) {
        this.minioService = minioService;
        this.mediaServerService = mediaServerService;
    }

    @Override
    public String getType() {
        return StorageType.MINIO.getType();
    }

    @Override
    public CloudRecordItem beforeStore(MediaRecordMp4Event event, CloudRecordItem item) {
        String localPath = item.getFilePath();
        String date = new File(localPath).getParentFile().getName();
        String objectKey = minioService.buildObjectKey(item.getApp(), item.getStream(), date, item.getFileName());
        long expectedSize = item.getFileSize();
        try {
            String url = buildZlmDownloadUrl(event.getMediaServer(), localPath);
            try (InputStream in = openZlmStream(url)) {
                minioService.upload(objectKey, in, expectedSize, "video/mp4");
            }
            long actual = minioService.statSize(objectKey);
            if (expectedSize > 0 && actual != expectedSize) {
                throw new IllegalStateException("上传对象大小与源文件不一致: " + actual + " != " + expectedSize);
            }
            item.setFilePath(objectKey);
            item.setStorageType(StorageType.MINIO.getType());
            log.info("[MinIO] 录像上传成功: {} -> {}", localPath, objectKey);
        } catch (Exception e) {
            log.error("[MinIO] 录像上传失败，降级为本地存储: {}, 原因: {}", localPath, e.getMessage());
            item.setFilePath(localPath);
            item.setStorageType(StorageType.LOCAL.getType());
        }
        return item;
    }

    @Override
    public void afterStore(CloudRecordItem item, String localFilePath) {
        if (!StorageType.isMinio(item.getStorageType())) {
            return;
        }
        try {
            String date = new File(localFilePath).getParentFile().getName();
            MediaServer mediaServer = mediaServerService.getOne(item.getMediaServerId());
            mediaServerService.deleteRecordDirectory(mediaServer, item.getApp(), item.getStream(), date, item.getFileName());
        } catch (Exception e) {
            log.warn("[MinIO] 删除 ZLM 本地文件失败（本地残留，不影响记录）: {}, 原因: {}", localFilePath, e.getMessage());
        }
    }

    /** 供测试覆盖 */
    protected InputStream openZlmStream(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(30000);
        return conn.getInputStream();
    }

    private String buildZlmDownloadUrl(MediaServer mediaServer, String filePath) {
        return String.format("http://%s:%s/index/api/downloadFile?file_path=%s",
                mediaServer.getStreamIp(), mediaServer.getHttpPort(), filePath);
    }

    @Override
    public DownloadFileInfo getDownloadFileInfo(CloudRecordItem item) {
        DownloadFileInfo info = new DownloadFileInfo();
        // 带文件名生成预签名 URL，MinIO 会通过 response-content-disposition 让浏览器以正确文件名下载，
        // 前端无需再追加 save_name 参数（追加会破坏 MinIO 签名导致 403）
        String url = minioService.getPresignedUrlSafe(item.getFilePath(), item.getFileName());
        info.setHttpPath(url);
        info.setHttpsPath(url);
        return info;
    }

    @Override
    public boolean deleteRecordFile(CloudRecordItem item, String dateDir) {
        try {
            minioService.delete(item.getFilePath());
            return true;
        } catch (Exception e) {
            log.error("[MinIO] 删除对象失败: {}, 原因: {}", item.getFilePath(), e.getMessage());
            return false;
        }
    }

    @Override
    public String downloadToLocal(CloudRecordItem item, String tempDir) {
        String fileName = item.getFileName();
        String localPath = tempDir + File.separator + fileName;
        try {
            minioService.downloadToLocal(item.getFilePath(), localPath);
            return localPath;
        } catch (Exception e) {
            log.error("[MinIO] 临时下载失败: {}, 原因: {}", item.getFilePath(), e.getMessage());
            return null;
        }
    }
}