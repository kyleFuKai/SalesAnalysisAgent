package com.kyle.salesAgent.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录接口的双维度限流：账号 ID 防定向猜密，来源 IP 防遍历账号。
 * 当前为单实例内存实现；多实例部署时必须改成共享存储限流。
 */
@Component
public class LoginAttemptLimiter {

    private static final int MAX_KEYS = 10_000;
    private final int perAccount;
    private final int perIp;
    private final long windowNanos;
    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    public LoginAttemptLimiter(
            @Value("${app.auth.login-limit.per-account:5}") int perAccount,
            @Value("${app.auth.login-limit.per-ip:30}") int perIp,
            @Value("${app.auth.login-limit.window-seconds:60}") long windowSeconds) {
        if (perAccount < 1 || perIp < 1 || windowSeconds < 1) {
            throw new IllegalArgumentException("登录限流配置必须为正数");
        }
        this.perAccount = perAccount;
        this.perIp = perIp;
        this.windowNanos = Duration.ofSeconds(windowSeconds).toNanos();
    }

    public boolean tryAcquire(Long accountId, String remoteAddress) {
        String ip = remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
        long now = System.nanoTime();
        // 分别限流，而不是把 IP 与账号拼成一把桶；不信任客户端提供的 X-Forwarded-For。
        boolean ipAllowed = take("ip:" + ip, perIp, now);
        boolean accountAllowed = take("account:" + accountId, perAccount, now);
        return ipAllowed && accountAllowed;
    }

    private boolean take(String key, int limit, long now) {
        if (attempts.size() >= MAX_KEYS && !attempts.containsKey(key)) {
            attempts.entrySet().removeIf(entry -> {
                Deque<Long> queue = entry.getValue();
                synchronized (queue) {
                    prune(queue, now);
                    return queue.isEmpty();
                }
            });
            if (attempts.size() >= MAX_KEYS) return false;
        }
        Deque<Long> queue = attempts.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            prune(queue, now);
            if (queue.size() >= limit) return false;
            queue.addLast(now);
            return true;
        }
    }

    private void prune(Deque<Long> queue, long now) {
        while (!queue.isEmpty() && now - queue.peekFirst() >= windowNanos) {
            queue.removeFirst();
        }
    }
}
