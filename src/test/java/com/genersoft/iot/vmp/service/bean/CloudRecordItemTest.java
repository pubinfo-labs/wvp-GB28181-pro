package com.genersoft.iot.vmp.service.bean;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CloudRecordItemTest {
    @Test
    void storageTypeDefaultsToLocal() {
        assertEquals("local", new CloudRecordItem().getStorageType());
    }
}