package com.kyle.salesAgent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 对话审计日志（需求 7.5 可追溯）：谁在什么时间问了什么、花了多少 Token、调了哪些工具。
 * 由 audit 包的 {@code AuditService} 异步写入，主链路零等待。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/2
 */
@Entity
@Table(name = "sa_audit_log")
@Getter
@Setter
@NoArgsConstructor
public class AuditLogEntity {

    /** 数据库主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 ID（来自 UserContext）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 姓名快照（写入时取，防后续改名影响历史）。 */
    @Column(length = 50)
    private String username;

    /** 会话 ID（客户端原始值，不含用户前缀）。 */
    @Column(name = "session_id", length = 120)
    private String sessionId;

    /** 用户提问原文（可能含客户姓名/手机号，PII 脱敏与 90 天滚动删除见架构 8 节待办）。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    /** Agent 回答；失败或流式中断时为空。 */
    @Column(columnDefinition = "TEXT")
    private String answer;

    /** 本次调用的工具名，逗号分隔（流式路径暂缺）。 */
    @Column(name = "tool_names", length = 500)
    private String toolNames;

    /** 输入 Token（流式路径暂缺）。 */
    @Column(name = "input_tokens")
    private Integer inputTokens;

    /** 输出 Token（流式路径暂缺）。 */
    @Column(name = "output_tokens")
    private Integer outputTokens;

    /** 总耗时毫秒（模型 + 工具调用）。 */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** 写入时间。 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @jakarta.persistence.PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
