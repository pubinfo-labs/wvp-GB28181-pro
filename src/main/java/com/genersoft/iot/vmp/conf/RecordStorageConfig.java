package com.genersoft.iot.vmp.conf;

import com.genersoft.iot.vmp.media.storage.StorageType;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConfigurationProperties(prefix = "record", ignoreInvalidFields = true)
@Data
public class RecordStorageConfig {

    /** 存储方式，取值见 {@link StorageType}：local | minio */
    private String storage = StorageType.LOCAL.getType();

    private Minio minio = new Minio();

    public boolean isMinio() {
        return StorageType.isMinio(storage);
    }

    @Data
    public static class Minio {
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String bucket = "wvp-record";
        private String pathPrefix = "record";
        private Integer urlExpireHours = 24;
        /** 对外可访问地址，生成预签名 URL 时替换 endpoint 的 host；为空则用 endpoint */
        private String publicEndpoint;
    }
}