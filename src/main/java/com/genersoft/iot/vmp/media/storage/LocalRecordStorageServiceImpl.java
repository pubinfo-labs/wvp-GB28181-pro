package com.genersoft.iot.vmp.media.storage;

import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.bean.RecordInfo;
import com.genersoft.iot.vmp.media.event.media.MediaRecordMp4Event;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.service.bean.CloudRecordItem;
import com.genersoft.iot.vmp.service.bean.DownloadFileInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class LocalRecordStorageServiceImpl implements IRecordStorageService {

    private final IMediaServerService mediaServerService;

    public LocalRecordStorageServiceImpl(IMediaServerService mediaServerService) {
        this.mediaServerService = mediaServerService;
    }

    @Override
    public String getType() {
        return StorageType.LOCAL.getType();
    }

    @Override
    public CloudRecordItem beforeStore(MediaRecordMp4Event event, CloudRecordItem item) {
        item.setStorageType(StorageType.LOCAL.getType());
        return item;
    }

    @Override
    public void afterStore(CloudRecordItem item, String localFilePath) {
        // 本地存储无需额外处理
    }

    @Override
    public DownloadFileInfo getDownloadFileInfo(CloudRecordItem item) {
        MediaServer mediaServer = mediaServerService.getOne(item.getMediaServerId());
        return mediaServerService.getDownloadFilePath(mediaServer, RecordInfo.getInstance(item));
    }

    @Override
    public boolean deleteRecordFile(CloudRecordItem item, String dateDir) {
        MediaServer mediaServer = mediaServerService.getOne(item.getMediaServerId());
        return mediaServerService.deleteRecordDirectory(mediaServer, item.getApp(),
                item.getStream(), dateDir, item.getFileName());
    }

    @Override
    public String downloadToLocal(CloudRecordItem item, String tempDir) {
        return item.getFilePath();
    }
}