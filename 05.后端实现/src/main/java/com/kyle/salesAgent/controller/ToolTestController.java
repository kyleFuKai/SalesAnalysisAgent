package com.kyle.salesAgent.controller;

import com.kyle.salesAgent.entity.SalesRep;
import com.kyle.salesAgent.repository.SalesRepRepository;
import com.kyle.salesAgent.security.UserContext;
import com.kyle.salesAgent.exception.PermissionDeniedException;
import com.kyle.salesAgent.tool.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/**
 * 工具直调测试 Controller —— 绕过 AI 模型直接验证各工具逻辑
 * <p>所有端点共用统一请求体 {@link ToolRequest}：字段覆盖现有全部工具的参数
 * （含环比的对比周期、图表的维度/标题等），各端点按需取用，用不到的不传即可
 * （对象类型字段缺省为 null，端点内给默认值）。新增工具端点时优先扩字段而不是加 record。
 * <p>仅用于开发期 curl/Postman 手测，正式接口走 Agent 链路，上线前本类可整体删除。
 * <p><b>模拟身份</b>：Service 层权限注入（fail-closed）要求每个查询都有用户身份，
 * 本类默认以总监（黄总，repId=13）身份调用——行为与权限接入前的全公司视角一致；
 * 传 {@code asRepId} 可模拟其他角色（如 2=销售员张伟、1=主管李明），用于验证越权拦截。
 * 身份写入 ThreadLocal 后由拦截器 afterCompletion 统一清理，不会跨请求残留。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/9/18
 */
@RestController
@RequestMapping("/test/tool")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.tool-test.enabled", havingValue = "true")
public class ToolTestController {

    private final SalesQueryTool salesQueryTool;
    private final SalesSummaryTool salesSummaryTool;
    private final SalesTrendTool salesTrendTool;
    private final ChartGeneratorTool chartGeneratorTool;
    private final AnomalyDetectionTool anomalyDetectionTool;
    private final SalesRepRepository repRepository;

    /**
     * 统一测试请求体：一个 record 服务全部端点。
     * <p>字段按"通用时间窗 → 环比专属 → 通用 → 图表专属"分组排列；
     * 全部用对象类型（Integer），区分"没传"（null → 端点内默认值）
     * 与"传了 0"（用户显式输入）——int 会把两者混成 0，测试时容易懵。
     */
    record ToolRequest(
            // —— 通用时间窗（明细/汇总/排名/同比/图表共用）——
            String startDate, String endDate,
            // —— 环比专属：对比周期（不传则工具内自动推算等长周期）——
            String currentStart, String currentEnd,
            String prevStart, String prevEnd,
            // —— 范围过滤 ——
            String regionName, String repName,
            // —— 数值参数 ——
            Integer months,          // 趋势/折线图：近 N 个月（趋势缺省 6）
            Integer topN,            // 排名：前 N 名（可传负数测"最差 N 名"）
            Integer limit,           // 明细：最多返回条数
            // —— 图表专属 ——
            String dimension,        // 柱状图 region/rep；饼图 region/category
            String title,            // 图表标题（不传则工具内兜底）
            Long asRepId) {          // 模拟身份：按 repId 以该销售员的角色调用（缺省=总监）
    }

    /**
     * 写入模拟身份：缺省总监（全公司视角，兼容权限接入前的测试习惯），
     * 传 asRepId 则按用户表记录模拟对应角色。查询失败抛 400 级提示由全局异常处理。
     */
    private void simulateIdentity(Long asRepId) {
        // 开发直调接口即使显式启用，也只允许真实登录身份为总监的账号模拟。
        // 必须在覆盖 ThreadLocal 前校验，否则销售员可传 asRepId=13 越权查询全公司。
        UserContext.UserInfo caller = UserContext.get();
        if (caller == null || !"SALES_DIRECTOR".equals(caller.role())) {
            throw new PermissionDeniedException("仅销售总监可使用开发测试接口");
        }
        if (asRepId == null) {
            UserContext.set(new UserContext.UserInfo(13L, "黄总", "SALES_DIRECTOR", 1L, null));
            return;
        }
        SalesRep rep = repRepository.findById(asRepId)
                .orElseThrow(() -> new IllegalArgumentException("测试身份 repId 不存在：" + asRepId));
        UserContext.set(new UserContext.UserInfo(
                rep.getId(), rep.getName(), rep.getRole(), rep.getRegionId(), rep.getId()));
    }

