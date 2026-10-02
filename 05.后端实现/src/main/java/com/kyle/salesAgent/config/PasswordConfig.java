package com.kyle.salesAgent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器配置：BCrypt（自带随机盐，同一明文每次加密结果不同、验证用 matches）。
 * <p>只引入 spring-security-crypto 工具包，<b>没有</b>启用 Spring Security 过滤器链——
 * 认证由 Sa-Token 负责，这里只借它的哈希算法。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/1
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
