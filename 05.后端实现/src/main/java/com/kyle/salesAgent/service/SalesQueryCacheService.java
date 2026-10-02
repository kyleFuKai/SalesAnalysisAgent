package com.kyle.salesAgent.service;

import com.kyle.salesAgent.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
/**
 * 手动版查询缓存包装器（RedisTemplate 直读写，学习/备用）。
 * <p>注意：{@code queryTotalAmount} 本身已有 @Cacheable（"sales-summary" 区），
 * 本类与它是两套平行机制——当前没有任何调用方，若将来接线，二选一，
 * 不要让同一条查询同时走两层缓存（两份 Key、两份 TTL，口径易错乱）。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/2 09:04
 */
@Service
@RequiredArgsConstructor
public class SalesQueryCacheService {

    private final SalesQueryService queryService;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${sales-agent.cache.query-ttl-seconds:300}")
    private long cacheTtlSeconds;

    public BigDecimal queryTotalAmountCached(Long regionId, LocalDate start, LocalDate end) {
        // Key 末尾拼权限标签（架构 4.3）：主管"不传大区=本区"和总监"不传大区=全公司"
        // 的结果数字不同，没有标签的话后者会拿到前者的数据当全公司
        String cacheKey = String.format("total_amount:%s:%s:%s:%s",
                regionId != null ? regionId : "all", start, end,
                UserContext.cacheScopeTag());

        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return new BigDecimal(cached.toString());
        }

        BigDecimal result = queryService.queryTotalAmount(regionId, start, end);
        redisTemplate.opsForValue().set(cacheKey, result.toPlainString(),
                Duration.ofSeconds(cacheTtlSeconds));
        return result;
    }
}
