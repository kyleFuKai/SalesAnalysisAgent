package com.kyle.salesAgent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kyle.salesAgent.entity.ChatMemoryEntity;
import com.kyle.salesAgent.repository.ChatMemoryRepository;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
/**
 * 将 Agent 的对话消息按会话 ID 保存到 MySQL，供后续轮次恢复上下文。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/9/23 15:20
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MysqlChatMemoryStore implements ChatMemoryStore {

    private final ChatMemoryRepository repository;
    /**
     * 读取并反序列化指定会话的历史消息；会话不存在或消息解析失败时返回空列表。
     *
     * @param memoryId 会话 ID
     * @return 历史消息列表
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        return repository.findBySessionId(sessionId)
                .map(entity -> {
                    try {
                        return ChatMessageDeserializer.messagesFromJson(entity.getMessages());
                    } catch (Exception e) {
                        log.warn("反序列化对话记忆失败，sessionId={}", sessionId, e);
                        return Collections.<ChatMessage>emptyList();
                    }
                })
                .orElse(Collections.emptyList());
    }

    /**
     * 将当前消息列表序列化后新增或更新到数据库；保存失败时记录错误日志。
     *
     * @param memoryId 会话 ID
     * @param messages 当前会话的消息列表
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String sessionId = memoryId.toString();
        try {
            String json = ChatMessageSerializer.messagesToJson(messages);
            ChatMemoryEntity entity = repository.findBySessionId(sessionId)
                    .orElseGet(() -> {
                        ChatMemoryEntity e = new ChatMemoryEntity();
                        e.setSessionId(sessionId);
                        return e;
                    });
            entity.setMessages(json);
            repository.save(entity);
        } catch (Exception e) {
            log.error("保存对话记忆失败，sessionId={}", sessionId, e);
        }
    }

    /**
     * 删除指定会话的持久化历史消息。
     *
     * @param memoryId 会话 ID
     */
    @Override
    public void deleteMessages(Object memoryId) {
        repository.deleteBySessionId(memoryId.toString());
    }
}
