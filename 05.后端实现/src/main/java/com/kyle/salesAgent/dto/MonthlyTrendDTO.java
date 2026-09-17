package com.kyle.salesAgent.dto;

import java.math.BigDecimal;

/**
 * 月度销售趋势 DTO
 * <p>用于月度趋势、环比、旺季分析场景，如"近 6 个月销售趋势"、"哪个月是旺季"
 *
 * @author kyle
 * @version 1.0
 * @date 2026/9/17 23:55
 */
public record MonthlyTrendDTO(
        /** 月份（格式：2024-11） */
        String month,
        /** 当月销售总额（毛额口径，仅 COMPLETED 订单） */
        BigDecimal totalAmount,
        /** 当月订单笔数 */
        Integer orderCount
) {}
