package com.kyle.salesAgent.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 销售大区实体
 *
 * @author kyle
 * @date 2026/9/17
 * @version 1.0
 */
@Entity
@Table(name = "sa_sales_region")
@Getter
@Setter
@NoArgsConstructor
public class SalesRegion {

    /** 大区ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 大区名称，如：华东区 */
    @Column(nullable = false, unique = true, length = 50)
    private String name;

    /** 上级大区，NULL表示顶级（本期仅用一层） */
    @Column(name = "parent_region_id")
    private Long parentRegionId;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}