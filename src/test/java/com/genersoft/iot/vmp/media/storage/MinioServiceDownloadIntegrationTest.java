package com.genersoft.iot.vmp.media.storage;

import com.genersoft.iot.vmp.conf.RecordStorageConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * downloadToLocal 集成测试 —— 连接真实 MinIO 服务下载对象。
 * 需要网络可达 192.168.111.62:9000，MinIO 中存在对应对象。
 */
class MinioServiceDownloadIntegrationTest {

    private RecordStorageConfig config;
    private MinioService service;

    @BeforeEach
    void setUp() {
        config = new RecordStorageConfig();
        config.setStorage("minio");
        config.getMinio().setEndpoint("http://192.168.111.62:9000");
        config.getMinio().setAccessKey("minioadmin");
        config.getMinio().setSecretKey("zJBENzMqZ4s8mjdXjtjQQw7Y");
        config.getMinio().setBucket("vision-file");
        config.getMinio().setPathPrefix("record");
        config.getMinio().setUrlExpireHours(24);

        service = new MinioService(config);
    }

    @Test
    void downloadRealObjectToLocal() throws Exception {
        // 对象 key：record/rtp/34020000002000000005_34020000001320000005/2026-09-28/2026-09-28-15-15-30-0.mp4
        String objectKey = service.buildObjectKey(
                "rtp",
                "34020000002000000005_34020000001320000005",
                "2026-09-28",
                "2026-09-28-15-15-30-0.mp4"
        );
        System.out.println("[TEST] objectKey = " + objectKey);

        // 先验证对象存在（statSize）
        long remoteSize = service.statSize(objectKey);
        System.out.println("[TEST] remote object size = " + remoteSize + " bytes");
        assertTrue(remoteSize > 0, "远程对象大小应 > 0");

        // 下载到临时目录
        Path localFile = Path.of(System.getProperty("java.io.tmpdir"),
                "wvp-test-download-" + System.currentTimeMillis() + ".mp4");
        System.out.println("[TEST] localPath = " + localFile);

        service.downloadToLocal(objectKey, localFile.toString());

        // 验证本地文件
        File downloaded = localFile.toFile();
        assertTrue(downloaded.exists(), "下载文件应存在");
        long localSize = downloaded.length();
        System.out.println("[TEST] local file size = " + localSize + " bytes");
        assertTrue(localSize > 0, "本地文件大小应 > 0");
        assertEquals(remoteSize, localSize, "本地文件大小应与远程一致");

        // 清理
        downloaded.delete();
        System.out.println("[TEST] 下载验证通过，已清理临时文件");
    }
}
