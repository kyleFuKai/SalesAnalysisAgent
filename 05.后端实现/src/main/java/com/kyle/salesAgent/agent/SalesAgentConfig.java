package com.kyle.salesAgent.agent;

import com.kyle.salesAgent.memory.MysqlChatMemoryStore;
import com.kyle.salesAgent.tool.*;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
                .beforeToolExecution(exec -> {
                    // 审计采集：工具名进当前请求的审计记录（无上下文时静默跳过）
                    com.kyle.salesAgent.audit.AuditContext.addTool(exec.request().name());
                    log.info("▶ 工具调用开始 | 工具：{} | 参数：{}",
                            exec.request().name(),
                            exec.request().arguments());
                })
                .afterToolExecution(exec ->
                        log.info("◀ 工具调用完成 | 工具：{} | 结果长度：{} 字符",
                                exec.request().name(),
                                exec.result() != null ? exec.result().length() : 0))
                .chatMemoryProvider(memoryId ->
                        MessageWindowChatMemory.builder()
                                .id(memoryId)
                                .maxMessages(20)         // 保留最近 20 条消息
                                .chatMemoryStore(chatMemoryStore)
                                .build())
                // 执行护栏（架构 3.2.3）：限制单次问答内连续工具调用轮数，
                // 防止模型循环调工具拖垮 Token 成本与 15 秒目标
                .maxSequentialToolsInvocations(8)
                .build();
    }
}
