package com.genersoft.iot.vmp.media.storage;

import com.genersoft.iot.vmp.conf.RecordStorageConfig;
import io.minio.DownloadObjectArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MinioServiceTest {

    private RecordStorageConfig config;
    private MinioClient client;
    private MinioService service;

    @BeforeEach
    void setUp() throws Exception {
        config = new RecordStorageConfig();
        config.setStorage("minio");
        config.getMinio().setEndpoint("http://192.168.1.10:9000");
        config.getMinio().setAccessKey("ak");
        config.getMinio().setSecretKey("sk");
        config.getMinio().setBucket("wvp-record");
        config.getMinio().setPathPrefix("record");
        config.getMinio().setUrlExpireHours(24);

        client = mock(MinioClient.class);
        service = new MinioService(config);
        service.setClient(client);   // 测试注入
    }

    @Test
    void buildObjectKey() {
        assertEquals("record/cam/001/2026-09-24/1234.mp4",
                service.buildObjectKey("cam", "001", "2026-09-24", "1234.mp4"));
    }

    @Test
    void presignedUrlReplacesHostWithPublicEndpoint() throws Exception {
        config.getMinio().setPublicEndpoint("http://video.example.com:9000");
        when(client.getPresignedObjectUrl(any())).thenReturn(
                "http://192.168.1.10:9000/wvp-record/record/cam/001/2026-09-24/1234.mp4?X-Amz-Signature=abc");
        String url = service.getPresignedUrl("record/cam/001/2026-09-24/1234.mp4");
        assertTrue(url.startsWith("http://video.example.com:9000/"));
        assertTrue(url.contains("X-Amz-Signature=abc"));
    }

    @Test
    void presignedUrlKeepsEndpointWhenPublicEndpointBlank() throws Exception {
        when(client.getPresignedObjectUrl(any())).thenReturn(
                "http://192.168.1.10:9000/wvp-record/record/cam/001/2026-09-24/1234.mp4?X-Amz-Signature=abc");
        String url = service.getPresignedUrl("record/cam/001/2026-09-24/1234.mp4");
        assertTrue(url.startsWith("http://192.168.1.10:9000/"));
    }

    @Test
    void isConfiguredFalseWhenBlank() {
        config.getMinio().setEndpoint(null);
        assertFalse(service.isConfigured());
    }

    @Test
    void downloadToLocalCallsClientWithCorrectArgs() throws Exception {
        ArgumentCaptor<DownloadObjectArgs> captor = ArgumentCaptor.forClass(DownloadObjectArgs.class);
        doNothing().when(client).downloadObject(captor.capture());

        service.downloadToLocal("record/cam/001/2026-09-24/1.mp4", "/tmp/test-download.mp4");

        DownloadObjectArgs args = captor.getValue();
        assertEquals("wvp-record", args.bucket());
        assertEquals("record/cam/001/2026-09-24/1.mp4", args.object());
        assertEquals("/tmp/test-download.mp4", args.filename());
        assertTrue(args.overwrite());
        verify(client, times(1)).downloadObject(any());
    }

    @Test
    void downloadToLocalPropagatesException() throws Exception {
        doThrow(new RuntimeException("network error"))
                .when(client).downloadObject(any());

        assertThrows(RuntimeException.class,
                () -> service.downloadToLocal("record/cam/001/2026-09-24/1.mp4", "/tmp/test-download.mp4"));
    }
}