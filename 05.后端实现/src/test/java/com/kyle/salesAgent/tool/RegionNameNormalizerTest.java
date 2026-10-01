package com.kyle.salesAgent.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RegionNameNormalizerTest {

    @Test
    void treatsMissingAndLiteralNullAsUnspecified() {
        assertNull(RegionNameNormalizer.normalize(null));
        assertNull(RegionNameNormalizer.normalize(""));
        assertNull(RegionNameNormalizer.normalize("  "));
        assertNull(RegionNameNormalizer.normalize("null"));
        assertNull(RegionNameNormalizer.normalize(" NULL "));
    }

    @Test
    void keepsUnknownNamesForExplicitValidation() {
        assertEquals("华东区", RegionNameNormalizer.normalize(" 华东区 "));
        assertEquals("不存在的大区", RegionNameNormalizer.normalize("不存在的大区"));
    }
}
