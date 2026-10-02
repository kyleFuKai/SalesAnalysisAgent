package com.kyle.salesAgent.tool;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工具入参校验器的边界测试：日期格式/真实日历日/Top N 收敛/图表维度白名单。 */
class ToolInputValidatorTest {

    private final ToolInputValidator validator = new ToolInputValidator();

    @Test
    void validDateParsesToLocalDate() {
        assertEquals(LocalDate.of(2026, 7, 1), validator.validateDate("2026-07-01"));
    }

    @Test
    void badDateFormatIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validator.validateDate("2026/07/01"));
        assertTrue(e.getMessage().contains("yyyy-MM-dd"));
    }

    @Test
    void impossibleCalendarDateIsRejected() {
        // 格式对（四位-两位-两位）但日历上不存在，同样要拒
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validator.validateDate("2026-13-45"));
        assertTrue(e.getMessage().contains("无效的日期"));
    }

    @Test
    void topNClampedToRange() {
        assertEquals(1, validator.validateTopN(0));
        assertEquals(1, validator.validateTopN(-5));
        assertEquals(3, validator.validateTopN(3));
        assertEquals(20, validator.validateTopN(99));
    }

    @Test
    void chartDimensionWhitelist() {
        assertEquals("region", validator.validateDimension("region"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validator.validateDimension("area"));
        assertTrue(e.getMessage().contains("region/rep/category"));
    }
}
