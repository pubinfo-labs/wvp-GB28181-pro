package com.genersoft.iot.vmp.media.storage;

import com.genersoft.iot.vmp.media.event.media.MediaRecordMp4Event;
import com.genersoft.iot.vmp.service.bean.CloudRecordItem;
import com.genersoft.iot.vmp.service.bean.DownloadFileInfo;

/**
 * 录像存储策略：按存储方式（{@link StorageType}）实现
 */
public interface IRecordStorageService {

    /** 存储方式标识，取值见 {@link StorageType} */
    String getType();

    /**
     * 入库前处理：minio 上传对象（失败降级 local）；local 不处理。
     * 返回可能被修改 storageType/filePath 的 item。
     */
    CloudRecordItem beforeStore(MediaRecordMp4Event event, CloudRecordItem item);

    /**
     * 写库成功后的回调：minio 删除 ZLM 本地文件；local 空实现。
     * @param localFilePath 原始 ZLM 本地路径（删本地用）
     */
    void afterStore(CloudRecordItem item, String localFilePath);

    /** 下载/播放 URL */
    DownloadFileInfo getDownloadFileInfo(CloudRecordItem item);

    /** 删除文件（ZLM 本地文件或 MinIO 对象） */
    boolean deleteRecordFile(CloudRecordItem item, String dateDir);

    /** 合并任务：下载到本地临时目录并返回本地路径（local 直接返回原路径） */
    String downloadToLocal(CloudRecordItem item, String tempDir);
}