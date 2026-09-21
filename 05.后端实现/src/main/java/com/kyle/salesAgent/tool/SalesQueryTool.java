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
 * 订单查询工具，答"有哪些订单/记录"这类问题（需求 4.1-A）。
 *
 * 工具层不做权限校验，范围由调用方按角色传参：销售员传 repName，
 * 主管传 regionName，总监可以都不传。UserContext 还没接（架构待办），
 * 目前权限全靠调用方自觉。
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
     * 查订单的工具入口。返回的是拼好的文字（不是 JSON），模型会直接拿来组织回答，
     * 所以格式怎么拼很重要，见 formatOrders。
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
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);

            // 用户问的是"张磊""华东区"，数据库过滤用的是 ID，这里做一次翻译。
            // 查不到就告诉模型名字不对，不能当作"没有数据"往下走
            Long regionId = null;
            if (regionName != null && !regionName.isBlank()) {
                regionId = queryService.getRegionIdByName(regionName);
                if (regionId == null) {
                    return "未找到大区：" + regionName + "，请确认大区名称是否正确（华东区/华南区/华北区/西南区）";
                }
            }

            Long repId = null;
            if (repName != null && !repName.isBlank()) {
                repId = queryService.getRepIdByName(repName);
                if (repId == null) {
                    return "未找到销售员：" + repName + "，请确认姓名是否正确";
                }
            }

            // 真正的查询在 Service，工具不碰 Repository
            List<SalesOrder> orders = queryService.queryOrders(repId, regionId, start, end);

            if (orders.isEmpty()) {
                return String.format("在 %s 至 %s 期间，%s暂无订单数据",
                        startDate, endDate,
                        regionName != null ? regionName + " " : "");
            }

            // 上限 50 条，太多了会把模型上下文撑爆
            int actualLimit = Math.min(limit, 50);
            List<SalesOrder> limited = orders.size() > actualLimit
                    ? orders.subList(0, actualLimit) : orders;

            return formatOrders(limited, orders.size(), startDate, endDate, regionName);

        } catch (DateTimeParseException e) {
            // 告诉模型正确格式，它自己会修正重试
            return "日期格式错误，请使用 yyyy-MM-dd 格式，如：2024-11-01";
        } catch (Exception e) {
            log.error("查询订单失败", e);
            return "查询订单数据时出现问题，请稍后重试";
        }
    }

    /**
     * 把订单列表拼成给模型看的文字。三段结构：开头报总数（截断了要说"以下显示前 N 条"），
     * 中间每条订单一行，结尾给已完成订单的小计。
     *
     * 金额用 ¥ 加千分位、不要小数位：模型引用起来干净，还能省 token。
     */
    private String formatOrders(List<SalesOrder> orders, int total,
                                  String startDate, String endDate, String regionName) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("订单查询结果（%s 至 %s%s）：\n",
                startDate, endDate,
                regionName != null ? "，" + regionName : ""));
        sb.append(String.format("共找到 %d 条订单", total));
        if (orders.size() < total) {
            // 截断过就说一声，不然模型以为总共就这几条
            sb.append(String.format("，以下显示前 %d 条", orders.size()));
        }
        sb.append("\n\n");

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

        // 小计只算已完成的，和销售额口径一致。这里用 double 是图 String.format 方便，
        // 精确计算在 Service 层
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
     * 状态码转中文，未知的状态原样返回，不会因为漏 case 报错。
     */
    private String translateStatus(String status) {
        return switch (status) {
            case "COMPLETED" -> "已完成";
            case "REFUNDED"  -> "已退款";
            case "CANCELLED" -> "已取消";
            default          -> status;
        };
    }
}
