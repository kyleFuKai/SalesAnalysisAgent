package com.kyle.salesAgent.tool;

import com.kyle.salesAgent.dto.ProductSalesDTO;
import com.kyle.salesAgent.dto.RegionSalesDTO;
import com.kyle.salesAgent.dto.RepSalesDTO;
import com.kyle.salesAgent.service.SalesQueryService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 销售汇总工具 —— 5 个工具之二
 * <p>对应需求 4.1-B 汇总统计类用例："多少 / 排名 / 完成率"类问题归这里。
 * <p><b>与 SalesQueryTool 的分工</b>：本工具答"算出来的数"（排名、总额、占比），
 * 原始订单明细归 SalesQueryTool，同比环比趋势归 SalesTrendTool。
 * <p><b>双层注释约定</b>（同 SalesQueryTool）：{@code @Tool}/{@code @P} 描述给
 * AI 模型看（决定何时调用、怎么传参）；JavaDoc 与行内注释给程序员看。
 * <p><b>权限语义</b>：不做权限校验（架构 4.2），大区收窄由调用方按当前用户角色
 * 传 regionName 实现；跨区排名（大区对比）天然是总监视角，由上层约束。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/9/19 12:51
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SalesSummaryTool {

    private final SalesQueryService queryService;

    /**
     * AI 工具入口：销售员业绩排名（Top N）
     * <p>业务场景："谁卖得最多"、"本月 Top 5 销售员"、"华东区 Top 3 销售员是谁"
     *（需求 4.1-B + 数据流转示例 6 章演示链路）
     * <p>输出格式：逐行"第 N 名：姓名（大区）销售额"，模型可直接拼装回答。
     *
     * @param startDate  查询起始日期（yyyy-MM-dd）
     * @param endDate    查询结束日期（yyyy-MM-dd）
     * @param regionName 大区名过滤（null/空 = 全公司）
     * @param topN       取前几名（方法内钳制到 1~20，防模型传 0 或超大值）
     * @return 排名列表字符串；时段无数据时返回明确空提示
     */
    @Tool("计算销售员业绩排名。适用于：谁卖得最多、Top N 销售员、业绩第一名、销售冠军、" +
            "各销售员的销售额对比。可按大区筛选或查全公司。")
    public String getTopReps(
            @P("查询开始日期，格式 yyyy-MM-dd") String startDate,
            @P("查询结束日期，格式 yyyy-MM-dd") String endDate,
            @P("大区名称，如：华东区。传 null 或空字符串表示查全公司") String regionName,
            @P("返回前 N 名，默认 5，最大 20") int topN) {

        // 调用留痕：审计与排错追溯"模型何时调了什么工具、传了什么参数"
        log.info("工具调用-getTopReps: start={}, end={}, region={}, topN={}",
                startDate, endDate, regionName, topN);

        try {
            // 参数解析：字符串日期 → LocalDate；格式错走专门的 catch 分支给友好提示
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            // topN 双向钳制：下限 1（防 0/负数）、上限 20（防模型传超大值撑爆上下文）
            int n = Math.min(Math.max(topN, 1), 20);

            // 名称 → ID 翻译 + NOT_FOUND 校验（与 getSalesSummary 同一套约定）：
            // 查无此大区要明确告知，绝不静默回退成全公司（架构 4.4 语义）
            Long regionId = null;
            if (regionName != null && !regionName.isBlank()) {
                regionId = queryService.getRegionIdByName(regionName);
                if (regionId == null) {
                    return "未找到大区：" + regionName + "，请确认大区名称是否正确（华东区/华南区/华北区/西南区）";
                }
            }

            // 委托 Service：regionId 非空时 SQL 端收窄到本区；null 时条件自动失效（全公司），
            // 表头标签与实际查询范围从此保持一致
            List<RepSalesDTO> reps = queryService.queryRepRanking(regionId, start, end, n);

            // 空结果给明确提示，避免模型猜测"是没数据还是没权限"
            if (reps.isEmpty()) {
                return "该时段内暂无销售数据";
            }

            StringBuilder sb = new StringBuilder();
            // 表头：日期范围 + 范围标注（regionName 为空时显式写"全公司"，不留歧义）
            sb.append(String.format("销售员业绩排名（%s 至 %s%s）：\n\n",
                    startDate, endDate,
                    regionName != null && !regionName.isBlank() ? "，" + regionName : "，全公司"));

            // 逐名输出：序号从 1 开始（i+1），DTO 用 record 访问器（repName() 不带 get 前缀）
            for (int i = 0; i < reps.size(); i++) {
                RepSalesDTO rep = reps.get(i);
                sb.append(String.format("第 %d 名：%s（%s）  销售额：¥%,.0f\n",
                        i + 1, rep.repName(), rep.regionName(), rep.totalAmount()));
            }
            return sb.toString();
            // 注：orderCount 字段暂未输出——Service 层目前硬编码 0（技术债 #2），
            // 输出反而会让模型答"0 笔"，等补真值后再加

        } catch (DateTimeParseException e) {
            // 日期解析失败：给模型正确格式示例，让它能自行修正重试
            return "日期格式错误，请使用 yyyy-MM-dd 格式";
        } catch (Exception e) {
            // 兜底：堆栈进日志（排错用），给模型/用户的消息保持友好
            log.error("查询销售员排名失败", e);
            return "查询排名数据时出现问题，请稍后重试";
        }
    }

    /**
     * AI 工具入口：大区业绩排名（含占比）
     * <p>业务场景："各大区销售额排名"（需求用例 8）、"各大区销售额占比饼图"（用例 15）
     * 的数据源——占比直接算好，模型拼回答/画饼图两用。
     * <p>权限语义：跨区对比是总监专属视角（需求 4.4 矩阵），由上层约束谁能调。
     * <p>输出格式：逐行"第 N 名：大区名 销售额 占比%"，末行附全公司合计。
     *
     * @param startDate 查询起始日期（yyyy-MM-dd）
     * @param endDate   查询结束日期（yyyy-MM-dd）
     * @return 排名 + 占比 + 合计的字符串；无数据时返回明确空提示
     */
    @Tool("计算各大区的销售业绩排名。适用于：哪个大区最好、大区业绩对比、各区销售额、" +
            "大区排行榜等场景。")
    public String getRegionRanking(
            @P("查询开始日期，格式 yyyy-MM-dd") String startDate,
            @P("查询结束日期，格式 yyyy-MM-dd") String endDate) {

        log.info("工具调用-getRegionRanking: start={}, end={}", startDate, endDate);

        try {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);

            // 全量大区（不截断）：饼图要 100% 占比，缺一个大区就拼不齐
            List<RegionSalesDTO> regions = queryService.queryRegionRanking(start, end);
            if (regions.isEmpty()) {
                return "该时段内暂无数据";
            }

            // 先算全公司总额，作占比的分母（毛额口径，与各分子一致）
            BigDecimal grandTotal = regions.stream()
                    .map(RegionSalesDTO::totalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("大区业绩排名（%s 至 %s）：\n\n", startDate, endDate));

            for (int i = 0; i < regions.size(); i++) {
                RegionSalesDTO region = regions.get(i);
                // 占比 = 本区 / 全公司 × 100；分母为 0 时给 0 防除零
                //（double 精度用于展示足够，业务精度在 Service 层已保证）
                double ratio = grandTotal.compareTo(BigDecimal.ZERO) > 0
                        ? region.totalAmount().doubleValue() / grandTotal.doubleValue() * 100 : 0;
                sb.append(String.format("第 %d 名：%s  销售额：¥%,.0f  占比：%.1f%%\n",
                        i + 1, region.regionName(), region.totalAmount(), ratio));
            }
            // 末行合计：给模型核对"各区加起来=总"的锚点，也服务"全公司卖了多少钱"的追问
            sb.append(String.format("\n全公司合计：¥%,.0f", grandTotal));
            return sb.toString();

        } catch (DateTimeParseException e) {
            return "日期格式错误，请使用 yyyy-MM-dd 格式";
        } catch (Exception e) {
            log.error("查询大区排名失败", e);
            return "查询大区数据时出现问题，请稍后重试";
        }
    }

    /**
     * AI 工具入口：产品销售排名（Top N，支持"最差 N 名"）
     * <p>业务场景："本月 Top 10 产品"（用例 9/16，柱状图数据源）、"有单产品里谁卖得差"。
     * <p>技巧：topN 传<b>负数</b>表示查最差的 |topN| 名——一个参数两种语义，
     * 省得再加一个 getWorstProducts 工具（工具越多模型越容易选错）。
     * <p>统计边界（Bug-2 修正）：GROUP BY 只覆盖"该时段有销售记录"的产品，
     * 零销售产品天然不在榜单——查最差时输出末尾注明零销售产品个数，
     * "滞销/断货"场景明确让给 AnomalyDetectionTool（需求 E19）。
     * <p>输出格式：逐行"第 N 名：产品名 [SKU] 品类 销售额 数量"；最差榜末尾附零销售说明。
     *
     * @param startDate 查询起始日期（yyyy-MM-dd）
     * @param endDate   查询结束日期（yyyy-MM-dd）
     * @param topN      正数 = 前 N 名；负数 = 最差 |N| 名（绝对值钳到 ≤20，仅含有单产品）
     * @return 排名列表字符串；无数据时返回明确空提示
     */
    @Tool("计算产品销售排名。适用于：最畅销产品、Top N SKU、哪个产品卖得最好、各品类销售情况。" +
            "传负数 topN 可查有销售记录中卖得最差的产品。" +
            "【注意】零销售产品不在统计范围内，滞销/断货预警请使用异常检测工具。")
    public String getTopProducts(
            @P("查询开始日期，格式 yyyy-MM-dd") String startDate,
            @P("查询结束日期，格式 yyyy-MM-dd") String endDate,
            @P("返回前 N 名，默认 10，最大 20。负数表示查最差的 N 名（仅统计有销售记录的产品）") int topN) {

        log.info("工具调用-getTopProducts: start={}, end={}, topN={}", startDate, endDate, topN);

        try {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            // 语义分流：负数 → 查最差；n 取绝对值并钳到 20
            boolean isWorst = topN < 0;
            int n = Math.min(Math.abs(topN), 20);

            // 查最差时先取"全部"（传 999 即不截断），后面再切尾部；
            // 查最佳时直接让 Service 截前 n
            List<ProductSalesDTO> products = queryService.queryProductRanking(start, end, isWorst ? 999 : n);
            if (products.isEmpty()) {
                return "该时段内暂无产品销售数据";
            }
            // 有单产品数要在截断前记下——零销售数 = 在售总数 − 这个数（Bug-2 修正），
            // 截断后的 size 只是"展示了 N 条"，不能拿来当分母算
            int withSalesCount = products.size();

            // 如果要查最差的，倒序取
            if (isWorst) {
                // 切尾部 n 条（升序里它们是最差的）；Math.max 防 n > 总数时下标越界
                products = products.subList(Math.max(0, products.size() - n), products.size());
                // subList 是原列表的"视图"，直接 reverse 会改原列表——先拷贝再倒序，
                // 让输出仍然按"最差在前"排列
                products = new java.util.ArrayList<>(products);
                java.util.Collections.reverse(products);
            } else {
                // 最佳：取前 n（Math.min 防越界）
                products = products.subList(0, Math.min(n, products.size()));
            }

            StringBuilder sb = new StringBuilder();
            // 标题区分最佳/最差，模型拼回答时不会说反
            sb.append(String.format("产品销售排名%s（%s 至 %s）：\n\n",
                    isWorst ? "（最差）" : "（最佳）", startDate, endDate));

            for (int i = 0; i < products.size(); i++) {
                ProductSalesDTO p = products.get(i);
                // 一行带全关键维度：产品名 + SKU 编码 + 品类 + 金额 + 数量
                sb.append(String.format("第 %d 名：%s [%s]  品类：%s  销售额：¥%,.0f  数量：%d 件\n",
                        i + 1, p.productName(), p.skuCode(), p.category(),
                        p.totalAmount(), p.totalQuantity()));
            }

            // 最差榜必须注明统计边界（Bug-2 修正）：零销售产品不在 GROUP BY 结果里，
            // 不说明的话模型会把"有单里最差的"当成"全部里最差的"——诚实标注 +
            // 给出零销售个数，模型能答出"最差 A/B/C，另有 X 个零销售"的完整结论
            if (isWorst) {
                long zeroSalesCount = Math.max(0, queryService.countActiveProducts() - withSalesCount);
                if (zeroSalesCount > 0) {
                    sb.append(String.format(
                            "\n注：以上仅统计该时段有销售记录的产品，另有 %d 个在售产品该时段零销售记录（连续零销售/断货预警属异常检测范围）。",
                            zeroSalesCount));
                } else {
                    sb.append("\n注：以上仅统计该时段有销售记录的产品（全部在售产品均有销售记录）。");
                }
            }
            return sb.toString();

        } catch (DateTimeParseException e) {
            return "日期格式错误，请使用 yyyy-MM-dd 格式";
        } catch (Exception e) {
            log.error("查询产品排名失败", e);
            return "查询产品数据时出现问题，请稍后重试";
        }
    }

    /**
     * AI 工具入口：时段销售额汇总（按人 / 按区 / 全公司三种范围）
     * <p>业务场景："本月销售额是多少"（用例 5）、"华东区这个月卖了多少钱"、
     * "我这个月卖了多少"（销售员最高频问题，需求 3.1）、"张伟和王芳业绩差多少"（用例 7）。
     * <p>范围优先级：repName 非空 → 按人查（regionName 忽略）；否则 regionName 非空 → 按区；
     * 都空 → 全公司。"华东区的张伟"这类问法人 是主语，故人优先。
     * <p>口径：毛额、仅 COMPLETED 订单（由 Service 的 JPQL 保证，各路径一致），
     * 输出末尾附口径标注。
     * <p>名称校验：姓名/大区名不存在时返回 NOT_FOUND 提示（架构 4.4 语义）。
     *
     * @param startDate  查询起始日期（yyyy-MM-dd）
     * @param endDate    查询结束日期（yyyy-MM-dd）
     * @param regionName 大区名（null/空 = 不按区；repName 非空时本参数被忽略）
     * @param repName    销售员姓名（null/空 = 不按人；非空时优先于 regionName）
     * @return 汇总字符串（总销售额 + 完成订单数 + 口径标注）；名称无效时返回纠正提示
     */
    @Tool("计算指定时段的总销售额、订单数等汇总数据。适用于：总销售额是多少、" +
            "本月/本季/本年收入、某大区整体业绩、某销售员个人业绩（如'我这个月卖了多少'）等场景。")
    public String getSalesSummary(
            @P("查询开始日期，格式 yyyy-MM-dd") String startDate,
            @P("查询结束日期，格式 yyyy-MM-dd") String endDate,
            @P("大区名称，如：华东区。传 null 表示查全公司。与 repName 同时传时以 repName 为准") String regionName,
            @P("销售员姓名，如：张伟。传 null 或空表示不按人查询；传了则按该销售员统计，优先于大区") String repName) {

        log.info("工具调用-getSalesSummary: start={}, end={}, region={}, rep={}",
                startDate, endDate, regionName, repName);

        try {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);

            BigDecimal totalAmount;
            Long orderCount;
            String scopeLabel;

            if (repName != null && !repName.isBlank()) {
                // 按人路径：名称 → ID + NOT_FOUND 校验（查无此人明确告知，不静默回退成全公司）
                Long repId = queryService.getRepIdByName(repName);
                if (repId == null) {
                    return "未找到销售员：" + repName + "，请确认姓名是否正确";
                }
                // 金额与订单数同口径（同 repId / 日期 / COMPLETED 条件）
                totalAmount = queryService.queryRepTotalAmount(repId, start, end);
                orderCount = queryService.queryRepOrderCount(repId, start, end);
                scopeLabel = repName;
            } else {
                // 按区/全公司路径：名称 → ID + NOT_FOUND 校验
                Long regionId = null;
                if (regionName != null && !regionName.isBlank()) {
                    regionId = queryService.getRegionIdByName(regionName);
                    if (regionId == null) {
                        return "未找到大区：" + regionName;
                    }
                }
                totalAmount = queryService.queryTotalAmount(regionId, start, end);
                orderCount = queryService.queryOrderCount(regionId, start, end);
                scopeLabel = regionName != null && !regionName.isBlank() ? regionName : "全公司";
            }

            // 口径标注（需求 2 节通用原则）：口径有歧义的回答须注明所采用的口径，
            // 模型拼回答时自然带上，支撑验收指标"口径说明完整率 ≥95%"
            return String.format("销售额汇总（%s 至 %s，%s）：\n总销售额：¥%,.0f\n完成订单数：%d 笔\n（毛额口径，仅统计已完成订单）",
                    startDate, endDate, scopeLabel, totalAmount, orderCount);

        } catch (DateTimeParseException e) {
            return "日期格式错误，请使用 yyyy-MM-dd 格式";
        } catch (Exception e) {
            log.error("查询销售汇总失败", e);
            return "查询汇总数据时出现问题，请稍后重试";
        }
    }
}
