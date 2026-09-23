package com.kyle.salesAgent.controller;

/**
 * Agent 同步对话接口的响应数据。
 *
 * @param sessionId 本次请求对应的会话 ID
 * @param reply Agent 返回的回答
 * @param durationMs 本次对话处理耗时，单位为毫秒
 * @author kyle
 * @version 1.0
 * @date 2026/9/24 06:36
 */
public record ChatResponse(
        String sessionId,
        String reply,
        long durationMs
) {}
