package com.kyle.salesAgent;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文装载冒烟测试：需要真实 MySQL/Redis/.env 环境。
 * 标记 integration —— CI 与默认 mvn test 跳过（surefire excludedGroups），
 * 本地运行：IDEA 直接跑，或 mvn test -Dsurefire.excludedGroups=
 */
@SpringBootTest
@Tag("integration")
class KyleSalesAgentApplicationTests {

    @Test
    void contextLoads() {
    }
}