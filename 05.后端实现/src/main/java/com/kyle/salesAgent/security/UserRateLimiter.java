package com.kyle.salesAgent.security;

import com.google.common.util.concurrent.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按用户限频（架构 3.2.2）：每个登录用户一个 Guava 令牌桶，防止单个账号刷接口
 * 消耗 Token 成本。
 * <p>单实例内存态：重启清零、多实例不共享——多实例部署时需切换 Redis 实现（架构已注明）。
 * 实例数以登录用户数为上限（全公司约 20 人），Map 不需要淘汰策略。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/2
 */
@Component
public class UserRateLimiter {

    private final double permitsPerSecond;

    private final Map<Long, RateLimiter> limiters = new ConcurrentHashMap<>();

    public UserRateLimiter(@Value("${sales-agent.rate-limit.per-user-qps:0.5}") double permitsPerSecond) {
        this.permitsPerSecond = permitsPerSecond;
    }

    /** 尝试获取一次通行许可；默认 0.5 QPS，即同一用户最快 2 秒一次。 */
    public boolean tryAcquire(Long userId) {
        return limiters.computeIfAbsent(userId, id -> RateLimiter.create(permitsPerSecond)).tryAcquire();
    }
}
