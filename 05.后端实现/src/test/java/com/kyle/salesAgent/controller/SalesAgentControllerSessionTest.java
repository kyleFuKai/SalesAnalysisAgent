package com.kyle.salesAgent.controller;

import com.kyle.salesAgent.agent.SalesAgent;
import com.kyle.salesAgent.audit.AuditService;
import com.kyle.salesAgent.memory.MysqlChatMemoryStore;
import com.kyle.salesAgent.security.UserContext;
import com.kyle.salesAgent.security.UserRateLimiter;
import com.kyle.salesAgent.service.SalesQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 清空会话必须同步驱逐模型记忆和回答缓存，缓存不可用时保持 fail-closed。 */
class SalesAgentControllerSessionTest {

    private final SalesAgent agent = mock(SalesAgent.class);
    private final MysqlChatMemoryStore memoryStore = mock(MysqlChatMemoryStore.class);
    private final StringRedisTemplate versionRedis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final SalesAgentController controller = new SalesAgentController(agent, memoryStore,
            mock(UserRateLimiter.class), mock(AuditService.class), mock(RedisTemplate.class),
            versionRedis, mock(SalesQueryService.class));

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void clearInvalidatesBothMemoryAndAnswerCache() {
        UserContext.set(new UserContext.UserInfo(2L, "张伟", "SALES_REP", 1L, 2L));
        ReflectionTestUtils.setField(controller, "answerTtlSeconds", 300L);
        when(versionRedis.opsForValue()).thenReturn(values);
        when(values.increment("answer:generation:2:session-1")).thenReturn(1L);

        assertEquals(HttpStatus.OK, controller.clearSession("session-1").getStatusCode());

        verify(values).increment("answer:generation:2:session-1");
        verify(versionRedis).expire("answer:generation:2:session-1", Duration.ofSeconds(600));
        verify(agent).evictChatMemory("2:session-1");
        verify(memoryStore).deleteMessages("2:session-1");
    }

    @Test
    void failedInvalidationDoesNotPretendClearSucceeded() {
        UserContext.set(new UserContext.UserInfo(2L, "张伟", "SALES_REP", 1L, 2L));
        when(versionRedis.opsForValue()).thenThrow(new IllegalStateException("Redis unavailable"));

        assertThrows(ResponseStatusException.class, () -> controller.clearSession("session-1"));
        verifyNoInteractions(agent, memoryStore);
    }

    @Test
    void answerCacheOnlyAppliesToConsecutiveIdenticalQuestion() {
        UserContext.set(new UserContext.UserInfo(2L, "张伟", "SALES_REP", 1L, 2L));
        when(versionRedis.opsForValue()).thenReturn(values);
        when(values.get("answer:last-question:2:session-1")).thenReturn("上一个问题");

        Boolean changedQuestion = ReflectionTestUtils.invokeMethod(controller,
                "isConsecutiveRepeat", UserContext.get(), "session-1", "新的问题");
        Boolean repeatedQuestion = ReflectionTestUtils.invokeMethod(controller,
                "isConsecutiveRepeat", UserContext.get(), "session-1", " 上一个问题 ");

        assertEquals(Boolean.FALSE, changedQuestion);
        assertEquals(Boolean.TRUE, repeatedQuestion);
    }
}
