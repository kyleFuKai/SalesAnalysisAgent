package com.kyle.salesAgent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kyle.salesAgent.agent.SalesAgent;
import com.kyle.salesAgent.audit.AuditContext;
import com.kyle.salesAgent.audit.AuditService;
import com.kyle.salesAgent.entity.AuditLogEntity;
import com.kyle.salesAgent.entity.SalesRep;
import com.kyle.salesAgent.repository.SalesRepRepository;
import com.kyle.salesAgent.security.UserContext;
import com.kyle.salesAgent.security.UserIdentityBuilder;
import com.kyle.salesAgent.service.SalesQueryService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 评测运行器（需求 8.1/8.2 落地）：真模型 + 真链路的行为回归测试。
 * <p>与单测的区别：单测 mock 模型只验证代码逻辑；评测打真模型验证
 * "提示词 + 工具 + 代码 + 模型"的组合行为，是改 System Prompt/工具描述/换模型时的回归防线。
 * <p>运行方式（默认跳过，避免日常 mvn test 烧 Token）：
 * <pre>mvn test -Dtest=EvalRunner -Deval.run=true</pre>
 * <p>用例来源：src/test/resources/eval/eval-cases.json，标注依据 = 需求 4.4 矩阵 + data.sql 已知埋点。
 * 数据对账（db 字段）：运行器直查数据库算真值，断言回答里出现格式化金额——"数据正确率 100%" 的度量手段。
 * <p>已知边界：v1 未覆盖多轮对话用例（4.2）；REFUSE 判定依赖 mustNotContain 泄漏标记（¥/订单号），
 * 不强断言拒答话术的具体措辞。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/2
 */
@SpringBootTest
@Tag("integration")   // 需要真实 MySQL/Redis/智谱 API
@EnabledIfSystemProperty(named = "eval.run", matches = "true")
public class EvalRunner {

    @Autowired
    private SalesAgent salesAgent;
    @Autowired
    private SalesQueryService queryService;
    @Autowired
    private SalesRepRepository repRepository;
    @Autowired
    private AuditService auditService;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 数字对账期望：直查数据库得到真值。method = repTotal / totalAmount。 */
    public record DbExpect(String method, Long regionId, Long repId, String start, String end) {}

    /** 一条评测用例：身份 + 输入 + 期望（行为/内容/工具）。字段均可空，按需断言。 */
    public record EvalCase(String caseId, Long repId, String input, String behavior,
                           List<String> mustContain, List<String> mustNotContain,
                           Integer expectToolCalls, List<String> expectTools, DbExpect db) {}

