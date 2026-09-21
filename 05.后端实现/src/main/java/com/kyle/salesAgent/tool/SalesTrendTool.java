package com.kyle.salesAgent.tool;

import com.kyle.salesAgent.dto.MonthlyTrendDTO;
import com.kyle.salesAgent.service.SalesQueryService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 销售趋势工具，管"比以前怎么样"这类问题：环比、同比、月度趋势、旺季判定
 * （需求 4.1-C 的用例都在这）。单期总额和排名在 SalesSummaryTool，
 * 模型按问法选工具，靠的是下面各方法的 @Tool 描述，所以那几行别乱改。
 *
 * 口径跟 Service 一致：毛额，只算 COMPLETED 订单。
 * 旺季判定按需求 2 节的规则：月销售额达到月均 1.2 倍。
 *
 * 工具层不做权限校验，大区范围由调用方传 regionName 决定。
 * 大区名查不到时报"未找到大区"，不会悄悄查全公司。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/9/20 22:43
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SalesTrendTool {

    private final SalesQueryService queryService;

    /**
     * 环比：当期和上一期比，对应需求用例 10"本月比上月增长了多少"。
     *
     * 对比周期可以不传，系统自动取等长的上一周期：当期 9-01~9-30 共 30 天，
     * 对比期就是 8-02~8-31。模型不用自己算日期。
     *
     * 返回的内容大概长这样：
     *   环比分析（华东区）：
     *   当前周期（2026-09-01 至 2026-09-30）：¥186,000
     *   对比周期（2026-08-02 至 2026-08-31）：¥155,880
     *   环比变化：↑ 增长 19.3%（增加 ¥30,120）
     */
    @Tool("计算销售环比增长率（当期与上一期对比）。适用于：本月比上月、本季比上季、" +
            "环比增长/下降多少、最近两期对比等场景。")
    public String calcMonthOverMonth(
            @P("当前周期开始日期，格式 yyyy-MM-dd") String currentStart,
            @P("当前周期结束日期，格式 yyyy-MM-dd") String currentEnd,
            @P("对比周期开始日期，格式 yyyy-MM-dd。传 null 则自动计算上一个等长周期") String prevStart,
            @P("对比周期结束日期，格式 yyyy-MM-dd。传 null 则自动计算上一个等长周期") String prevEnd,
            @P("大区名称，如：华东区。传 null 表示全公司") String regionName) {

        // 留日志，出了问题能查到模型当时传了什么
        log.info("工具调用-calcMonthOverMonth: current={}/{}, prev={}/{}, region={}",
                currentStart, currentEnd, prevStart, prevEnd, regionName);

        try {
            LocalDate cStart = LocalDate.parse(currentStart);
            LocalDate cEnd = LocalDate.parse(currentEnd);

            // 对比周期不传就自动推：pEnd 是当期前一天，pStart 再往前数同样的天数。
            // between 算的是差值，+1 才是闭区间天数。
            // 例：当期 9-01~9-30，days=30 → pEnd=8-31，pStart=8-31-29=8-02
            LocalDate pStart, pEnd;
            if (prevStart == null || prevStart.isBlank()) {
                long days = java.time.temporal.ChronoUnit.DAYS.between(cStart, cEnd) + 1;
                pEnd = cStart.minusDays(1);
                pStart = pEnd.minusDays(days - 1);
            } else {
                // 只传 start 不传 end 会 NPE，这里直接告诉模型要成对传
                if (prevEnd == null || prevEnd.isBlank()) {
                    return "对比周期需要同时传 prevStart 和 prevEnd（或都不传由系统自动推算）";
                }
                pStart = LocalDate.parse(prevStart);
                pEnd = LocalDate.parse(prevEnd);
            }

            // 大区名查不到时返回提示，让模型纠正，不能当成"全公司"处理
            Long regionId = resolveRegionId(regionName);
            if (regionId == null && regionName != null && !regionName.isBlank()) {
                return "未找到大区：" + regionName;
            }

            // 两期口径一致（同一个 JPQL），比出来才有意义
            BigDecimal currentAmount = queryService.queryTotalAmount(regionId, cStart, cEnd);
            BigDecimal prevAmount = queryService.queryTotalAmount(regionId, pStart, pEnd);
            // 除零的情况 Service 里处理了，上期没数据时返回 null
            BigDecimal growthRate = queryService.calcGrowthRate(currentAmount, prevAmount);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("环比分析（%s）：\n\n",
                    regionName != null && !regionName.isBlank() ? regionName : "全公司"));
            // 两期金额分行列，模型拼回答时好引用
            sb.append(String.format("当前周期（%s 至 %s）：¥%,.0f\n", cStart, cEnd, currentAmount));
            sb.append(String.format("对比周期（%s 至 %s）：¥%,.0f\n", pStart, pEnd, prevAmount));

            // 上期没数据就算不出增长率，照实说，不编数字
            if (growthRate == null) {
                sb.append("对比周期无数据，无法计算增长率");
            } else {
                // 百分比取 abs，正负已经用箭头表达了，不然会出现"↑ -12%"这种输出；
                // 后面带上差额，光看百分比不知道业务体量
                String trend = growthRate.compareTo(BigDecimal.ZERO) >= 0 ? "↑ 增长" : "↓ 下降";
                sb.append(String.format("环比变化：%s %.1f%%（%s ¥%,.0f）",
                        trend,
                        growthRate.abs(),
                        growthRate.compareTo(BigDecimal.ZERO) >= 0 ? "增加" : "减少",
                        currentAmount.subtract(prevAmount).abs()));
            }
            return sb.toString();

        } catch (DateTimeParseException e) {
            return "日期格式错误，请使用 yyyy-MM-dd 格式";
        } catch (Exception e) {
            log.error("计算环比失败", e);
            return "计算环比数据时出现问题，请稍后重试";
        }
    }

    /**
     * 同比：和去年同期比，对应需求用例 11"今年 Q4 和去年 Q4 相比怎样"。
     * 去年日期不用传，直接 minusYears(1)，闰年 2-29 会自动落到 2-28。
     *
     * 靠的是去年的历史数据（需求 9-1 要求至少 13 个月回溯，测试数据 B08 段埋了去年 Q4）。
     * 去年没数据就直说算不了。
     *
     * 输出和环比类似，只是没有差额那一截——年度差额动辄几十万，放着反而干扰阅读。
     */
    @Tool("计算销售同比增长率（与去年同期对比）。适用于：今年和去年同期比、" +
            "同比增长率、年度对比、YoY 等场景。")
    public String calcYearOverYear(
            @P("查询开始日期，格式 yyyy-MM-dd（今年的日期）") String startDate,
            @P("查询结束日期，格式 yyyy-MM-dd（今年的日期）") String endDate,
            @P("大区名称，如：华东区。传 null 表示全公司") String regionName) {

        log.info("工具调用-calcYearOverYear: start={}, end={}, region={}", startDate, endDate, regionName);

        try {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            LocalDate prevStart = start.minusYears(1);
            LocalDate prevEnd = end.minusYears(1);

            Long regionId = resolveRegionId(regionName);
            if (regionId == null && regionName != null && !regionName.isBlank()) {
                return "未找到大区：" + regionName;
            }

            BigDecimal thisYear = queryService.queryTotalAmount(regionId, start, end);
            BigDecimal lastYear = queryService.queryTotalAmount(regionId, prevStart, prevEnd);
            BigDecimal growthRate = queryService.calcGrowthRate(thisYear, lastYear);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("同比分析（%s）：\n\n",
                    regionName != null && !regionName.isBlank() ? regionName : "全公司"));
            // 今年在前去年在后，模型照着念不会把顺序说反
            sb.append(String.format("今年（%s 至 %s）：¥%,.0f\n", start, end, thisYear));
            sb.append(String.format("去年（%s 至 %s）：¥%,.0f\n", prevStart, prevEnd, lastYear));

            if (growthRate == null) {
                sb.append("去年同期无数据，无法计算同比增长率");
            } else {
                String trend = growthRate.compareTo(BigDecimal.ZERO) >= 0 ? "↑ 同比增长" : "↓ 同比下降";
                sb.append(String.format("同比变化：%s %.1f%%", trend, growthRate.abs()));
            }
            return sb.toString();

        } catch (DateTimeParseException e) {
            return "日期格式错误，请使用 yyyy-MM-dd 格式";
        } catch (Exception e) {
            log.error("计算同比失败", e);
            return "计算同比数据时出现问题，请稍后重试";
        }
    }

    /**
     * 月度趋势：近 N 个月逐月列出来，带逐月涨跌和旺季判定。
     * 对应用例 12（趋势）、13（旺季）、14（折线图的数据就从这来）。
     *
     * 旺季的规则来自需求 2 节：月销售额达到月均 1.2 倍。判定范围就是本次查询
     * 的窗口，输出里会把月均和阈值一起给出来，用户追问"为什么算旺季"时有依据。
     *
     * 有个已知边界：SQL 按 GROUP BY month 聚合，某月一单没有就没有那一行，
     * 月份会缺。现在数据量下碰不到。
     *
     * 返回内容大概长这样：
     *   月度销售趋势（近 6 个月，华东区）：
     *   2026-04：¥186,000  订单数：15
     *   2026-05：¥155,880  订单数：12 (↓16.2%)
     *   ...
     *   整体趋势：下降 12.3%
     *   旺季判定（基于近 6 个月窗口）：月均 ¥152,000，旺季阈值 ≥1.2×月均 = ¥182,400；旺季月份：2026-04
     */
    @Tool("获取近 N 个月的月度销售趋势数据。适用于：近几个月的趋势、月度变化情况、" +
            "销售走势、趋势是上升还是下降、哪个月是旺季等场景。如果用户要画折线图，先调用此工具获取数据。")
    public String getMonthlyTrend(
            @P("查看近多少个月，如 6 表示近 6 个月，最大 24") int months,
            @P("大区名称，如：华东区。传 null 表示全公司") String regionName) {

        log.info("工具调用-getMonthlyTrend: months={}, region={}", months, regionName);

        try {
            // 上限 24 个月，对应需求 9-1 的"建议 24 个完整自然月"
            int m = Math.min(Math.max(months, 1), 24);

            // 大区名查不到要报错，不能默默查全公司（之前漏过这个校验）
            Long regionId = resolveRegionId(regionName);
            if (regionId == null && regionName != null && !regionName.isBlank()) {
                return "未找到大区：" + regionName;
            }

            List<MonthlyTrendDTO> trend = queryService.queryMonthlyTrend(regionId, m);
            if (trend.isEmpty()) {
                return "暂无趋势数据";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("月度销售趋势（近 %d 个月%s）：\n\n",
                    m, regionName != null && !regionName.isBlank() ? "，" + regionName : "，全公司"));

            // 逐月列出来。首行没有环比（它没有上月可比），从第二行开始算一个
            // 挂在后面，模型直接引用就行，不用自己除
            for (int i = 0; i < trend.size(); i++) {
                MonthlyTrendDTO dto = trend.get(i);
                String changeStr = "";
                if (i > 0) {
                    BigDecimal prev = trend.get(i - 1).totalAmount();
                    BigDecimal rate = queryService.calcGrowthRate(dto.totalAmount(), prev);
                    if (rate != null) {
                        changeStr = rate.compareTo(BigDecimal.ZERO) >= 0
                                ? String.format(" (↑%.1f%%)", rate)
                                : String.format(" (↓%.1f%%)", rate.abs());
                    }
                }
                sb.append(String.format("%s：¥%,.0f  订单数：%d%s\n",
                        dto.month(), dto.totalAmount(), dto.orderCount(), changeStr));
            }

            // 整体趋势就是首月末月对比，给个方向感。中间有波折时以逐月箭头为准
            if (trend.size() >= 2) {
                BigDecimal first = trend.get(0).totalAmount();
                BigDecimal last = trend.get(trend.size() - 1).totalAmount();
                BigDecimal overallRate = queryService.calcGrowthRate(last, first);
                if (overallRate != null) {
                    sb.append(String.format("\n整体趋势：%s %.1f%%",
                            overallRate.compareTo(BigDecimal.ZERO) >= 0 ? "上升" : "下降",
                            overallRate.abs()));
                }
            }

            // 旺季：先算月均（中间多留几位小数，减少舍入误差），再筛达标的月份。
            // 月均和阈值都写进输出，用户问"怎么算的"有得答
            BigDecimal avg = trend.stream()
                    .map(MonthlyTrendDTO::totalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(trend.size()), 4, RoundingMode.HALF_UP);
            BigDecimal threshold = avg.multiply(BigDecimal.valueOf(1.2));
            String peakMonths = trend.stream()
                    .filter(t -> t.totalAmount().compareTo(threshold) >= 0)
                    .map(MonthlyTrendDTO::month)
                    .collect(Collectors.joining("、"));

            sb.append(String.format("\n旺季判定（基于近 %d 个月窗口）：月均 ¥%,.0f，旺季阈值 ≥1.2×月均 = ¥%,.0f；",
                    m, avg, threshold));
            // 没有达标月份也要说清楚，不然模型容易把销售额最高的那个月硬当成旺季
            if (peakMonths.isEmpty()) {
                sb.append("本窗口内无达标月份（各月销售额均未达到月均 1.2 倍）");
            } else {
                sb.append(String.format("旺季月份：%s", peakMonths));
            }
            return sb.toString();

        } catch (Exception e) {
            // 这个方法没有字符串日期要解析，走到这的基本是 DB 或聚合的问题
            log.error("获取月度趋势失败", e);
            return "获取趋势数据时出现问题，请稍后重试";
        }
    }

    /**
     * 大区名换大区 ID，三个方法都用这一份。
     * 返回 null 有两种含义：没传大区名（不限大区）、名字查不到。
     * 所以调用处必须再拿 regionName 判断一下是不是第二种，否则就悄悄查全公司了。
     */
    private Long resolveRegionId(String regionName) {
        if (regionName == null || regionName.isBlank()) return null;
        return queryService.getRegionIdByName(regionName);
    }
}
