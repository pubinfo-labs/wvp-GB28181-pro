package com.genersoft.iot.vmp.media.storage;

import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.bean.RecordInfo;
import com.genersoft.iot.vmp.media.event.media.MediaRecordMp4Event;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.service.bean.CloudRecordItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MinioRecordStorageServiceImplTest {

    private MinioService minioService;
    private MinioRecordStorageServiceImpl impl;
    private MediaRecordMp4Event event;

    @BeforeEach
    void setUp() throws Exception {
        minioService = mock(MinioService.class);
        impl = spy(new MinioRecordStorageServiceImpl(minioService, mock(IMediaServerService.class)));
        // 屏蔽真实 HTTP 拉流，返回固定流
        doReturn(new ByteArrayInputStream(new byte[100])).when(impl).openZlmStream(anyString());
        // 关键：必须 stub buildObjectKey，否则 objectKey 为 null，statSize(anyString()) 不匹配 null
        // 返回 null 拆箱 NPE，beforeStore 会被误判降级 local，导致 beforeStoreUploadsAndChangesKey 断言失败
        when(minioService.buildObjectKey(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("record/cam/001/2026-09-24/1.mp4");

        MediaServer mediaServer = new MediaServer();
        mediaServer.setId("zlm1");
        mediaServer.setStreamIp("127.0.0.1");
        mediaServer.setHttpPort(80);
        RecordInfo recordInfo = new RecordInfo();
        recordInfo.setApp("cam"); recordInfo.setStream("001");
        recordInfo.setFileName("1.mp4"); recordInfo.setFileSize(100);
        recordInfo.setFilePath("/opt/media/record/cam/001/2026-09-24/1.mp4");
        recordInfo.setFolder("2026-09-24");
        // 注意：MediaRecordMp4Event 没有 (mediaServer, app, stream, recordInfo) 构造，
        // 只有 (Object source) 构造 + setter（或静态 getInstance(source, hookParam, mediaServer)）
        event = new MediaRecordMp4Event(new Object());
        event.setApp("cam");
        event.setStream("001");
        event.setRecordInfo(recordInfo);
        event.setMediaServer(mediaServer);
    }

    @Test
    void typeIsMinio() {
        assertEquals("minio", impl.getType());
    }

    @Test
    void beforeStoreUploadsAndChangesKey() throws Exception {
        doNothing().when(minioService).upload(anyString(), any(), anyLong(), anyString());
        when(minioService.statSize(anyString())).thenReturn(100L);

        CloudRecordItem item = impl.beforeStore(event, CloudRecordItem.getInstance(event));
        assertEquals("minio", item.getStorageType());
        assertEquals("record/cam/001/2026-09-24/1.mp4", item.getFilePath());
    }

    @Test
    void beforeStoreFallsBackToLocalOnFailure() throws Exception {
        doThrow(new RuntimeException("upload fail"))
                .when(minioService).upload(anyString(), any(), anyLong(), anyString());

        CloudRecordItem item = impl.beforeStore(event, CloudRecordItem.getInstance(event));
        assertEquals("local", item.getStorageType());
        assertEquals("/opt/media/record/cam/001/2026-09-24/1.mp4", item.getFilePath());
    }

    @Test
    void beforeStoreFallsBackWhenSizeMismatch() throws Exception {
        doNothing().when(minioService).upload(anyString(), any(), anyLong(), anyString());
        when(minioService.statSize(anyString())).thenReturn(50L); // 与 100 不一致

        CloudRecordItem item = impl.beforeStore(event, CloudRecordItem.getInstance(event));
        assertEquals("local", item.getStorageType());
    }
}