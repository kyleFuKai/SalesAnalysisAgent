package com.kyle.salesAgent;

import com.kyle.salesAgent.agent.SalesAgent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
/**
 *
 * @author kyle
 * @version 1.0
 * @date 2026/9/23 15:20
 */
@SpringBootTest
public class SalesAgentSmokeTest {

    @Autowired
    private SalesAgent salesAgent;

    // chat() 的第三/四个参数对应 System Prompt 里的 {{today}} 和 {{userIdentity}} 变量
    private final String today = LocalDate.now().toString();
    // 冒烟测试以总监身份运行，可覆盖全部能力
    private final String userIdentity = "黄总（销售总监）。数据范围：全公司所有数据";

    @Test
    void smokeTest() {
        String response = salesAgent.chat(
                "test-session-001",
                "你好，你能做什么？",
                today,
                userIdentity);
        System.out.println("Agent 回答：" + response);
    }

    @Test
    void toolCallTest() {
        String response = salesAgent.chat(
                "test-session-002",
                "近6个月的月度销售趋势是什么？",
                today,
                userIdentity);
        System.out.println("Agent 回答：" + response);
    }
}
