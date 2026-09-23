package com.kyle.salesAgent.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 会话历史消息在 sa_chat_memory 表中的持久化记录。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/9/23 14:37
 */
@Entity
@Table(name = "sa_chat_memory")
@Getter
@Setter
@NoArgsConstructor
public class ChatMemoryEntity {

    /** 数据库主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 唯一会话 ID，用于定位该会话的历史消息。 */
    @Column(name = "session_id", nullable = false, unique = true, length = 100)
    private String sessionId;

    /** 序列化后的消息列表 JSON。 */
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String messages;

    /** 最近一次写入消息的时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 首次保存或更新实体前刷新修改时间。 */
    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
