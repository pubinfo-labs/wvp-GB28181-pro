package com.genersoft.iot.vmp.service.impl;

import com.genersoft.iot.vmp.conf.RecordStorageConfig;
import com.genersoft.iot.vmp.media.storage.IRecordStorageService;
import com.genersoft.iot.vmp.service.bean.CloudRecordItem;
import com.genersoft.iot.vmp.service.bean.DownloadFileInfo;
import com.genersoft.iot.vmp.storager.dao.CloudRecordServiceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudRecordServiceImplStorageTest {

    @Test
    void playUrlUsesMinioImplForMinioRecord() {
        IRecordStorageService minioSvc = mock(IRecordStorageService.class);
        DownloadFileInfo minioInfo = new DownloadFileInfo();   // DownloadFileInfo 仅有无参构造，需用 setter
        minioInfo.setHttpPath("http://minio/x.mp4");
        when(minioSvc.getDownloadFileInfo(any())).thenReturn(minioInfo);

        CloudRecordServiceMapper mapper = mock(CloudRecordServiceMapper.class);
        CloudRecordItem item = new CloudRecordItem();
        item.setId(1); item.setStorageType("minio"); item.setServerId("wvp1");
        when(mapper.queryOne(1)).thenReturn(item);

        CloudRecordServiceImpl svc = new CloudRecordServiceImpl();
        ReflectionTestUtils.setField(svc, "cloudRecordServiceMapper", mapper);
        ReflectionTestUtils.setField(svc, "userSetting", userSettingWithServerId("wvp1"));
        Map<String, IRecordStorageService> map = new HashMap<>();
        map.put("minio", minioSvc);
        ReflectionTestUtils.setField(svc, "storageServiceMap", map);

        DownloadFileInfo info = svc.getPlayUrlPath(1);
        assertEquals("http://minio/x.mp4", info.getHttpPath());
        verify(minioSvc).getDownloadFileInfo(item);
    }

    private com.genersoft.iot.vmp.conf.UserSetting userSettingWithServerId(String id) {
        com.genersoft.iot.vmp.conf.UserSetting u = new com.genersoft.iot.vmp.conf.UserSetting();
        u.setServerId(id);
        return u;
    }
}