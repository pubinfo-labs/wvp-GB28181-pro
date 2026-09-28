package com.genersoft.iot.vmp.media.storage;

import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.service.bean.CloudRecordItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalRecordStorageServiceImplTest {

    @Test
    void typeIsLocal() {
        assertEquals("local", new LocalRecordStorageServiceImpl(mock(IMediaServerService.class)).getType());
    }

    @Test
    void deleteDelegatesToZlm() {
        IMediaServerService mediaServerService = mock(IMediaServerService.class);
        when(mediaServerService.getOne("zlm1")).thenReturn(new MediaServer());
        when(mediaServerService.deleteRecordDirectory(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        CloudRecordItem item = new CloudRecordItem();
        item.setMediaServerId("zlm1");
        item.setApp("cam"); item.setStream("001");
        item.setFilePath("/opt/media/record/cam/001/2026-09-24/1.mp4");
        item.setFileName("1.mp4");

        boolean result = new LocalRecordStorageServiceImpl(mediaServerService).deleteRecordFile(item, "2026-09-24");
        assertTrue(result);
    }

    @Test
    void downloadToLocalReturnsFilePath() {
        IMediaServerService mediaServerService = mock(IMediaServerService.class);
        CloudRecordItem item = new CloudRecordItem();
        item.setFilePath("/opt/media/record/cam/001/2026-09-24/1.mp4");
        assertEquals("/opt/media/record/cam/001/2026-09-24/1.mp4",
                new LocalRecordStorageServiceImpl(mediaServerService).downloadToLocal(item, "/tmp"));
    }
}