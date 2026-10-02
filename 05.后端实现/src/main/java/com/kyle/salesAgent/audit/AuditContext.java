package com.kyle.salesAgent.audit;

/**
 * 单次请求的审计数据采集器（ThreadLocal）。
 * <p>写入方：SalesAgentConfig 的工具调用钩子（记录工具名）、TokenUsageListener
 * （累加 Token）。读取方：Controller 在请求结束时取出并交给 {@link AuditService} 落库。
 * <p>作用域说明：同步路径（方法调用在同一线程）能完整采集；SSE 流式路径的工具执行
 * 发生在其他线程，采集不到——流式审计只有问答与耗时，Token/工具明细为 v2 待办
 * （需按 memoryId 关联）。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/2
 */
public final class AuditContext {

    /** 一次请求内累积的审计明细。 */
    public static class Record {
        private final java.util.List<String> tools = new java.util.ArrayList<>();
        private long inputTokens;
        private long outputTokens;

        public java.util.List<String> tools() {
            return tools;
        }

        public long inputTokens() {
            return inputTokens;
        }

        public long outputTokens() {
            return outputTokens;
        }
    }

    private static final ThreadLocal<Record> HOLDER = new ThreadLocal<>();

    /** Controller 在请求开始时调用；仅同步路径有效。 */
    public static void begin() {
        HOLDER.set(new Record());
    }

    /** 取当前请求的采集器；未 begin（流式路径/其他线程）返回 null，写入方需判空。 */
    public static Record current() {
        return HOLDER.get();
    }

    /** 记录一次工具调用（AgentConfig 钩子写入）。 */
    public static void addTool(String toolName) {
        Record r = HOLDER.get();
        if (r != null) {
            r.tools.add(toolName);
        }
    }

    /** 累加一次模型响应的 Token 用量（TokenUsageLogger 写入）。 */
    public static void addTokens(long input, long output) {
        Record r = HOLDER.get();
        if (r != null) {
            r.inputTokens += input;
            r.outputTokens += output;
        }
    }

    /** 请求结束必须清理（同 UserContext.clear 的线程复用纪律）。 */
    public static void clear() {
        HOLDER.remove();
    }

    private AuditContext() {
    }
}
