package com.kyle.salesAgent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kyle.salesAgent.dto.MonthlyTrendDTO;
import com.kyle.salesAgent.dto.ProductSalesDTO;
import com.kyle.salesAgent.dto.RepSalesDTO;
import com.kyle.salesAgent.dto.RegionSalesDTO;
import com.kyle.salesAgent.service.SalesQueryService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图表工具，对应需求 4.1-D 的三个图表用例：折线图（14）、饼图（15）、柱状图（16）。
 *
 * 工作方式：不产图片，产出一份 ECharts option 的 JSON，前面加个 CHART_JSON:
 * 前缀。链路是 工具 → 模型原样带回来 → 前端看到前缀，剥掉 JSON 交给 ECharts 画。
 * 这条链有个前提：模型得原样转交，它要是把 JSON 包进 markdown 代码块或者截断了，
 * 前端就解析不了。所以 System Prompt 里要写死"CHART_JSON 开头的内容原样输出"，
 * 这条已经记在架构文档待办里了。
 *
 * 数据都复用 Service 层现成的查询，和文字版工具同一个来源，数字不会两边对不上。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/9/21 00:03
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChartGeneratorTool {

    private final SalesQueryService queryService;
    // 用 Spring 容器里那份 ObjectMapper，别自己 new——以后全局改序列化配置时这里跟着走
    private final ObjectMapper objectMapper;

    /**
     * 趋势折线图，对应需求用例 14"画一张近半年的销售趋势折线图"。
     * 数据就是 getMonthlyTrend 那份，只是包装成 ECharts 的格式。
     *
     * 返回大概长这样（前端剥掉前缀用）：
     *   CHART_JSON:{"title":{"text":"华东区近6个月销售趋势"},
     *     "xAxis":{"type":"category","data":["2026-04",...]},
     *     "series":[{"type":"line","data":[186000,...],"smooth":true,...}]}
     */
    @Tool("生成销售趋势折线图的 ECharts JSON 数据。适用于：画折线图、趋势图、" +
            "月度变化图等可视化需求。返回的 JSON 可直接用于前端 ECharts 渲染。")
    public String generateLineChart(
            @P("近多少个月的数据，如 6 表示近 6 个月") int months,
            @P("大区名称，如：华东区。传 null 表示全公司") String regionName,
            @P("图表标题，如：华东区近6个月销售趋势") String title) {

        log.info("工具调用-generateLineChart: months={}, region={}", months, regionName);

        try {
            // 下限也钳一下：传负数会把日期窗口推到未来，查出来是空的，误导人
            int m = Math.min(Math.max(months, 1), 24);

            // 大区名查不到要报错，之前漏了这步，错别字会画出一张全公司的图
            Long regionId = resolveRegionId(regionName);
            if (regionId == null && regionName != null && !regionName.isBlank()) {
                return "未找到大区：" + regionName;
            }

            List<MonthlyTrendDTO> data = queryService.queryMonthlyTrend(regionId, m);
            if (data.isEmpty()) {
                return "暂无数据，无法生成图表";
            }

            // x 轴月份，y 轴金额。金额转 long：图上不需要小数位，JSON 还能短点
            List<String> xAxis = data.stream().map(MonthlyTrendDTO::month).toList();
            List<Number> amounts = data.stream()
                    .map(d -> d.totalAmount().longValue())
                    .map(v -> (Number) v)
                    .toList();

            // 用 LinkedHashMap，JSON 字段顺序和插入顺序一致，调试时好对照
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("title", Map.of("text", title != null ? title : "销售趋势"));
            option.put("tooltip", Map.of("trigger", "axis"));
            option.put("xAxis", Map.of("type", "category", "data", xAxis));
            option.put("yAxis", Map.of("type", "value", "name", "销售额（元）"));
            option.put("series", List.of(Map.of(
                    "type", "line",
                    "data", amounts,
                    "smooth", true,
                    "name", "销售额",
                    "itemStyle", Map.of("color", "#5470c6")
            )));

            String json = objectMapper.writeValueAsString(option);
            return "CHART_JSON:" + json;   // 前端识别 CHART_JSON: 前缀后提取 JSON 渲染

        } catch (Exception e) {
            log.error("生成折线图失败", e);
            return "生成图表数据时出现问题，请稍后重试";
        }
    }

    /**
     * 对比柱状图，两种维度：按大区（dimension=region）或按销售员（dimension=rep）。
     * 需求用例 16 的"Top 10 产品柱状图"是产品维度，目前归在这里的 rep/region 两个维度，
     * 产品柱状图以后要的话再加。
     *
     * dimension 传别的值会直接报错并列出合法值，不猜。
     *
     * 返回大概长这样：
     *   CHART_JSON:{"title":{"text":"各大区销售额对比"},
     *     "xAxis":{"type":"category","data":["华东区","华南区",...],"axisLabel":{"rotate":30}},...}
     */
    @Tool("生成大区或销售员销售额对比的柱状图 ECharts JSON。适用于：画柱状图、" +
            "对比图、排行榜图等可视化需求。")
    public String generateBarChart(
            @P("对比维度：region（按大区对比）或 rep（按销售员对比）") String dimension,
            @P("查询开始日期，格式 yyyy-MM-dd") String startDate,
            @P("查询结束日期，格式 yyyy-MM-dd") String endDate,
            @P("图表标题") String title) {

        log.info("工具调用-generateBarChart: dim={}, start={}, end={}", dimension, startDate, endDate);

        try {
            // dimension 拼错了就报错，不能落到 else 里悄悄换成另一个维度——
            // 用户要的是大区图，拿到一张销售员图还以为是对的，这才是麻烦
            if (!"region".equals(dimension) && !"rep".equals(dimension)) {
                return "dimension 仅支持 region（按大区）或 rep（按销售员），收到：" + dimension;
            }

            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);

            List<String> names;
            List<Number> values;

            if ("region".equals(dimension)) {
                // 大区就四个，全画
                List<RegionSalesDTO> regions = queryService.queryRegionRanking(start, end);
                names = regions.stream().map(RegionSalesDTO::regionName).toList();
                values = regions.stream()
                        .map(r -> (Number) r.totalAmount().longValue()).toList();
            } else {
                // 销售员有 20 个，全画太挤，取前 10
                List<RepSalesDTO> reps =
                        queryService.queryRepRanking(null, start, end, 10);
                names = reps.stream().map(RepSalesDTO::repName).toList();
                values = reps.stream()
                        .map(r -> (Number) r.totalAmount().longValue()).toList();
            }

            if (names.isEmpty()) {
                return "暂无数据，无法生成图表";
            }

            Map<String, Object> option = new LinkedHashMap<>();
            option.put("title", Map.of("text", title != null ? title : "销售对比"));
            option.put("tooltip", Map.of("trigger", "axis"));
            // 中文标签横排会叠在一起，转 30 度
            option.put("xAxis", Map.of("type", "category", "data", names,
                    "axisLabel", Map.of("rotate", 30)));
            option.put("yAxis", Map.of("type", "value", "name", "销售额（元）"));
            option.put("series", List.of(Map.of(
                    "type", "bar",
                    "data", values,
                    "itemStyle", Map.of("color", "#91cc75")
            )));

            String json = objectMapper.writeValueAsString(option);
            return "CHART_JSON:" + json;

        } catch (Exception e) {
            log.error("生成柱状图失败", e);
            return "生成图表数据时出现问题，请稍后重试";
        }
    }

    /**
     * 占比饼图，两种维度：按大区（region）或按品类（category）。
     * 对应需求用例 15"各大区销售额占比饼图"。
     *
     * 品类那条路的算法：产品排名查前 100 条明细，在内存里按品类把金额加起来。
     * 现在 50 个 SKU 全盖得住，哪天产品超过 100 个就得把聚合下沉到 SQL 了。
     *
     * dimension 传别的值同样直接报错。
     */
    @Tool("生成销售占比饼图的 ECharts JSON。适用于：画饼图、各部分占比、" +
            "份额分布等可视化需求。")
    public String generatePieChart(
            @P("饼图维度：region（大区占比）、category（品类占比）") String dimension,
            @P("查询开始日期，格式 yyyy-MM-dd") String startDate,
            @P("查询结束日期，格式 yyyy-MM-dd") String endDate,
            @P("图表标题") String title) {

        log.info("工具调用-generatePieChart: dim={}, start={}, end={}", dimension, startDate, endDate);

        try {
            if (!"region".equals(dimension) && !"category".equals(dimension)) {
                return "dimension 仅支持 region（大区占比）或 category（品类占比），收到：" + dimension;
            }

            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);

            List<Map<String, Object>> pieData;

            if ("region".equals(dimension)) {
                // 饼图要凑 100%，所以全量大区一个不能少
                List<RegionSalesDTO> regions = queryService.queryRegionRanking(start, end);
                pieData = regions.stream().map(r -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", r.regionName());
                    item.put("value", r.totalAmount().longValue());
                    return item;
                }).toList();
            } else {
                // 同品类金额累加，merge 一行就够。LinkedHashMap 保持品类首次出现的顺序
                List<ProductSalesDTO> products = queryService.queryProductRanking(start, end, 100);
                Map<String, BigDecimal> categoryMap = new LinkedHashMap<>();
                for (ProductSalesDTO p : products) {
                    categoryMap.merge(p.category(), p.totalAmount(), BigDecimal::add);
                }
                pieData = categoryMap.entrySet().stream().map(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", e.getKey());
                    item.put("value", e.getValue().longValue());
                    return item;
                }).toList();
            }

            if (pieData.isEmpty()) {
                return "暂无数据，无法生成图表";
            }

            Map<String, Object> option = new LinkedHashMap<>();
            // 饼图的几个专属配置：标题居中；悬浮显示"名称: 金额 (占比％)"；
            // 图例竖排放左边，品类名长，横排会把饼挤小
            option.put("title", Map.of("text", title != null ? title : "销售占比", "left", "center"));
            option.put("tooltip", Map.of("trigger", "item", "formatter", "{b}: {c} ({d}%)"));
            option.put("legend", Map.of("orient", "vertical", "left", "left"));
            option.put("series", List.of(Map.of(
                    "type", "pie",
                    "radius", "55%",
                    "data", pieData,
                    "emphasis", Map.of("itemStyle",
                            Map.of("shadowBlur", 10, "shadowOffsetX", 0, "shadowColor", "rgba(0,0,0,0.5)"))
            )));

            String json = objectMapper.writeValueAsString(option);
            return "CHART_JSON:" + json;

        } catch (Exception e) {
            log.error("生成饼图失败", e);
            return "生成图表数据时出现问题，请稍后重试";
        }
    }

    /**
     * 大区名换 ID，和 SalesTrendTool 里那个是同一个套路。
     * 返回 null 分两种：没传名字、名字查不到。调用处得拿 regionName 再判一下，
     * 不然查不到就悄悄变全公司了。本类里只有折线图用得到（柱状图饼图本来就是跨区对比）。
     */
    private Long resolveRegionId(String regionName) {
        if (regionName == null || regionName.isBlank()) return null;
        return queryService.getRegionIdByName(regionName);
    }
}
