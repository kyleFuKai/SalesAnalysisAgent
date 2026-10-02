package com.kyle.salesAgent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;

/**
 * Redis 手动缓存的安全读写：连接失败/超时只记日志并降级，绝不让缓存故障
 * 升级为业务故障。与 RedisConfig 的 CacheErrorHandler 同一原则——那个管
 * @Cacheable 注解路径，这里管 RedisTemplate 手动路径。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/2
 */
@Slf4j
public final class RedisSafe {

    /** 读缓存；任何异常返回 fallback（调用方据此回源数据库）。 */
    @SuppressWarnings("unchecked")
    public static <T> T get(RedisTemplate<String, Object> redisTemplate, String key, T fallback) {
        try {
            return (T) redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis 读取失败，走降级 | key={} | err={}", key, e.toString());
            return fallback;
        }
    }

    /** 写缓存；任何异常只记日志（本次不缓存，下次查询重试）。 */
    public static void set(RedisTemplate<String, Object> redisTemplate, String key, Object value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.warn("Redis 写入失败，本次不缓存 | key={} | err={}", key, e.toString());
        }
    }

    private RedisSafe() {
    }
}
