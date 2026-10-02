package com.kyle.salesAgent.entity;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
    @Column(name = "region_id")
    private Long regionId;

    /** 角色：SALES_REP(销售员)/SALES_MANAGER(销售主管)/SALES_DIRECTOR(销售总监) */
    @Column(nullable = false, length = 20)
    private String role;

    /** 密码（BCrypt 哈希，60 字符）。@JsonIgnore：任何 JSON 序列化都不得把哈希带出接口 */
    @JsonIgnore
    @Column(nullable = false, length = 72)
    private String password;

    /** 账号是否启用；停用后不可登录。 */
    @Column(nullable = false)
    private Boolean active = true;

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
