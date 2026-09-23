package com.kyle.salesAgent.controller;

import com.kyle.salesAgent.agent.SalesAgent;
import com.kyle.salesAgent.memory.MysqlChatMemoryStore;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.LocalDate;

/**
 * 销售分析 Agent 的 HTTP 接口。
 * 提供同步问答、SSE 流式问答和清理会话记忆的接口。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/9/24 06:37
 */
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
@Slf4j
public class SalesAgentController {

    /** 执行对话并按会话 ID 维护上下文的 Agent。 */
    private final SalesAgent salesAgent;

    /**
     * 接收用户提问并返回 Agent 的同步回答。
     * 请求体校验通过后，调用 Agent 并返回回答。耗时包含模型与工具调用。
     *
     * @param request 包含会话 ID 与本轮提问的请求体
     * @return 会话 ID、回答内容和处理耗时
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        log.info("接收请求: sessionId={}, message={}", request.sessionId(), request.message());
        long start = System.currentTimeMillis();

        String reply = salesAgent.chat(request.sessionId(), request.message(), LocalDate.now().toString());

        long duration = System.currentTimeMillis() - start;
        log.info("请求完成: sessionId={}, durationMs={}", request.sessionId(), duration);

        return ResponseEntity.ok(new ChatResponse(request.sessionId(), reply, duration));
    }

    /** 按会话 ID 持久化对话消息的存储组件。 */
    private final MysqlChatMemoryStore chatMemoryStore;

    /**
     * 删除指定会话在持久化存储中的对话消息。
     * 当前仅按会话 ID 删除，尚未校验会话归属。
     *
     * @param sessionId 要清理的会话 ID
     * @return 空响应体
     */
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable String sessionId) {
        chatMemoryStore.deleteMessages(sessionId);
        return ResponseEntity.ok().build();
    }

    /**
     * 接收用户提问并以 SSE 逐步返回回答。
     * token 事件携带回答片段，done 表示正常结束，error 表示生成失败。
     *
     * @param request 包含会话 ID 与本轮提问的请求体
     * @return 供客户端订阅的 SSE 事件流
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@Valid @RequestBody ChatRequest request) {

        log.info("流式请求: sessionId={}", request.sessionId());

        // 将 LangChain4j 的流式回调转换为可由 HTTP 接口发送的事件流。
        return Flux.create(sink -> {
            salesAgent.chatStream(request.sessionId(), request.message(), LocalDate.now().toString())
                    .onPartialResponse(token -> {
                        // 每个 token（词片）推送一个 SSE 事件
                        sink.next(ServerSentEvent.<String>builder()
                                .event("token")
                                .data(token)
                                .build());
                    })
                    .onCompleteResponse(response -> {
                        // 推送结束信号
                        sink.next(ServerSentEvent.<String>builder()
                                .event("done")
                                .data("[DONE]")
                                .build());
                        sink.complete();
                        log.info("流式响应完成: sessionId={}", request.sessionId());
                    })
                    .onError(error -> {
                        log.error("流式响应出错: sessionId={}", request.sessionId(), error);
                        sink.next(ServerSentEvent.<String>builder()
                                .event("error")
                                .data("服务暂时不可用，请稍后重试")
                                .build());
                        sink.complete();
                    })
                    .start();
        });
    }
}
