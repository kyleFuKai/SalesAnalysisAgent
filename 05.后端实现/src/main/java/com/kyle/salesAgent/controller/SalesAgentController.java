package com.kyle.salesAgent.controller;

import com.kyle.salesAgent.agent.SalesAgent;
import com.kyle.salesAgent.audit.AuditContext;
import com.kyle.salesAgent.audit.AuditService;
import com.kyle.salesAgent.entity.AuditLogEntity;
import com.kyle.salesAgent.config.RedisSafe;
import com.kyle.salesAgent.exception.PermissionDeniedException;
import com.kyle.salesAgent.memory.MysqlChatMemoryStore;
import com.kyle.salesAgent.security.UserContext;
import com.kyle.salesAgent.security.UserIdentityBuilder;
import com.kyle.salesAgent.security.UserRateLimiter;
import com.kyle.salesAgent.service.SalesQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDate;

/**
 * 销售分析 Agent 的 HTTP 接口。
 * 提供同步问答、SSE 流式问答和清理会话记忆的接口。
 *
 * <p>会话归属：真实记忆 ID = "userId:sessionId"，在 Controller 层统一拼接——
 * 不同用户即使传相同 sessionId 也互不可见，防止越权读取他人对话（架构 3.2.3 ChatMemory 军规）。
 * 所有接口要求登录身份（UserContext），无身份直接拒绝。
 *
 * <p>回答缓存：相同问题 + 相同身份 + 相同会话在 TTL 内直接返回上次的最终回答，
 * 不再调模型（省 Token，需求 7.3）；数据新鲜度由 TTL 兜底（需求 7.2）。
 *
 * <p>审计日志（需求 7.5）：同步路径完整记录问答/耗时/Token/工具；流式路径的
 * 工具与 Token 明细跨线程暂缺，为 v2 待办。
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

    /** 按会话 ID 持久化对话消息的存储组件。 */
    private final MysqlChatMemoryStore chatMemoryStore;

    /** 按用户限频器（架构 3.2.2），防止单账号刷接口消耗 Token。 */
    private final UserRateLimiter rateLimiter;

    /** 审计日志异步落库。 */
    private final AuditService auditService;

    /** 回答缓存读写。 */
    private final RedisTemplate<String, Object> redisTemplate;

    /** 大区 ID → 名称翻译（System Prompt 身份注入用）。 */
    private final SalesQueryService salesQueryService;

    /** 回答缓存 TTL（秒），对应需求 7.2 的近实时档。 */
    @Value("${sales-agent.cache.answer-ttl-seconds:300}")
    private long answerTtlSeconds;

    /**
     * 取当前登录身份；没有直接拒绝（工具层会 fail-closed，这里提前失败给出明确提示）。
     */
    private UserContext.UserInfo requireUser() {
        UserContext.UserInfo user = UserContext.get();
        if (user == null) {
            throw new PermissionDeniedException("未获取到用户身份，请先登录");
        }
        return user;
    }

    /**
     * 会话与用户绑定后的真实记忆 ID：不同用户即使传相同 sessionId 也互不可见。
     */
    private String scopedMemoryId(UserContext.UserInfo user, String sessionId) {
        return user.userId() + ":" + sessionId;
    }

    /**
     * 回答缓存 Key：权限标签 + 会话 + 归一化问题。同会话内重复提问才命中，
     * 不同会话不共享（多轮上下文不同，保守不缓存）。
     */
    private String answerKey(UserContext.UserInfo user, String sessionId, String message) {
        return String.format("answer:%s:%s:%s",
                UserContext.cacheScopeTag(), sessionId, message.trim());
    }

    /**
     * 组装审计记录并交给 AuditService 异步落库（失败不影响业务）。
     */
    private void recordAudit(UserContext.UserInfo user, String sessionId,
                             String question, String reply, long duration) {
        AuditLogEntity row = new AuditLogEntity();
        row.setUserId(user.userId());
        row.setUsername(user.username());
        row.setSessionId(sessionId);
        row.setQuestion(question);
        row.setAnswer(reply);
        AuditContext.Record collected = AuditContext.current();
        if (collected != null) {
            row.setToolNames(String.join(",", collected.tools()));
            row.setInputTokens((int) collected.inputTokens());
            row.setOutputTokens((int) collected.outputTokens());
        }
        row.setDurationMs(duration);
        auditService.record(row);
    }

    /**
     * 接收用户提问并返回 Agent 的同步回答。
     * 请求体校验通过后，调用 Agent 并返回回答。耗时包含模型与工具调用。
     *
     * @param request 包含会话 ID 与本轮提问的请求体
     * @return 会话 ID、回答内容和处理耗时
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        UserContext.UserInfo user = requireUser();
        if (!rateLimiter.tryAcquire(user.userId())) {
            log.warn("限流拦截: userId={}", user.userId());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        // 回答缓存：同会话内重复提问直接返回，不再消耗模型 Token
        String cacheKey = answerKey(user, request.sessionId(), request.message());
        Object cachedAnswer = RedisSafe.get(redisTemplate, cacheKey, null);
        if (cachedAnswer != null) {
            log.info("回答缓存命中: sessionId={}", request.sessionId());
            return ResponseEntity.ok(new ChatResponse(request.sessionId(), (String) cachedAnswer, 0L));
        }

        log.info("接收请求: userId={}, sessionId={}, message={}",
                user.userId(), request.sessionId(), request.message());
        long start = System.currentTimeMillis();

        // 审计采集从进入 Agent 前开始（工具钩子/Token 监听都往里写），finally 里落库并清理
        AuditContext.begin();
        String reply = null;
        try {
            reply = salesAgent.chat(
                    scopedMemoryId(user, request.sessionId()), request.message(),
                    LocalDate.now().toString(), UserIdentityBuilder.build(user, salesQueryService));
        } finally {
            recordAudit(user, request.sessionId(), request.message(), reply,
                    System.currentTimeMillis() - start);
            AuditContext.clear();
        }

        long duration = System.currentTimeMillis() - start;
        log.info("请求完成: sessionId={}, durationMs={}", request.sessionId(), duration);

        RedisSafe.set(redisTemplate, cacheKey, reply, Duration.ofSeconds(answerTtlSeconds));
        return ResponseEntity.ok(new ChatResponse(request.sessionId(), reply, duration));
    }

    /**
     * 删除当前用户指定会话在持久化存储中的对话消息。
     * 记忆 ID 带用户前缀，别人的会话删不到。
     *
     * @param sessionId 要清理的会话 ID
     * @return 空响应体
     */
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable String sessionId) {
        UserContext.UserInfo user = requireUser();
        chatMemoryStore.deleteMessages(scopedMemoryId(user, sessionId));
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
        UserContext.UserInfo user = requireUser();
        if (!rateLimiter.tryAcquire(user.userId())) {
            log.warn("流式限流拦截: userId={}", user.userId());
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
        }
        log.info("流式请求: userId={}, sessionId={}", user.userId(), request.sessionId());
        String memoryId = scopedMemoryId(user, request.sessionId());

        // 回答缓存（流式）：命中把整段回答作为一个 token 事件推送，效果与逐字等价
        String cacheKey = answerKey(user, request.sessionId(), request.message());
        Object cachedAnswer = RedisSafe.get(redisTemplate, cacheKey, null);
        if (cachedAnswer != null) {
            log.info("流式回答缓存命中: sessionId={}", request.sessionId());
            return Flux.just(
                    ServerSentEvent.<String>builder().event("token").data((String) cachedAnswer).build(),
                    ServerSentEvent.<String>builder().event("done").data("[DONE]").build());
        }

        // 流式路径的审计在完成/出错回调里落库（Token/工具明细跨线程暂缺，v2 待办）
        long start = System.currentTimeMillis();
        StringBuilder answerBuffer = new StringBuilder();
        String userIdentity = UserIdentityBuilder.build(user, salesQueryService);

        // 将 LangChain4j 的流式回调转换为可由 HTTP 接口发送的事件流。
        return Flux.create(sink -> {
            salesAgent.chatStream(memoryId, request.message(), LocalDate.now().toString(), userIdentity)
                    .onPartialResponse(token -> {
                        // 每个 token（词片）推送一个 SSE 事件
                        answerBuffer.append(token);
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
                        long duration = System.currentTimeMillis() - start;
                        recordAudit(user, request.sessionId(), request.message(),
                                answerBuffer.toString(), duration);
                        RedisSafe.set(redisTemplate, cacheKey, answerBuffer.toString(),
                                Duration.ofSeconds(answerTtlSeconds));
                        log.info("流式响应完成: sessionId={}, durationMs={}", request.sessionId(), duration);
                    })
                    .onError(error -> {
                        log.error("流式响应出错: sessionId={}", request.sessionId(), error);
                        sink.next(ServerSentEvent.<String>builder()
                                .event("error")
                                .data("服务暂时不可用，请稍后重试")
                                .build());
                        sink.complete();
                        recordAudit(user, request.sessionId(), request.message(),
                                null, System.currentTimeMillis() - start);
                    })
                    .start();
        });
    }
}
