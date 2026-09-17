package com.kyle.salesAgent.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 销售订单实体
 *
 * @author kyle
 * @date 2026/9/17
 * @version 1.0
 */
@Entity
@Table(name = "sa_sales_order")
@Getter
@Setter
@NoArgsConstructor
public class SalesOrder {

    /** 订单ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 订单号 */
    @Column(name = "order_no", nullable = false, unique = true, length = 50)
    private String orderNo;

    /** 销售员ID */
    @Column(name = "rep_id", nullable = false)
    private Long repId;

    /** 产品ID */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    /** 销售大区ID */
    @Column(name = "region_id", nullable = false)
    private Long regionId;

    /** 客户名称 */
    @Column(name = "customer_name", nullable = false, length = 100)
    private String customerName;

    /** 销售数量 */
    @Column(nullable = false)
    private Integer quantity;

    /** 成交单价 */
    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    /** 成交金额（quantity * unit_price） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** 成本总额 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal cost;

    /** 毛利（amount - cost） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal profit;

    /** 状态：COMPLETED/REFUNDED/CANCELLED */
    @Column(nullable = false, length = 20)
    private String status;

    /** 下单日期 */
    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}