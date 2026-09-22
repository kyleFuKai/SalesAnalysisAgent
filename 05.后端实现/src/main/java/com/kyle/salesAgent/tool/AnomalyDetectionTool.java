package com.kyle.salesAgent.tool;

import com.kyle.salesAgent.dto.AnomalyDTO;
import com.kyle.salesAgent.entity.Product;
import com.kyle.salesAgent.entity.SalesRegion;
import com.kyle.salesAgent.entity.SalesRep;
import com.kyle.salesAgent.service.SalesQueryService;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 异常检测工具，5 个工具的最后一个。需求 4.1-E 的四条规则全在这：
 * 大区订单骤降（17/18）、产品连续零销售（19）、退单率异常（20）、销售员业绩骤降（18）。
 *
 * 一次调用跑完四项检测，结果按严重度排序输出。规则和阈值：
 *   大区骤降   近 14 天订单量对比前 28 天折算均值，降幅超 30% 报警（超 60% 高优）
 *   产品断销   曾成交的在售产品，距上次出单达到阈值天数（配置 7 天，需求 E19）
 *   退单率     近 30 天退单笔数占比达 15%（分母含取消单，需求 v1.1.2 笔数口径）
 *   业绩骤降   相邻两个 30 天窗口金额对比，降幅超 30% 报警（超 60% 高优）
 *
 * 每项都设了样本量门槛（大区两周少于 2 单、个人订单少于 3 单不判），
 * 避免拿一两个订单的波动制造假警报。
 *
 * 无参数设计：统计窗口固定截至前一天（当天数据未走完，不完整），用户不需要也没法传日期。
 * 全公司视角，只有总监该问"有没有异常"，主管/总监的收窄等接了 UserContext
 * 在 queryAnomaly* 几个方法里收敛。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/9/21 23:31
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AnomalyDetectionTool {

    private final SalesQueryService queryService;

    /** 断销判定天数，配置在 application.yml（需求 E19 是 7 天）。 */
    @Value("${sales-agent.tool.anomaly-threshold-days:5}")
    private int zeroSaleThresholdDays;

    /** 业绩/订单骤降的报警线，0.3 即降幅超 30% 报警（需求 E18）。 */
    @Value("${sales-agent.tool.trend-drop-threshold:0.3}")
    private double trendDropThreshold;

    /**
     * 异常检测的工具入口，四个检测顺序执行，汇总后按 HIGH > MEDIUM > LOW 排序输出。
     * 每条异常带四样：类型、严重度、对象、描述 + 处理建议，模型照着转述就行。
     * 一条没查到时也要说明"不代表已排除全部风险"，免得模型答成"一切正常"。
     */
    @Tool("自动检测销售数据中的所有异常，包括：大区订单量骤降、产品连续零销售、" +
            "销售员退单率异常、销售员业绩骤降。适用于：有没有异常、风险排查、预警检测等场景。" +
            "无需传入参数，统计截至昨天的完整周期；产品断销仅检测曾有成交的在售产品。")
    public String detectAllAnomalies() {

        log.info("工具调用-detectAllAnomalies: 开始全面异常检测");

        List<AnomalyDTO> anomalies = new ArrayList<>();
        // 一次调用固定一个截止日，避免跨午夜造成四项检测口径不同。
        LocalDate end = LocalDate.now().minusDays(1);

        try {
            // 配置是人改的，先验一遍。阈值不合法宁可整体不检测，也别拿错误规则跑出误导结论
            if (zeroSaleThresholdDays < 1 || !Double.isFinite(trendDropThreshold)
                    || trendDropThreshold <= 0 || trendDropThreshold >= 1) {
                throw new IllegalStateException("异常阈值不合法：天数须为正数，下降比例须在 0 和 1 之间");
            }
            anomalies.addAll(detectRegionDropAnomalies(end));
            anomalies.addAll(detectZeroSaleProducts(end));
            anomalies.addAll(detectHighRefundReps(end));
            anomalies.addAll(detectRepPerformanceDrop(end));
        } catch (Exception e) {
            log.error("异常检测出错", e);
            return "异常检测过程中出现问题，请稍后重试";
        }

        if (anomalies.isEmpty()) {
            return "截至 " + end + "，按当前规则未发现异常（样本不足及从未成交产品不参与相关检测），不代表已排除全部风险。";
        }

        // 按优先级排序：HIGH > MEDIUM > LOW
        anomalies.sort((a, b) -> {
            int order = severityOrder(a.severity()) - severityOrder(b.severity());
            return order;
        });

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("异常检测结果：共发现 %d 个异常\n\n", anomalies.size()));
        sb.append("统计截止日：").append(end).append("；产品断销仅检测曾成交的在售产品。\n\n");

        for (AnomalyDTO anomaly : anomalies) {
            String icon = switch (anomaly.severity()) {
                case "HIGH"   -> "🔴 高优先级";
                case "MEDIUM" -> "🟡 中优先级";
                default       -> "🔵 低优先级";
            };
            sb.append(String.format("%s｜%s\n", icon, anomaly.type()));
            sb.append(String.format("  对象：%s\n", anomaly.subject()));
            sb.append(String.format("  描述：%s\n", anomaly.description()));
            sb.append(String.format("  建议：%s\n\n", anomaly.suggestion()));
        }

        return sb.toString();
    }

    // ============================================================
    // 检测一：大区订单量骤降
    // ============================================================

    /**
     * 近 14 天订单量和前 28 天（折算成两周均值）比，降幅超阈值算骤降。
     * 用"折算均值"而不是直接拿 28 天总量比，是让两边处于同一时间长度。
     * 基准期两周均值不到 2 单的大区跳过——基数太小，随便一单就能触发 100% 降幅。
     * 降幅 30%~60% 记 MEDIUM，超 60% 记 HIGH。
     */
    private List<AnomalyDTO> detectRegionDropAnomalies(LocalDate end) {
        List<AnomalyDTO> result = new ArrayList<>();

        // 近 2 周 vs 过去 4 周每 2 周的平均
        LocalDate recentStart = end.minusDays(13);
        LocalDate recentEnd = end;
        LocalDate baseEnd = recentStart.minusDays(1);
        LocalDate baseStart = baseEnd.minusDays(27);

        for (SalesRegion region : queryService.queryAnomalyRegions()) {
            Long recentCount = queryService.queryOrderCount(region.getId(), recentStart, recentEnd);
            Long baseCount = queryService.queryOrderCount(region.getId(), baseStart, baseEnd);

            // 基准期 4 周折算成每 2 周平均
            double baseAvg = baseCount / 2.0;
            if (baseAvg < 2) continue; // 样本量太小，忽略

            double dropRate = (baseAvg - recentCount) / baseAvg;
            if (dropRate > trendDropThreshold) {
                String severity = dropRate > 0.6 ? "HIGH" : "MEDIUM";
                result.add(new AnomalyDTO(
                        "大区订单量骤降",
                        severity,
                        region.getName(),
                        String.format("近 2 周订单量 %d 笔，过去 4 周均值 %.1f 笔/两周，下降 %.0f%%",
                                recentCount, baseAvg, dropRate * 100),
                        "建议联系大区负责人确认原因，检查是否有系统问题或市场变化"
                ));
            }
        }
        return result;
    }

    // ============================================================
    // 检测二：产品连续零销售
    // ============================================================

    /**
     * 在售产品里，距上次出单达到阈值天数（配置 7 天）的算断销。
     * 距离越久越严重：14 天以上 HIGH、7 天以上 MEDIUM、不到 7 天 LOW（低优先级）。
     *
     * 两个刻意的取舍：
     *   从未成交过的产品直接跳过——没有"上次出单"就没法算"断销多少天"，
     *   也可能是新品，不能凭空下结论；
     *   最近成交日按产品一次性批量查（queryLastOrderDates），
     *   不然 50 个 SKU 就是 50 次单查。
     */
    private List<AnomalyDTO> detectZeroSaleProducts(LocalDate end) {
        List<AnomalyDTO> result = new ArrayList<>();
        Map<Long, LocalDate> lastSaleDates = queryService.queryLastOrderDates(end);

        for (Product product : queryService.queryAnomalyProducts()) {
            LocalDate lastSaleDate = lastSaleDates.get(product.getId());
            // 无成交不等于新品；缺少上架时间时不推断已断销多少天。
            if (lastSaleDate == null) continue;

            long daysWithoutSale = ChronoUnit.DAYS.between(lastSaleDate, end);
            if (daysWithoutSale >= zeroSaleThresholdDays) {
                String severity = daysWithoutSale >= 14 ? "HIGH"
                        : daysWithoutSale >= 7 ? "MEDIUM" : "LOW";
                result.add(new AnomalyDTO(
                        "产品连续零销售",
                        severity,
                        product.getName() + "（" + product.getSkuCode() + "）",
                        String.format("已连续 %d 天无销售订单，上次出单日期：%s",
                                daysWithoutSale, lastSaleDate),
                        "检查产品是否下架、库存是否充足、价格是否有竞争力"
                ));
            }
        }
        return result;
    }

    // ============================================================
    // 检测三：销售员退单率异常
    // ============================================================

    /**
     * 近 30 天退单率（退单笔数/总单数，分母含取消单）达到 15% 就报，
     * 正好卡在需求 4.1-E20 的绝对值线上；超 30% 记 HIGH。
     * 时段内下单少于 3 单的跳过，一单退一单就是 100%，没有统计意义。
     */
    private List<AnomalyDTO> detectHighRefundReps(LocalDate end) {
        List<AnomalyDTO> result = new ArrayList<>();
        LocalDate start = end.minusDays(29);

        List<Object[]> refundData = queryService.queryRefundRates(start, end);
        for (Object[] row : refundData) {
            Long repId = ((Number) row[0]).longValue();
            long refunded = ((Number) row[1]).longValue();
            long total = ((Number) row[2]).longValue();

            if (total < 3) continue; // 样本量太小

            double refundRate = (double) refunded / total;
            if (refundRate >= 0.15) {
                String repName = queryService.getRepName(repId);
                String severity = refundRate > 0.3 ? "HIGH" : "MEDIUM";
                result.add(new AnomalyDTO(
                        "销售员退单率异常",
                        severity,
                        repName,
                        String.format("近 30 天下单订单的退单率 %.1f%%（%d/%d 单），达到或超过预警阈值 15%%（分母含取消订单）",
                                refundRate * 100, refunded, total),
                        "建议与该销售员沟通了解原因，排查是否存在虚报订单或客户不满意的情况"
                ));
            }
        }
        return result;
    }

    // ============================================================
    // 检测四：销售员业绩骤降
    // ============================================================

    /**
     * 相邻两个完整 30 天窗口的完成金额对比，降幅超 30% 报警、超 60% HIGH
     *（需求 E18，和配置里的 trend-drop-threshold 对应）。
     * 上期金额为零的直接跳过：没有基准就算不出降幅，硬算要么除零要么
     * 得出"下降 100%"这种吓人但没意义的结论。
     *
     * 只查 queryAnomalyReps（普通销售员）：主管不背个人业绩，
     * 总监更不是，把他们放进来只会产出噪音。
     */
    private List<AnomalyDTO> detectRepPerformanceDrop(LocalDate end) {
        List<AnomalyDTO> result = new ArrayList<>();
        // 两个相邻、不重叠的完整 30 天窗口，BETWEEN 两端均包含。
        LocalDate curStart = end.minusDays(29);
        LocalDate prevEnd = curStart.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(29);

        for (SalesRep rep : queryService.queryAnomalyReps()) {
            BigDecimal previous = queryService.queryRepTotalAmount(rep.getId(), prevStart, prevEnd);
            // 上期为零没有可比较基准，避免除零或凭空生成下降比例。
            if (previous.signum() <= 0) continue;
            BigDecimal current = queryService.queryRepTotalAmount(rep.getId(), curStart, end);
            BigDecimal decrease = previous.subtract(current);
            if (decrease.compareTo(previous.multiply(BigDecimal.valueOf(trendDropThreshold))) <= 0) continue;
            BigDecimal percent = decrease.multiply(BigDecimal.valueOf(100))
                    .divide(previous, 2, RoundingMode.HALF_UP);
            String severity = decrease.compareTo(previous.multiply(new BigDecimal("0.6"))) > 0
                    ? "HIGH" : "MEDIUM";
            result.add(new AnomalyDTO("销售员业绩骤降", severity, rep.getName(),
                    String.format("近 30 天已完成订单金额 %.2f 元，前 30 天 %.2f 元，下降 %.2f%%",
                            current, previous, percent),
                    "建议核对客户跟进、休假及订单归属情况，确认业绩下降原因"));
        }
        return result;
    }

    /**
     * 严重度转排序权重：HIGH 最前。LOW 和未知值都排最后。
     */
    private int severityOrder(String severity) {
        return switch (severity) {
            case "HIGH"   -> 0;
            case "MEDIUM" -> 1;
            default       -> 2;
        };
    }

}
