package com.kyle.salesAgent.tool;

import com.kyle.salesAgent.dto.OrderSummaryDTO;
import com.kyle.salesAgent.entity.SalesOrder;
import com.kyle.salesAgent.service.SalesQueryService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 销售订单查询工具 —— LangChain4j 的 5 个工具之一
 * <p>对应架构 3.2.4：工具层把 Service 方法包装成"AI 可调用的工具"。
 * 由调用方根据当前用户角色传入（销售员场景应传 repName，主管传 regionName，
 * 总监可不传）。当前 UserContext 未接入（架构待办），权限注入由工具调用方负责。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/9/18
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SalesQueryTool {

    private final SalesQueryService queryService;

    /**
     * AI 工具调用入口：把模型自然语言翻译成的"查订单"需求落到具体查询上。
     * <p>返回字符串直接喂给模型（不是 JSON），所以格式对 AI 友好优先于对程序友好——
     * 详见 {@link #formatOrders} 的格式约定。
     *
     * @param startDate  查询起始日期（yyyy-MM-dd）
     * @param endDate    查询结束日期（yyyy-MM-dd）
     * @param regionName 大区名过滤（null/空表示全公司）
     * @param repName    销售员姓名过滤（null/空表示不限个人）
     * @param limit      返回条数上限（默认 20，函数体内 clamp 到 50）
     * @return 人类可读的订单摘要字符串（含小计），供模型直接拼装回答
     */
    @Tool("查询原始销售订单数据。适用于：查具体订单、看某时段订单列表、统计某时段订单总数。" +
         "【不适合】排名、增长率、图表生成、异常检测等场景，那些请使用对应的专用工具。")
    public String queryOrders(
            @P("查询开始日期，格式 yyyy-MM-dd，如 2024-11-01") String startDate,
            @P("查询结束日期，格式 yyyy-MM-dd，如 2024-11-30") String endDate,
            @P("大区名称，如：华东区、华南区、华北区、西南区。传 null 或空字符串表示查全公司") String regionName,
            @P("销售员姓名，如需按特定销售员筛选则传入，如：张磊。否则传 null 或空字符串") String repName,
            @P("最多返回条数，默认 20，最大 50。避免返回数据过多") int limit) {

        log.info("工具调用-queryOrders: start={}, end={}, region={}, repName={}, limit={}",
                startDate, endDate, regionName, repName, limit);

        try {
            // 参数解析：AI 给的是字符串，底层要 LocalDate；解析失败时给明确提示而非堆栈
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);

            // 名称 → ID 翻译（用户/模型问的是"张磊"，DB 过滤字段是 repId）
            // 第一道校验：名称不存在 → NOT_FOUND 语义（呼应架构 4.4），不是"无数据"
            Long regionId = null;
            if (regionName != null && !regionName.isBlank()) {
                regionId = queryService.getRegionIdByName(regionName);
                if (regionId == null) {
                    return "未找到大区：" + regionName + "，请确认大区名称是否正确（华东区/华南区/华北区/西南区）";
                }
            }

            // 同上：销售员名称 → ID；找不到时返回错误提示而不是静默返回空（NOT_FOUND 语义）
            Long repId = null;
            if (repName != null && !repName.isBlank()) {
                repId = queryService.getRepIdByName(repName);
                if (repId == null) {
                    return "未找到销售员：" + repName + "，请确认姓名是否正确";
                }
            }

            // 委托给 Service：本工具不直接碰 Repository
            List<SalesOrder> orders = queryService.queryOrders(repId, regionId, start, end);

            // 空结果给明确空消息（"暂无"），不要让模型自行猜测是不是权限问题
            if (orders.isEmpty()) {
                return String.format("在 %s 至 %s 期间，%s暂无订单数据",
                        startDate, endDate,
                        regionName != null ? regionName + " " : "");
            }

            // 截断上限：模型传超过 50 时硬截到 50（防一次性返回过大撑爆上下文窗口）
            int actualLimit = Math.min(limit, 50);
            List<SalesOrder> limited = orders.size() > actualLimit
                    ? orders.subList(0, actualLimit) : orders;

            // 装配格式化字符串（含原始条数 vs 显示条数提示 + 完成订单小计）
            return formatOrders(limited, orders.size(), startDate, endDate, regionName);

        } catch (DateTimeParseException e) {
            // 日期解析失败：明确告诉模型/用户怎么改，而不是甩异常堆栈
            return "日期格式错误，请使用 yyyy-MM-dd 格式，如：2024-11-01";
        } catch (Exception e) {
            // 兜底异常：详细堆栈进日志，给用户/模型的回复要友好
            log.error("查询订单失败", e);
            return "查询订单数据时出现问题，请稍后重试";
        }
    }

    /**
     * 把订单列表格式化成模型能直接读懂的字符串
     * <p>格式约定（AI 阅读友好）：
     * <ul>
     *   <li>开头：日期范围 + 总条数 + "以下显示前 N 条"提示（让模型知道截断了）</li>
     *   <li>每条订单：单行"-"开头，竖线分隔字段（视觉清晰，模型易抽取）</li>
     *   <li>结尾：小计——只算 COMPLETED 的金额合计（与口径一致）</li>
     * </ul>
     * <p>金额故意用 ¥ + 千分位 + 整数显示（去掉 .00），是因为模型不需要小数位
     * （统计里 0.01 没意义），且减少上下文 token 消耗。
     *
     * @param orders     已截断的本次返回订单列表
     * @param total      原始未截断的总数（用于提示"以下显示前 N 条"）
     * @param startDate  起始日期（仅用于回显，不参与计算）
     * @param endDate    结束日期（仅用于回显）
     * @param regionName 大区名（仅用于回显；null 时不显示"华东区"）
     * @return 拼装好的多行字符串，可直接喂给模型
     */
    private String formatOrders(List<SalesOrder> orders, int total,
                                  String startDate, String endDate, String regionName) {
        // 用 StringBuilder 拼装多行：循环里 += String 每次都新建字符串，性能差
        StringBuilder sb = new StringBuilder();
        // 开头：时间范围 + 大区名（null 时不显示"+ 华东区"）
        sb.append(String.format("订单查询结果（%s 至 %s%s）：\n",
                startDate, endDate,
                regionName != null ? "，" + regionName : ""));
        sb.append(String.format("共找到 %d 条订单", total));
        // 截断提示：实际返回 < 总数时告知模型，避免它误以为"只有这么多"
        if (orders.size() < total) {
            sb.append(String.format("，以下显示前 %d 条", orders.size()));
        }
        sb.append("\n\n");

        // 逐条订单：单行格式 + 状态中文化（让模型直接拼"已完成的订单"给用户看）
        for (SalesOrder order : orders) {
            String repName = queryService.getRepName(order.getRepId());
            sb.append(String.format("- 订单号：%s | 日期：%s | 销售员：%s | 客户：%s | 金额：¥%,.0f | 状态：%s\n",
                    order.getOrderNo(),
                    order.getOrderDate(),
                    repName,
                    order.getCustomerName(),
                    order.getAmount(),
                    translateStatus(order.getStatus())));
        }

        // 小计：只算已完成的金额（毛额口径）；用 double 计算只为了 String.format 快，
        // 展示精度到元无所谓，业务精度由 Service 层保证
        double completedTotal = orders.stream()
                .filter(o -> "COMPLETED".equals(o.getStatus()))
                .mapToDouble(o -> o.getAmount().doubleValue())
                .sum();
        long completedCount = orders.stream()
                .filter(o -> "COMPLETED".equals(o.getStatus())).count();

        sb.append(String.format("\n小计：完成订单 %d 笔，金额合计 ¥%,.0f", completedCount, completedTotal));
        return sb.toString();
    }

    /**
     * 订单状态码 → 中文显示
     * <p>与 entity {@code SalesOrder.status} 的取值（v1.1 文档第 2 节）一致，
     * 同样支持未来新增状态码的 default 分支（未知码原样返回，防漏映射）
     *
     * @param status 状态码（如 "COMPLETED"）
     * @return 中文显示（如 "已完成"）；未知码原样返回
     */
    private String translateStatus(String status) {
        // switch 表达式（Java 14+）：每个 case 直接返回值，比传统 switch 更紧凑
        return switch (status) {
            case "COMPLETED" -> "已完成";
            case "REFUNDED"  -> "已退款";
            case "CANCELLED" -> "已取消";
            // 防御：未来 schema 加新状态码时不会因 switch 漏 case 而 NPE/返回 null
            default          -> status;
        };
    }
}