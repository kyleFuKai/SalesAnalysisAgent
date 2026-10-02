package com.kyle.salesAgent.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
/**
 * Redis 序列化与缓存配置：对象存 JSON（带类型信息），@Cacheable 各缓存区按数据新鲜度分 TTL。
 * <p>序列化之所以要自定义 ObjectMapper：默认 JDK 序列化存二进制，Redis 里不可读、跨语言不友好；
 * 纯 JSON 又会在反序列化时丢类型（全部变成 LinkedHashMap）。写入 @class 类型信息后才能还原。
 * <p>序列化之外要留意两点：各缓存区 TTL 对齐需求 7.2 的新鲜度分档（近实时数据 ≤5 分钟，
 * 元数据可放宽）；@Cacheable 的 Key 末尾统一拼 UserContext.cacheScopeTag()
 * （角色|大区|人，架构 4.3），不同角色/大区即使问法相同也不会互相命中。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/1 17:28
 */
@Configuration
@EnableCaching
@Slf4j
public class RedisConfig implements CachingConfigurer {

    /**
     * 创建带类型信息的 ObjectMapper。
     * activateDefaultTyping 让 Jackson 在序列化时写入 @class 字段，
     * 反序列化时才能还原成正确的 Java 类型（而不是 LinkedHashMap）。
     * <p>注意：record 是隐式 final 类，在 NON_FINAL typing 下是否携带类型信息
     * 与 Jackson 版本有关——第一次把 record DTO 存进缓存时，务必用集成测试
     * 验证"取回来还是 DTO"，如果拿到 LinkedHashMap，改用专用 DTO class 或换 typing 策略。
     */
    private ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // WRAPPER_ARRAY 将类型信息包在数组里：["com.example.Dto", {...}]
        // 比 AS_PROPERTY 对 List/集合类型更兼容
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.WRAPPER_ARRAY
        );
        return mapper;
    }

    /**
     * 手动读写 Redis 用的模板（如 ChatMemory、自定义缓存逻辑）。
     * Key 用 String 序列化保证 Redis 里可读、好排查；Value 用 JSON。
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(redisObjectMapper());

        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jsonSerializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(jsonSerializer);
        return template;
    }

    /**
     * @Cacheable 注解式缓存的管理器，各缓存区 TTL 按需求 7.2 新鲜度分档：
     * 排名/趋势这类跟着订单走的 ≤5 分钟；大区元数据几乎不变给 30 分钟；
     * 异常检测结果最敏感给 2 分钟。月度趋势现在保守取 5 分钟，以后可按 7.2
     * 放宽到"每日数据更新完成后失效"。
     * <p>Key 设计见架构 4.3：必须含权限维度，具体见类注释。
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(redisObjectMapper());

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(jsonSerializer));

        // 不同缓存区设置不同 TTL
        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();
        cacheConfigs.put("rep-ranking",       defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigs.put("region-ranking",    defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigs.put("monthly-trend",     defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigs.put("sales-summary",     defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigs.put("product-ranking",   defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigs.put("region-meta",       defaultConfig.entryTtl(Duration.ofMinutes(30)));
        cacheConfigs.put("anomaly-detection", defaultConfig.entryTtl(Duration.ofMinutes(2)));

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigs)
                .build();
    }

    /**
     * 缓存降级（可用性）：Redis 故障时 @Cacheable 的读/写/清异常只记日志，
     * 方法照常回源数据库执行——缓存是加速器，不是依赖，缓存层故障
     * 不能升级成全局故障。RedisTemplate 手动路径的降级见 {@link RedisSafe}。
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("缓存读取失败，回源数据库 | cache={} | err={}", cache.getName(), e.toString());
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("缓存写入失败，本次不缓存 | cache={} | err={}", cache.getName(), e.toString());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.warn("缓存清除失败 | cache={} | err={}", cache.getName(), e.toString());
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.warn("缓存清空失败 | cache={} | err={}", cache.getName(), e.toString());
            }
        };
    }
}
