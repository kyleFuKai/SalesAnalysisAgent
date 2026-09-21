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
 * 销售汇总工具，管"多少 / 排名 / 完成率"这类问题（需求 4.1-B）：
 * 销售员排名、大区排名、产品排名、销售额汇总。
 * 原始明细在 SalesQueryTool，趋势对比在 SalesTrendTool，别混。
 *
 * 口径跟 Service 一致：毛额，只算 COMPLETED 订单。
 * 工具层不做权限校验，大区收窄靠调用方传 regionName；跨区排名是大区对比，
 * 天然只有总监能问，上层约束。
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
     * 销售员业绩排名，Top N。对应用例"本月 Top 5 销售员"、"华东区 Top 3 销售员是谁"。
     * regionName 传了就只排本区，不传排全公司。
     */
    @Tool("计算销售员业绩排名。适用于：谁卖得最多、Top N 销售员、业绩第一名、销售冠军、" +
            "各销售员的销售额对比。可按大区筛选或查全公司。")
    public String getTopReps(
            @P("查询开始日期，格式 yyyy-MM-dd") String startDate,
            @P("查询结束日期，格式 yyyy-MM-dd") String endDate,
            @P("大区名称，如：华东区。传 null 或空字符串表示查全公司") String regionName,
            @P("返回前 N 名，默认 5，最大 20") int topN) {

        log.info("工具调用-getTopReps: start={}, end={}, region={}, topN={}",
                startDate, endDate, regionName, topN);

        try {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            // 上限 20 防模型传个大数把上下文撑爆，下限 1 防 0 和负数
            int n = Math.min(Math.max(topN, 1), 20);

            // 大区名查不到就报错，不能当成全公司查——不然标题写着华东区，
            // 数据却是全公司的（这个 bug 真出现过）
            Long regionId = null;
            if (regionName != null && !regionName.isBlank()) {
                regionId = queryService.getRegionIdByName(regionName);
                if (regionId == null) {
                    return "未找到大区：" + regionName + "，请确认大区名称是否正确（华东区/华南区/华北区/西南区）";
                }
            }

            List<RepSalesDTO> reps = queryService.queryRepRanking(regionId, start, end, n);
            if (reps.isEmpty()) {
                return "该时段内暂无销售数据";
            }

            StringBuilder sb = new StringBuilder();
            // 不传大区就明写"全公司"，别让模型猜
            sb.append(String.format("销售员业绩排名（%s 至 %s%s）：\n\n",
                    startDate, endDate,
                    regionName != null && !regionName.isBlank() ? "，" + regionName : "，全公司"));

            for (int i = 0; i < reps.size(); i++) {
                RepSalesDTO rep = reps.get(i);
                sb.append(String.format("第 %d 名：%s（%s）  销售额：¥%,.0f\n",
                        i + 1, rep.repName(), rep.regionName(), rep.totalAmount()));
            }
            // orderCount 没往外带：Service 那边还是 0 占位，写出来模型会答"0 笔"，
            // 等补了真值再加
            return sb.toString();

        } catch (DateTimeParseException e) {
            return "日期格式错误，请使用 yyyy-MM-dd 格式";
        } catch (Exception e) {
            log.error("查询销售员排名失败", e);
            return "查询排名数据时出现问题，请稍后重试";
        }
    }

    /**
     * 大区排名，带占比和全公司合计。对应用例 8"各大区销售额排名"，
     * 也是用例 15 饼图的数据来源。只有总监能看跨区数据，上层约束。
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

            List<RegionSalesDTO> regions = queryService.queryRegionRanking(start, end);
            if (regions.isEmpty()) {
                return "该时段内暂无数据";
            }

            // 先算总盘，当占比的分母
            BigDecimal grandTotal = regions.stream()
                    .map(RegionSalesDTO::totalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("大区业绩排名（%s 至 %s）：\n\n", startDate, endDate));

            for (int i = 0; i < regions.size(); i++) {
                RegionSalesDTO region = regions.get(i);
                // 总盘为 0 时给 0，防除零。展示用 double 够了，业务精度在 Service
                double ratio = grandTotal.compareTo(BigDecimal.ZERO) > 0
                        ? region.totalAmount().doubleValue() / grandTotal.doubleValue() * 100 : 0;
                sb.append(String.format("第 %d 名：%s  销售额：¥%,.0f  占比：%.1f%%\n",
                        i + 1, region.regionName(), region.totalAmount(), ratio));
            }
            // 末尾放合计，模型答"全公司一共卖了多少"也能用同一次调用
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
     * 产品排名，topN 传正数查卖得最好的，传负数查"有单产品里"卖得最差的。
     * 一个参数顶两个用途，省得再加一个查最差的工具——工具越多模型越容易选错。
     *
     * 注意：SQL 按产品 GROUP BY，整个时段一单没卖的产品根本不在结果里，
     * 所以"最差榜"是"有单里最差的"，不是真滞销榜。真滞销（连续零销售）
     * 归异常检测工具管（需求 E19）。查最差时输出里会注明这一点。
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
            boolean isWorst = topN < 0;
            int n = Math.min(Math.abs(topN), 20);

            // 查最差时先拿全量（传 999），回头切尾部；查最佳直接让 Service 截前 n
            List<ProductSalesDTO> products = queryService.queryProductRanking(start, end, isWorst ? 999 : n);
            if (products.isEmpty()) {
                return "该时段内暂无产品销售数据";
            }
            // 有单产品数要在截断前记下来，零销售个数 = 在售总数 - 这个数
            int withSalesCount = products.size();

            if (isWorst) {
                // 切尾部 n 条再倒序，让最差的排前面。Math.max 防 n 比总数还大的越界
                products = products.subList(Math.max(0, products.size() - n), products.size());
                // subList 是视图，直接 reverse 会改底下列表，先拷一份
                products = new java.util.ArrayList<>(products);
                java.util.Collections.reverse(products);
            } else {
                products = products.subList(0, Math.min(n, products.size()));
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("产品销售排名%s（%s 至 %s）：\n\n",
                    isWorst ? "（最差）" : "（最佳）", startDate, endDate));

            for (int i = 0; i < products.size(); i++) {
                ProductSalesDTO p = products.get(i);
                sb.append(String.format("第 %d 名：%s [%s]  品类：%s  销售额：¥%,.0f  数量：%d 件\n",
                        i + 1, p.productName(), p.skuCode(), p.category(),
                        p.totalAmount(), p.totalQuantity()));
            }

            // 最差榜必须说明统计边界，不然模型把"有单里最差的"当成"全部里最差的"来答。
            // 零销售个数 = 在售产品总数 - 有单产品数
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
     * 销售额汇总，对应用例 5"本月销售额是多少"，也是销售员天天问的
     * "我这个月卖了多少"。
     *
     * 范围按优先级：传了 repName 就按人查（regionName 忽略），
     * 不然传了 regionName 按区查，都空就是全公司。
     * 人优先是因为用户会说"华东区的张伟卖了多少"——人才是主语。
     *
     * 口径：毛额，只算 COMPLETED。输出末尾带口径标注，模型回答时会带上，
     * 满足需求 2 节"口径要说明"的要求。
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
                // 按人。查无此人要明说，不能当"没数据"
                Long repId = queryService.getRepIdByName(repName);
                if (repId == null) {
                    return "未找到销售员：" + repName + "，请确认姓名是否正确";
                }
                totalAmount = queryService.queryRepTotalAmount(repId, start, end);
                orderCount = queryService.queryRepOrderCount(repId, start, end);
                scopeLabel = repName;
            } else {
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

            // 金额和订单数同一个口径，模型可以放心放一起说
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
