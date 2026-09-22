package com.kyle.salesAgent.spike;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

/** spike 同步助手，工具选择（T2）、多轮记忆（T3）、权限拒答（T4）都走它。 */
public interface SpikeChatAssistant {

    String chat(@MemoryId String memoryId, @UserMessage String message);
}
