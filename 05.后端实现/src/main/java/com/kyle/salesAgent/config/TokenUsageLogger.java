package com.kyle.salesAgent.config;

import com.kyle.salesAgent.audit.AuditContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * LLM Token 用量监听：输入/输出 Token 计入 Micrometer 指标，并按配置单价估算费用。
 * <p>单价从 application.yml 读取（元/千 Token），换模型只改配置，不改代码。
 * 对应需求 7.3 成本控制 / 7.5 可追溯。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/9/24 10:59
 */
@Component
@Slf4j
public class TokenUsageLogger implements ChatModelListener {

    private final Counter inputTokenCounter;
    private final Counter outputTokenCounter;
    private final BigDecimal inputPricePerK;
    private final BigDecimal outputPricePerK;

    public TokenUsageLogger(MeterRegistry meterRegistry,
                            @Value("${llm-cost.input-price-per-k:0}") BigDecimal inputPricePerK,
                            @Value("${llm-cost.output-price-per-k:0}") BigDecimal outputPricePerK) {
        this.inputTokenCounter = Counter.builder("llm.tokens.input")
                .description("Input tokens consumed")
                .register(meterRegistry);
        this.outputTokenCounter = Counter.builder("llm.tokens.output")
                .description("Output tokens consumed")
                .register(meterRegistry);
        this.inputPricePerK = inputPricePerK;
        this.outputPricePerK = outputPricePerK;
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        var usage = responseContext.chatResponse().tokenUsage();
        if (usage != null) {
            int input = usage.inputTokenCount() != null ? usage.inputTokenCount() : 0;
            int output = usage.outputTokenCount() != null ? usage.outputTokenCount() : 0;

            inputTokenCounter.increment(input);
            outputTokenCounter.increment(output);

            // 审计采集：同步路径下本次会话的 Token 累进 AuditContext（无上下文时静默跳过）
            AuditContext.addTokens(input, output);

            if (inputPricePerK.signum() > 0 || outputPricePerK.signum() > 0) {
                // 单价未配置（为 0）时只记 Token 数，不算钱，避免拿错价格输出误导性费用
                BigDecimal cost = BigDecimal.valueOf(input).multiply(inputPricePerK)
                        .add(BigDecimal.valueOf(output).multiply(outputPricePerK))
                        .divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP);
                log.info("Token 用量 | 输入：{} | 输出：{} | 本次费用约：¥{}",
                        input, output, cost);
            } else {
                log.info("Token 用量 | 输入：{} | 输出：{}（单价未配置，不估算费用）",
                        input, output);
            }
        }
    }
}