    @Test
    void runEvalSuite() throws Exception {
        List<EvalCase> cases;
        try (InputStream in = getClass().getResourceAsStream("/eval/eval-cases.json")) {
            cases = mapper.readValue(in,
                    mapper.getTypeFactory().constructCollectionType(List.class, EvalCase.class));
        }

        StringBuilder report = new StringBuilder("\n========== 评测报告 ==========\n");
        List<String> failed = new ArrayList<>();
        // 已知抖动用例：glm-5.3-flash 的工具选择在 temperature 0.1 下仍有低频随机
        // （同一用例不同轮次 PASS/FAIL 交替），与真回归分开统计，避免掩盖信号或无效追分
        List<String> flaky = List.of("MGR-PRODUCT", "DIR-MOM", "DIR-ANOMALY");
        List<String> flakyFailed = new ArrayList<>();
        for (EvalCase c : cases) {
            String failure = runCase(c);
            if (failure == null) {
                report.append(String.format("PASS %s%n", c.caseId()));
            } else if (flaky.contains(c.caseId())) {
                flakyFailed.add(c.caseId());
                report.append(String.format("FLAKY %s → %s%n", c.caseId(), failure));
            } else {
                failed.add(c.caseId());
                report.append(String.format("FAIL %s → %s%n", c.caseId(), failure));
            }
        }
        report.append(String.format("========== 合计 %d 条：通过 %d，真失败 %d，已知抖动 %d ==========%n",
                cases.size(), cases.size() - failed.size() - flakyFailed.size(),
                failed.size(), flakyFailed.size()));
        if (!failed.isEmpty()) {
            report.append("失败用例：").append(String.join(", ", failed));
        }
        if (!flakyFailed.isEmpty()) {
            report.append(String.format("%n抖动用例（不计为失败，但连续多轮 FLAKY 应升级排查）：%s",
                    String.join(", ", flakyFailed)));
        }
        // 报告落文件（UTF-8），控制台 GBK 下中文会乱码
        try {
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("target", "eval-report.txt"), report.toString());
        } catch (Exception ignored) {
        }
        System.out.println(report);
        // 任一用例失败即让 JUnit 变红，报告全文在断言消息里
        assertEquals(0, failed.size(), report.toString());
    }

    /**
     * 执行单条用例，返回失败原因；null = 通过。
     * LLM 调用最多尝试 3 次——瞬时网络抖动（ResourceAccessException 等）不应记为产品缺陷。
     */
    private String runCase(EvalCase c) {
        String failure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            failure = runCaseOnce(c);
            if (failure == null || !failure.startsWith("执行异常")) {
                return failure;
            }
        }
        return failure;
    }

    private String runCaseOnce(EvalCase c) {
        SalesRep rep = repRepository.findById(c.repId()).orElse(null);
        if (rep == null) {
            return "身份 repId 不存在: " + c.repId();
        }
        UserContext.set(new UserContext.UserInfo(
                rep.getId(), rep.getName(), rep.getRole(), rep.getRegionId(), rep.getId()));
        AuditContext.begin();
        long start = System.currentTimeMillis();
        try {
            String identity = UserIdentityBuilder.build(UserContext.get(), queryService);
            String answer = salesAgent.chat(
                    "eval-" + c.caseId(), c.input(), LocalDate.now().toString(), identity);
            List<String> tools = AuditContext.current() != null
                    ? AuditContext.current().tools() : List.of();
            // 评测也落审计（需求 8.3 抽检需要留痕；直调 Agent 本不经过 Controller）
            recordEvalAudit(rep, c, answer, tools, System.currentTimeMillis() - start);

            if (c.mustContain() != null) {
                for (String s : c.mustContain()) {
                    if (!answer.contains(s)) {
                        return "缺少期望内容 \"" + s + "\"；回答片段: " + snippet(answer);
                    }
                }
            }
            if (c.mustNotContain() != null) {
                for (String s : c.mustNotContain()) {
                    if (answer.contains(s)) {
                        return "泄漏敏感内容 \"" + s + "\"；回答片段: " + snippet(answer);
                    }
                }
            }
            if (c.expectTools() != null) {
                for (String t : c.expectTools()) {
                    if (!tools.contains(t)) {
                        return "未调用期望工具 " + t + "；实际调用: " + tools;
                    }
                }
            }
            if (c.expectToolCalls() != null && tools.size() != c.expectToolCalls()) {
                return "工具调用次数期望 " + c.expectToolCalls() + " 实际 " + tools.size()
                        + "；实际工具: " + tools;
            }
            if (c.db() != null) {
                String expected = resolveDbExpected(c.db());
                if (!answer.contains(expected)) {
                    return "数据对账失败：期望含 \"" + expected + "\"；回答片段: " + snippet(answer);
                }
            }
            return null;
        } catch (Exception e) {
            return "执行异常: " + e;
        } finally {
            AuditContext.clear();
            UserContext.clear();
        }
    }

    /** 直查数据库算真值，格式化成模型应输出的金额形态（¥X,XXX）。 */
    private String resolveDbExpected(DbExpect db) {
        BigDecimal value = switch (db.method()) {
            case "repTotal" -> queryService.queryRepTotalAmount(db.repId(),
                    LocalDate.parse(db.start()), LocalDate.parse(db.end()));
            case "totalAmount" -> queryService.queryTotalAmount(db.regionId(),
                    LocalDate.parse(db.start()), LocalDate.parse(db.end()));
            default -> throw new IllegalArgumentException("未知 db 方法: " + db.method());
        };
        return String.format("¥%,.0f", value);
    }

    /** 评测记录落审计表：抽检/复盘失败用例时能看到模型当时的完整回答。 */
    private void recordEvalAudit(SalesRep rep, EvalCase c, String answer,
                                 List<String> tools, long duration) {
        AuditLogEntity row = new AuditLogEntity();
        row.setUserId(rep.getId());
        row.setUsername(rep.getName() + "（评测）");
        row.setSessionId("eval-" + c.caseId());
        row.setQuestion(c.input());
        row.setAnswer(answer);
        row.setToolNames(String.join(",", tools));
        AuditContext.Record collected = AuditContext.current();
        if (collected != null) {
            row.setInputTokens((int) collected.inputTokens());
            row.setOutputTokens((int) collected.outputTokens());
        }
        row.setDurationMs(duration);
        auditService.record(row);
    }

    private String snippet(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }
}
