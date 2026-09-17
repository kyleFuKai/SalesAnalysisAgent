package com.kyle.salesAgent.dto;

import java.math.BigDecimal;

/**
 * 大区销售汇总 DTO
 * <p>用于大区排名与占比场景，如"各大区销售额排名"、"各大区销售额占比饼图"（总监视角）
 *
 * @author kyle
 * @version 1.0
 * @date 2026/9/17 23:43
 */
public record RegionSalesDTO(
        /** 大区ID */
        Long regionId,
        /** 大区名称 */
        String regionName,
        /** 销售总额（毛额口径，仅 COMPLETED 订单） */
        BigDecimal totalAmount,
        /** 订单笔数 */
        Integer orderCount,
        /** 毛利总额（amount - cost） */
        BigDecimal totalProfit
) {}
