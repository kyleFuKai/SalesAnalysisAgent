package com.kyle.salesAgent.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 销售员实体
 *
 * @author kyle
 * @date 2026/9/17
 * @version 1.0
 */
@Entity
@Table(name = "sa_sales_rep")
@Getter
@Setter
@NoArgsConstructor
public class SalesRep {

    /** 销售员ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 姓名 */
    @Column(nullable = false, length = 50)
    private String name;

    /** 所属大区 */
    @Column(name = "region_id", nullable = false)
    private Long regionId;

    /** 角色：SALES_REP/SALES_MANAGER/SALES_DIRECTOR */
    @Column(nullable = false, length = 20)
    private String role;

    /** 邮箱 */
    @Column(length = 100)
    private String email;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}