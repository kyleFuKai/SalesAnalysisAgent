package com.kyle.salesAgent.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RepNameNormalizerTest {

    @Test
    void treatsMissingAndLiteralNullAsUnspecified() {
        assertNull(RepNameNormalizer.normalize(null));
        assertNull(RepNameNormalizer.normalize(""));
        assertNull(RepNameNormalizer.normalize("  "));
        assertNull(RepNameNormalizer.normalize("null"));
        assertNull(RepNameNormalizer.normalize(" NULL "));
    }

    @Test
    void keepsUnknownNamesForExplicitValidation() {
        assertEquals("张磊", RepNameNormalizer.normalize(" 张磊 "));
        assertEquals("不存在的销售员", RepNameNormalizer.normalize("不存在的销售员"));
    }
}