    // ==================== 工具一：SalesQueryTool（查明细） ====================

    @PostMapping("/query-orders")
    public String queryOrders(@RequestBody ToolRequest req) {
        simulateIdentity(req.asRepId());
        // limit 缺省 20（与 @P 描述的默认值一致）
        return salesQueryTool.queryOrders(
                req.startDate(), req.endDate(),
                req.regionName(), req.repName(),
                req.limit() == null ? 20 : req.limit());
    }

    // ==================== 工具二：SalesSummaryTool（算汇总） ====================

    @PostMapping("/sales-summary")
    public String salesSummary(@RequestBody ToolRequest req) {
        simulateIdentity(req.asRepId());
        return salesSummaryTool.getSalesSummary(
                req.startDate(), req.endDate(), req.regionName(), req.repName());
    }

    @PostMapping("/top-reps")
    public String topReps(@RequestBody ToolRequest req) {
        simulateIdentity(req.asRepId());
        // topN 缺省 5（与 @P 描述一致）
        return salesSummaryTool.getTopReps(
                req.startDate(), req.endDate(),
                req.regionName(), req.topN() == null ? 5 : req.topN());
    }

    @PostMapping("/region-ranking")
    public String regionRanking(@RequestBody ToolRequest req) {
        simulateIdentity(req.asRepId());
        return salesSummaryTool.getRegionRanking(req.startDate(), req.endDate());
    }

    @PostMapping("/top-products")
    public String topProducts(@RequestBody ToolRequest req) {
        simulateIdentity(req.asRepId());
        // topN 缺省 10（与 @P 描述一致；传负数测"最差 N 名"）
        return salesSummaryTool.getTopProducts(
                req.startDate(), req.endDate(),
                req.topN() == null ? 10 : req.topN());
    }

    // ==================== 工具三：SalesTrendTool（趋势对比） ====================

    @PostMapping("/month-over-month")
    public String monthOverMonth(@RequestBody ToolRequest req) {
        simulateIdentity(req.asRepId());
        // 对比周期四字段成对不传时，工具内自动推算等长周期
        return salesTrendTool.calcMonthOverMonth(
                req.currentStart(), req.currentEnd(),
                req.prevStart(), req.prevEnd(), req.regionName());
    }

    @PostMapping("/year-over-year")
    public String yearOverYear(@RequestBody ToolRequest req) {
        simulateIdentity(req.asRepId());
        return salesTrendTool.calcYearOverYear(
                req.startDate(), req.endDate(), req.regionName());
    }

    @PostMapping("/monthly-trend")
    public String monthlyTrend(@RequestBody ToolRequest req) {
        simulateIdentity(req.asRepId());
        // months 缺省 6（与 @P 描述一致）
        return salesTrendTool.getMonthlyTrend(
                req.months() == null ? 6 : req.months(), req.regionName());
    }

    // ==================== 工具四：ChartGeneratorTool（图表） ====================

    @PostMapping("/line-chart")
    public String lineChart(@RequestBody ToolRequest req) {
        simulateIdentity(req.asRepId());
        // months 缺省 6；dimension/title 仅柱状图与饼图需要
        return chartGeneratorTool.generateLineChart(
                req.months() == null ? 6 : req.months(),
                req.regionName(), req.title());
    }

    @PostMapping("/bar-chart")
    public String barChart(@RequestBody ToolRequest req) {
        simulateIdentity(req.asRepId());
        return chartGeneratorTool.generateBarChart(
                req.dimension(), req.startDate(), req.endDate(), req.title());
    }

    @PostMapping("/pie-chart")
    public String pieChart(@RequestBody ToolRequest req) {
        simulateIdentity(req.asRepId());
        return chartGeneratorTool.generatePieChart(
                req.dimension(), req.startDate(), req.endDate(), req.title());
    }

    // ==================== 工具五：AnomalyDetectionTool（异常检测） ====================

    @PostMapping("/detect-anomalies")
    public String detectAnomalies(@RequestBody(required = false) ToolRequest req) {
        // 无参数工具，请求体可省略；传 asRepId 可模拟主管/销售员视角验证范围收窄
        simulateIdentity(req == null ? null : req.asRepId());
        // 统计截止日取前一天，工具内自动计算
        return anomalyDetectionTool.detectAllAnomalies();
    }
}
