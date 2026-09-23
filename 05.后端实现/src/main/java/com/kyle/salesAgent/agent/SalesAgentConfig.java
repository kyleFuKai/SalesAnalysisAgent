package com.kyle.salesAgent.agent;

import com.kyle.salesAgent.memory.MysqlChatMemoryStore;
import com.kyle.salesAgent.tool.*;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Function;

/**
 * 配置销售分析 Agent 使用的模型、工具和按会话隔离的对话记忆。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/9/23 14:10
 */
@Configuration
@RequiredArgsConstructor
public class SalesAgentConfig {

    private final ChatModel chatLanguageModel;
    private final StreamingChatModel streamingChatModel;
    private final SalesQueryTool salesQueryTool;
    private final SalesSummaryTool salesSummaryTool;
    private final SalesTrendTool salesTrendTool;
    private final ChartGeneratorTool chartGeneratorTool;
    private final AnomalyDetectionTool anomalyDetectionTool;
    private final MysqlChatMemoryStore chatMemoryStore;   // 注入持久化存储

    /**
     * 创建同时支持同步和流式调用的 Agent。
     * 每个会话只保留最近 20 条消息，消息通过 MySQL 持久化存储。
     *
     * @return 配置完成的销售分析 Agent
     */
    @Bean
    public SalesAgent salesAgent() {
        return AiServices.builder(SalesAgent.class)
                .chatModel(chatLanguageModel)
                .streamingChatModel(streamingChatModel)
                .tools(salesQueryTool,
                        salesSummaryTool,
                        salesTrendTool,
                        chartGeneratorTool,
                        anomalyDetectionTool)
                .chatMemoryProvider(memoryId ->
                        MessageWindowChatMemory.builder()
                                .id(memoryId)
                                .maxMessages(20)         // 保留最近 20 条消息
                                .chatMemoryStore(chatMemoryStore)
                                .build())
                .build();
    }
}
