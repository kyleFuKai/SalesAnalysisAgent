package com.kyle.salesAgent.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 产品实体
 *
 * @author kyle
 * @date 2026/9/17
 * @version 1.0
 */
@Entity
@Table(name = "sa_product")
@Getter
@Setter
@NoArgsConstructor
public class Product {

    /** 产品ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SKU编码 */
    @Column(name = "sku_code", nullable = false, unique = true, length = 50)
    private String skuCode;

    /** 产品名称 */
    @Column(nullable = false, length = 200)
    private String name;

    /** 品类：数码产品/家用电器/服装配饰/其他 */
    @Column(nullable = false, length = 50)
    private String category;

    /** 售价 */
    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    /** 成本 */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal cost;

    /** 状态：ACTIVE(在售)/INACTIVE(下架) */
    @Column(nullable = false, length = 20)
    private String status;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}