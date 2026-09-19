package com.kyle.salesAgent.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 订单摘要 DTO（不含敏感字段）
 * <p>用于订单列表/明细的对外展示场景，如"上个月华东区的所有订单有哪些"、"张磊这个月成交了哪几单"。
 * <p>故意不包含 cost / profit 字段（对齐需求 6.3 字段级敏感数据保护），
 * 适合直接传给模型展示给最终用户；如需毛利/成本分析请用 SalesOrder 实体。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/9/18 11:59
 */
public record OrderSummaryDTO(
        /** 订单号 */
        String orderNo,
        /** 销售员姓名（关联 sa_sales_rep.name 翻译显示） */
        String repName,
        /** 客户名称 */
        String customerName,
        /** 成交金额（quantity * unit_price） */
        BigDecimal amount,
        /** 状态：COMPLETED(已完成)/REFUNDED(已退单)/CANCELLED(已取消) */
        String status,
        /** 下单日期 */
        LocalDate orderDate
) {}