package com.kyle.salesAgent.controller;

import com.kyle.salesAgent.tool.SalesQueryTool;
import com.kyle.salesAgent.tool.SalesSummaryTool;
import org.springframework.web.bind.annotation.*;

/**
 * 工具直调测试 Controller —— 绕过 AI 模型直接验证各工具逻辑
 * <p>所有端点共用统一请求体 {@link ToolRequest}：字段覆盖现有全部工具的参数，
 * 各端点按需取用，用不到的不传即可（Integer 字段缺省为 null，端点内给默认值）。
 * <p>仅用于开发期 curl/Postman 手测，正式接口走 Agent 链路，上线前本类可整体删除。
 */
@RestController
@RequestMapping("/test/tool")
public class ToolTestController {

    private final SalesQueryTool salesQueryTool;
    private final SalesSummaryTool salesSummaryTool;

    public ToolTestController(SalesQueryTool salesQueryTool, SalesSummaryTool salesSummaryTool) {
        this.salesQueryTool = salesQueryTool;
        this.salesSummaryTool = salesSummaryTool;
    }

    /** 统一测试请求体：一个 record 服务全部端点，新增工具端点无需再加 record */
    record ToolRequest(String startDate, String endDate,
                       String regionName, String repName,
                       Integer topN, Integer limit) {}

    // ==================== 工具一：SalesQueryTool ====================

    @PostMapping("/query-orders")
    public String queryOrders(@RequestBody ToolRequest req) {
        // limit 缺省 20（与 @P 描述的默认值一致）
        return salesQueryTool.queryOrders(
                req.startDate(), req.endDate(),
                req.regionName(), req.repName(),
                req.limit() == null ? 20 : req.limit());
    }

    // ==================== 工具二：SalesSummaryTool ====================

    @PostMapping("/sales-summary")
    public String salesSummary(@RequestBody ToolRequest req) {
        return salesSummaryTool.getSalesSummary(
                req.startDate(), req.endDate(), req.regionName(), req.repName());
    }

    @PostMapping("/top-reps")
    public String topReps(@RequestBody ToolRequest req) {
        // topN 缺省 5（与 @P 描述一致）
        return salesSummaryTool.getTopReps(
                req.startDate(), req.endDate(),
                req.regionName(), req.topN() == null ? 5 : req.topN());
    }

    @PostMapping("/region-ranking")
    public String regionRanking(@RequestBody ToolRequest req) {
        return salesSummaryTool.getRegionRanking(req.startDate(), req.endDate());
    }

    @PostMapping("/top-products")
    public String topProducts(@RequestBody ToolRequest req) {
        // topN 缺省 10（与 @P 描述一致；传负数测"最差 N 名"）
        return salesSummaryTool.getTopProducts(
                req.startDate(), req.endDate(),
                req.topN() == null ? 10 : req.topN());
    }
}