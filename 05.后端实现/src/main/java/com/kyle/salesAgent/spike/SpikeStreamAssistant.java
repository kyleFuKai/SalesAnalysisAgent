package com.kyle.salesAgent.spike;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * spike 流式助手（T1）。故意挂上工具：流式和工具调用叠在一起跑，
 * 才是真实场景里最容易翻车的组合，光测纯文本流式说明不了问题。
 */
public interface SpikeStreamAssistant {

    TokenStream stream(@MemoryId String memoryId, @UserMessage String message);
}
