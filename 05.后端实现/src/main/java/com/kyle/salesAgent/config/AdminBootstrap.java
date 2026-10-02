package com.kyle.salesAgent.config;

import com.kyle.salesAgent.entity.SalesRep;
import com.kyle.salesAgent.repository.SalesRepRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** 首次部署时用环境变量创建唯一初始管理员；绝不预置通用密码。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrap implements ApplicationRunner {
    private final SalesRepRepository reps;
    private final PasswordEncoder encoder;

    @Value("${APP_BOOTSTRAP_ADMIN_PASSWORD:}")
    private String bootstrapPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (bootstrapPassword.isBlank()) return;
        try {
            if (reps.existsByRole("SYS_ADMIN")) {
                log.info("管理员已存在，跳过初始管理员创建");
                return;
            }
            if (bootstrapPassword.length() < 12
                    || bootstrapPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
                throw new IllegalStateException("初始管理员密码需至少 12 位且不超过 72 字节");
            }
            SalesRep admin = new SalesRep();
            admin.setName("系统管理员");
            admin.setRole("SYS_ADMIN");
            admin.setRegionId(null);
            admin.setPassword(encoder.encode(bootstrapPassword));
            admin.setActive(true);
            Long id = reps.save(admin).getId();
            log.warn("已创建初始管理员账号，销售员 ID={}；请立即移除 APP_BOOTSTRAP_ADMIN_PASSWORD 环境变量", id);
        } finally {
            bootstrapPassword = "";
        }
    }
}
