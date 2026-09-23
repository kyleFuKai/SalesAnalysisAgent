package com.kyle.salesAgent.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
/**
 * Agent 同步对话接口的请求参数。
 * 客户端传入会话 ID 和本轮提问，字段在进入 Controller 前完成校验。
 *
 * @param sessionId 会话 ID，同一会话的多轮提问使用相同 ID
 * @param message 本轮用户提问
 * @author kyle
 * @version 1.0
 * @date 2026/9/24 06:29
 */
public record ChatRequest(
        @NotBlank(message = "sessionId 不能为空")
        @Size(max = 100)
        String sessionId,

        @NotBlank(message = "message 不能为空")
        @Size(max = 2000, message = "消息不能超过 2000 字")
        String message
) {}
