package com.kyle.salesAgent.tool;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;
/**
 * 工具入参校验器：模型传入参数的格式/范围校验（日期、Top N、图表维度等）。
 * <p>约定：校验失败抛 {@link IllegalArgumentException}，消息写成模型能读懂的纠正提示
 * （如"无效的日期格式，请使用 yyyy-MM-dd"）——工具层 catch 后把消息原样透传给模型，
 * 模型据此自行修正重试，而不是收到一句模糊的"出问题了"。
 * <p>大区名称不在这里校验：数据库是唯一事实来源（getRegionIdByName 查不到即提示），
 * 白名单硬编码会和库表形成两处维护点，漏改会把合法大区拒掉。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/2 09:17
 */
@Component
public class ToolInputValidator {

    private static final Set<String> VALID_CHART_TYPES =
            Set.of("line", "bar", "pie");

    private static final Set<String> VALID_DIMENSIONS =
            Set.of("region", "rep", "category");

    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /**
     * 校验并解析日期：格式必须 yyyy-MM-dd，且必须是真实存在的日历日期
     * （"2026-13-45" 格式对但日历上不存在，同样拒绝）。直接返回解析好的
     * LocalDate，调用方不用二次 parse。
     */
    public LocalDate validateDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank() || !DATE_PATTERN.matcher(dateStr).matches()) {
            throw new IllegalArgumentException("无效的日期格式，请使用 yyyy-MM-dd");
        }
        try {
            return LocalDate.parse(dateStr);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("无效的日期：" + dateStr);
        }
    }

    /**
     * Top N 收敛到 1~20：下限防 0/负数导致空结果被误答成"暂无数据"，
     * 上限防撑爆模型上下文。
     * <p>注意：只用于"正数=前 N 名"的参数；"负数=最差 N 名"的参数（getTopProducts）
     * 不能走这里——clamp 会吞掉负号，悄悄把"查最差"变成"查最佳"。
     */
    public int validateTopN(int topN) {
        return Math.min(Math.max(topN, 1), 20);
    }

    /**
     * 日期区间校验：开始不能晚于结束——区间为空时查询会静默返回空集，
     * 被模型当成"该时段没有数据"误答（安全自检第 3 项的缺口）。
     */
    public void validateDateRange(LocalDate start, LocalDate end) {
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期，请核对查询范围");
        }
    }

    /** 图表类型白名单（ChartGeneratorTool 预留）。 */
    public String validateChartType(String chartType) {
        if (!VALID_CHART_TYPES.contains(chartType)) {
            throw new IllegalArgumentException("无效的图表类型：" + chartType + "，有效值为：line/bar/pie");
        }
        return chartType;
    }

    /** 图表维度白名单（ChartGeneratorTool 预留）。 */
    public String validateDimension(String dimension) {
        if (!VALID_DIMENSIONS.contains(dimension)) {
            throw new IllegalArgumentException("无效的维度：" + dimension + "，有效值为：region/rep/category");
        }
        return dimension;
    }
}
