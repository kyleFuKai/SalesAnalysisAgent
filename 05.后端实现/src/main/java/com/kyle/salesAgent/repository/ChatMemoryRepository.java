package com.kyle.salesAgent.repository;

import com.kyle.salesAgent.entity.ChatMemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 对话记忆的数据访问接口，按会话 ID 查询或删除持久化消息。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/9/23 14:38
 */
@Repository
public interface ChatMemoryRepository extends JpaRepository<ChatMemoryEntity, Long> {

    Optional<ChatMemoryEntity> findBySessionId(String sessionId);

    @Transactional
    void deleteBySessionId(String sessionId);
}
