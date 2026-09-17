package com.kyle.salesAgent.dto;

import java.math.BigDecimal;

/**
 * 产品销售汇总 DTO
 * <p>用于产品排名与品类分析场景，如"本月 Top 10 产品"、"哪个品类最好"
 *
 * @author kyle
 * @version 1.0
 * @date 2026/9/17 23:50
 */
public record ProductSalesDTO(
        /** 产品ID */
        Long productId,
        /** SKU编码 */
        String skuCode,
        /** 产品名称 */
        String productName,
        /** 品类：数码产品/家用电器/服装配饰/其他 */
        String category,
        /** 销售总额（毛额口径，仅 COMPLETED 订单） */
        BigDecimal totalAmount,
        /** 销售总数量 */
        Integer totalQuantity
) {}
