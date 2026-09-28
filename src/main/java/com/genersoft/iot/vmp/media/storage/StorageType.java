package com.genersoft.iot.vmp.media.storage;

/**
 * 录像存储方式
 * <p>
 * 对应 {@link com.genersoft.iot.vmp.service.bean.CloudRecordItem#storageType} 的取值，
 * 同时作为 {@link IRecordStorageService#getType()} 与存储服务映射（storageServiceMap）的 key。
 * 数据库落库仍然使用 {@link #getType()} 返回的字符串，保持兼容。
 */
public enum StorageType {

    /** 本地存储：录像文件保存在 ZLM 所在服务器的磁盘上 */
    LOCAL("local"),

    /** MinIO 对象存储：上传成功后删除 ZLM 本地文件 */
    MINIO("minio");

    private final String type;

    StorageType(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    /**
     * 根据存储方式标识解析枚举，忽略大小写；未知标识默认返回 {@link #LOCAL}
     */
    public static StorageType of(String type) {
        for (StorageType storageType : values()) {
            if (storageType.type.equalsIgnoreCase(type)) {
                return storageType;
            }
        }
        return LOCAL;
    }

    /**
     * 判断给定的存储方式标识是否为 MinIO
     */
    public static boolean isMinio(String type) {
        return MINIO.type.equalsIgnoreCase(type);
    }
}