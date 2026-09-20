package com.kyle.salesAgent.service;

import com.kyle.salesAgent.dto.MonthlyTrendDTO;
import com.kyle.salesAgent.dto.ProductSalesDTO;
import com.kyle.salesAgent.dto.RegionSalesDTO;
import com.kyle.salesAgent.dto.RepSalesDTO;
import com.kyle.salesAgent.entity.SalesOrder;
import com.kyle.salesAgent.entity.SalesRep;
import com.kyle.salesAgent.repository.ProductRepository;
import com.kyle.salesAgent.repository.SalesOrderRepository;
import com.kyle.salesAgent.repository.SalesRegionRepository;
import com.kyle.salesAgent.repository.SalesRepRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 销售数据查询 Service —— 所有工具（@Tool）的统一业务入口
 * <p>对应架构文档 3.2.5：封装查询逻辑、承载口径定义。
 * <p><b>权限约定</b>：本类不做权限校验（工具层不感知权限），行级过滤通过调用方
 * 传入的 repId / regionId 参数实现——销售员传自己的 repId、主管传本区 regionId、
 * 总监传 null（全量）。后续接入 UserContext 后由 AOP 强制注入（fail-closed）。
 * <p><b>指标口径</b>（对齐需求文档第 2 节）：销售额均为毛额口径，仅统计
 * status = COMPLETED 的订单，按 order_date 归属统计周期。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/9/18 00:35
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SalesQueryService {

    private final SalesOrderRepository orderRepository;
    private final SalesRepRepository repRepository;
    private final ProductRepository productRepository;
    private final SalesRegionRepository regionRepository;

    // ============================================================
    // 基础查询
    // ============================================================

    /**
     * 查询指定时段的订单列表（原始明细）
     * <p>业务场景：需求 4.1-A 类用例，如"上个月华东区的所有订单有哪些"、"张磊这个月成交了哪几单"
     * <p>权限语义：三个参数决定查询范围，调用方按当前用户权限传入——
     * <ul>
     *   <li>repId 非空：只查该销售员的订单（销售员角色用，只能传自己的 repId）</li>
     *   <li>regionId 非空：查该大区所有人的订单（主管角色用，只能传本区）</li>
     *   <li>两者都为 null：全量查询（仅总监角色应走到此分支）</li>
     * </ul>
     * <p>口径：包含全部状态（COMPLETED/REFUNDED/CANCELLED），明细场景退单也要能看到
     *
     * @param repId    销售员ID（按人过滤时传，否则 null）
     * @param regionId 大区ID（按区过滤时传，否则 null）
     * @param start    起始日期（含）
     * @param end      结束日期（含）
     * @return 订单明细列表，按查询条件的日期区间过滤；无数据返回空列表
     */
    public List<SalesOrder> queryOrders(Long repId, Long regionId,
                                        LocalDate start, LocalDate end) {
        // 范围从窄到宽依次判断：先按人 → 再按区 → 最后全量
        if (repId != null) {
            // 销售员角色：只查本人订单（最窄范围，优先命中）
            return orderRepository.findByRepIdAndOrderDateBetween(repId, start, end);
        }
        if (regionId != null) {
            // 主管角色：查本大区所有人的订单
            return orderRepository.findByRegionIdAndOrderDateBetween(regionId, start, end);
        }
        // 全量查询（只有 SALES_DIRECTOR(销售总监) 角色会走到这里）：
        // findAll 拉全量后在内存按日期闭区间过滤（!isBefore && !isAfter ≈ BETWEEN）；
        // 数据量大时应改为 findByOrderDateBetween 让数据库在索引上过滤
        return orderRepository.findAll().stream()
                .filter(o -> !o.getOrderDate().isBefore(start) && !o.getOrderDate().isAfter(end))
                .collect(Collectors.toList());
    }

    /**
     * 查询指定范围的总销售额
     * <p>业务场景：需求 4.1-B 类用例，如"本月销售额是多少"、"华东区这个月卖了多少钱"
     * <p>权限语义：regionId 为 null 时表示全公司口径（仅总监应如此调用）；
     * 主管传本区 regionId，销售员按人查询走 {@link #queryRepTotalAmount}
     * <p>口径：毛额，仅 status = COMPLETED 的订单金额合计
     *
     * @param regionId 大区ID（null 表示全公司）
     * @param start    起始日期（含）
     * @param end      结束日期（含）
     * @return 总销售额；无符合条件订单时返回 0（COALESCE 兜底，不返回 null）
     */
    public BigDecimal queryTotalAmount(Long regionId, LocalDate start, LocalDate end) {
        if (regionId != null) {
            // 按区路径：JPQL 在数据库端完成过滤与聚合（含 status='COMPLETED'，口径与全公司路径一致）
            return orderRepository.sumAmountByRegion(regionId, start, end);
        }
        // 全公司路径：内存三步过滤——① 留已完成 ② 留日期区间 ③ 金额求和
        return orderRepository.findAll().stream()
                .filter(o -> o.getStatus().equals("COMPLETED"))
                .filter(o -> !o.getOrderDate().isBefore(start) && !o.getOrderDate().isAfter(end))
                .map(SalesOrder::getAmount)                       // 提取金额字段
                .reduce(BigDecimal.ZERO, BigDecimal::add);        // 从 0 起累加，无数据时自然返回 0
    }

    /**
     * 查询指定销售员的总销售额
     * <p>业务场景：销售员最高频问题"我这个月卖了多少"（需求 3.1 / 4.1-B），
     * 也服务"张伟和王芳业绩差多少"的对比场景（模型取两人各时段数字相减）
     * <p>口径：毛额，仅 status = COMPLETED 的订单金额合计（与 {@link #queryTotalAmount} 一致）
     *
     * @param repId 销售员ID（必传——按人查询没有"全公司"语义）
     * @param start 起始日期（含）
     * @param end   结束日期（含）
     * @return 总销售额；无符合条件订单时返回 0（COALESCE 兜底，不返回 null）
     */
    public BigDecimal queryRepTotalAmount(Long repId, LocalDate start, LocalDate end) {
        // 按人路径：JPQL 在数据库端完成过滤与聚合
        return orderRepository.sumAmountByRep(repId, start, end);
    }

    /**
     * 查询指定销售员的完成订单数
     * <p>用途：与 {@link #queryRepTotalAmount} 配套返回（"卖了多少 + 多少单"）
     * <p>口径：仅 status = COMPLETED 的订单
     *
     * @param repId 销售员ID（必传）
     * @param start 起始日期（含）
     * @param end   结束日期（含）
     * @return 完成订单笔数；无数据返回 0
     */
    public Long queryRepOrderCount(Long repId, LocalDate start, LocalDate end) {
        return orderRepository.countCompletedByRep(repId, start, end);
    }

    /** ============================================================
     * 排名查询
     * ============================================================ */

    /**
     * 销售员业绩排名（带姓名、大区信息，支持大区过滤）
     * <p>业务场景：需求用例 Top N 排名场景；"华东区 Top 3 销售员是谁"（数据流转示例 6 章演示链路）
     * <p>权限语义：regionId 传 null 查全公司（总监视角）；主管传本区 regionId 收窄到本区
     * <p>口径：仅 COMPLETED 订单金额合计，按金额降序取前 N
     * <p>实现说明：repository 返回 Object[]（repId + 总金额），本方法批量补齐
     * 姓名与大区名（一次 findAll 建 Map，避免循环内逐条查询造成 N+1）
     *
     * @param regionId 大区ID（null 表示全公司）
     * @param start    起始日期（含）
     * @param end      结束日期（含）
     * @param topN     取前几名
     * @return 销售员业绩列表（金额降序），最多 topN 条；orderCount 暂为 0（待单独统计）
     */
    public List<RepSalesDTO> queryRepRanking(Long regionId, LocalDate start, LocalDate end, int topN) {
        // 聚合查询：raw 每行结构为 [repId(0), 总金额(1)]，已按金额降序；
        // regionId 过滤在 SQL 端完成（:regionId IS NULL OR ... 条件）
        List<Object[]> raw = orderRepository.findRepRanking(regionId, start, end);

        // 批量查询销售员信息，避免 N+1：建 id → 实体 映射
        Map<Long, SalesRep> repMap = repRepository.findAll().stream()
                .collect(Collectors.toMap(SalesRep::getId, r -> r));
        // 批量查询大区名：建 id → 大区名 映射
        Map<Long, String> regionNameMap = regionRepository.findAll().stream()
                .collect(Collectors.toMap(r -> r.getId(), r -> r.getName()));

        List<RepSalesDTO> result = new ArrayList<>();
        for (Object[] row : raw) {
            // row[0] 实际类型可能是 Long/BigInteger 等，统一经 Number 转 Long
            Long repId = ((Number) row[0]).longValue();
            BigDecimal total = new BigDecimal(row[1].toString());
            // 销售员不存在（数据不一致）时跳过该行，避免 NPE
            SalesRep rep = repMap.get(repId);
            if (rep == null) continue;

            // 补齐大区名；映射查不到（脏数据）时兜底"未知"而非 null
            String regionName = regionNameMap.getOrDefault(rep.getRegionId(), "未知");
            // 这里 orderCount 需要单独查，简化处理用 0（上线前必须补真值，防模型答"0 笔"）
            result.add(new RepSalesDTO(repId, rep.getName(), rep.getRegionId(),
                    regionName, total, 0));

            // raw 本身已降序，凑够 topN 即可提前退出
            if (result.size() >= topN) break;
        }
        return result;
    }

    /**
     * 大区业绩排名
     * <p>业务场景：需求用例"各大区销售额排名"、"各大区销售额占比饼图"（总监视角专属）
     * <p>权限语义：跨区数据，仅 SALES_DIRECTOR 应调用
     * <p>口径：仅 COMPLETED 订单金额合计，按金额降序
     *
     * @param start 起始日期（含）
     * @param end   结束日期（含）
     * @return 大区业绩列表（金额降序），全量大区；orderCount/totalProfit 暂为 0（待单独统计）
     */
    public List<RegionSalesDTO> queryRegionRanking(LocalDate start, LocalDate end) {
        // raw 每行结构为 [regionId(0), 总金额(1)]，已按金额降序
        List<Object[]> raw = orderRepository.findRegionRanking(start, end);
        // 批量补齐大区名，避免循环内逐条查询
        Map<Long, String> regionNameMap = regionRepository.findAll().stream()
                .collect(Collectors.toMap(r -> r.getId(), r -> r.getName()));

        return raw.stream().map(row -> {
            Long regionId = ((Number) row[0]).longValue();
            BigDecimal total = new BigDecimal(row[1].toString());
            // 映射查不到（脏数据）时兜底"未知"
            String regionName = regionNameMap.getOrDefault(regionId, "未知");
            // orderCount/totalProfit 暂为 0 占位（上线前必须补真值）
            return new RegionSalesDTO(regionId, regionName, total, 0, BigDecimal.ZERO);
        }).collect(Collectors.toList());
    }

    /**
     * 产品销售排名
     * <p>业务场景：需求用例"本月 Top 10 产品的柱状图"、"哪个品类最好"（按 category 分组可基于此扩展）
     * <p>权限语义：产品维度按权限收窄——销售员限本人经手、主管限本区、总监全公司；
     * 收窄由调用方传入的 start/end 前置查询保证，本方法不做过滤
     * <p>口径：仅 COMPLETED 订单，按金额降序取前 N；quantity 为销量合计
     *
     * @param start 起始日期（含）
     * @param end   结束日期（含）
     * @param topN  取前几名
     * @return 产品业绩列表（金额降序），含 SKU 编码与品类；最多 topN 条
     */
    public List<ProductSalesDTO> queryProductRanking(LocalDate start, LocalDate end, int topN) {
        // raw 每行结构为 [productId(0), 总金额(1), 总销量(2)]，已按金额降序
        List<Object[]> raw = orderRepository.findProductRanking(start, end);
        // 批量补齐产品信息（SKU 编码、名称、品类），避免 N+1
        Map<Long, com.kyle.salesAgent.entity.Product> productMap = productRepository.findAll().stream()
                .collect(Collectors.toMap(p -> p.getId(), p -> p));

        List<ProductSalesDTO> result = new ArrayList<>();
        for (Object[] row : raw) {
            Long productId = ((Number) row[0]).longValue();
            BigDecimal total = new BigDecimal(row[1].toString());
            Integer qty = ((Number) row[2]).intValue();
            // 产品不存在（数据不一致）时跳过该行
            com.kyle.salesAgent.entity.Product p = productMap.get(productId);
            if (p == null) continue;
            result.add(new ProductSalesDTO(productId, p.getSkuCode(), p.getName(),
                    p.getCategory(), total, qty));
            // 凑够 topN 提前退出
            if (result.size() >= topN) break;
        }
        return result;
    }

    /**
     * 统计在售产品总数
     * <p>用途：产品"最差排名"场景的零销售提示——零销售数 = 在售总数 − 该时段有单产品数
     *（GROUP BY 只返回有单产品，零销售的不在结果里，用减法补出个数）
     *
     * @return status = ACTIVE 的产品数量
     */
    public long countActiveProducts() {
        // SELECT COUNT(...) WHERE status=? 由 Spring Data 方法名推导生成
        return productRepository.countByStatus("ACTIVE");
    }

    /** ============================================================
     * 趋势分析
     * ============================================================ */

    /**
     * 月度趋势数据（近 N 个月）
     * <p>业务场景：需求用例"近 6 个月的月度销售趋势"、"给我画一张近半年的销售趋势折线图"；
     * 也是"哪个月是旺季"（旺季 = 月度销售额 ≥ 月均 1.2 倍）的数据源
     * <p>口径：仅 COMPLETED 订单，按月聚合（month 格式 yyyy-MM）；regionId 为 null 表示全公司
     *
     * @param regionId 大区ID（null 表示全公司；权限收窄由调用方保证）
     * @param months   往回追溯的月数（如 6 表示近 6 个月）
     * @return 按月聚合的销售额与订单数列表；无订单的月份可能缺失（非连续，由调用方/模型补零说明）
     */
    public List<MonthlyTrendDTO> queryMonthlyTrend(Long regionId, int months) {
        // 统计窗口：months 个月前那个月的 1 号 → 今天（含当月，当月数据不完整由调用方说明）
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusMonths(months).withDayOfMonth(1);

        // 原生 SQL 聚合：raw 每行结构为 [月份字符串(0), 总金额(1), 订单数(2)]
        // regionId 传 null 时由 SQL 内的 (:regionId IS NULL OR ...) 分支处理全公司口径
        List<Object[]> raw = orderRepository.findMonthlyTrend(regionId, start, end);
        return raw.stream().map(row -> new MonthlyTrendDTO(
                row[0].toString(),                        // 月份，如 "2026-03"
                new BigDecimal(row[1].toString()),
                ((Number) row[2]).intValue()
        )).collect(Collectors.toList());
    }

    /**
     * 计算环比增长率（当期 vs 上期）
     * <p>业务场景：需求用例"本月销售额比上个月增长了多少"（环比）；
     * 同比（今年 Q4 vs 去年 Q4）复用同一公式，由调用方传入对应两期数据即可
     * <p>公式：(current - previous) / previous × 100%，保留 2 位小数
     *
     * @param current  当期销售额
     * @param previous 上期销售额
     * @return 增长率百分数（如 12.50 表示增长 12.5%，负数表示下降）；
     *         上期为 null 或 0 时返回 null（无法计算，调用方需向模型/用户明确说明，不得编造数字）
     */
    public BigDecimal calcGrowthRate(BigDecimal current, BigDecimal previous) {
        // 上期无数据或为 0 时除法无意义，返回 null 交由上层明确告知"无法计算"
        if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
            return null; // 上期为零，无法计算
        }
        // 先以 4 位小数精度做除法（减少中间精度丢失），再乘 100 转百分数，最后保留 2 位
        return current.subtract(previous)
                .divide(previous, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** ============================================================
     * 异常检测辅助
     * ============================================================ */

    /**
     * 查询产品最后一次出单日期
     * <p>业务场景：异常检测用例"有没有产品连续多天零销售"——
     * 判定规则（需求 4.1-E19）：当前日期 − 最后出单日期 ≥ 7 天即触发断货预警
     *
     * @param productId 产品ID
     * @return 该产品最近一笔订单的下单日期；从未出单返回 null（调用方按"无数据"处理，不等于"零销售 N 天"）
     */
    public LocalDate queryLastOrderDate(Long productId) {
        // MAX(order_date) 即最后出单日；JPQL 内已限定 COMPLETED（退单不算出单）
        return orderRepository.findLastOrderDateByProduct(productId);
    }

    /**
     * 查询大区在指定时段内的订单数
     * <p>业务场景：异常检测用例"最近数据有没有什么异常需要关注"——
     * 大区近 14 天订单数为 0 即触发暴跌预警（测试数据埋点：华北区）；
     * 也为销售额汇总配套返回订单数（"本月卖了多少 + 多少单"）
     * <p>口径：仅 COMPLETED 订单
     *
     * @param regionId 大区ID（传 null 表示全公司）
     * @param start    起始日期（含）
     * @param end      结束日期（含）
     * @return 完成订单数；无数据返回 0
     */
    public Long queryOrderCount(Long regionId, LocalDate start, LocalDate end) {
        // JPQL 端已完成状态与日期过滤，直接返回计数；
        // regionId 为 null 时 (:regionId IS NULL OR ...) 条件自动失效（全公司）
        return orderRepository.countCompletedByRegion(regionId, start, end);
    }

    /**
     * 查询所有销售员退单率
     * <p>业务场景：异常检测用例"退单率异常高的有哪些"——
     * 判定规则（需求 4.1-E20）：近 30 天退单率 ≥ 全公司均值 + 2 倍标准差，或绝对值 ≥ 15%
     * <p>口径：退单率 = REFUNDED 订单数 ÷ 该销售员订单总数（时段内，分母含 CANCELLED）
     * <p>权限语义：结果包含全公司销售员，调用方需按当前用户权限过滤后再返回
     *（销售员只能看自己、主管限本区、总监全量）
     *
     * @param start 起始日期（含）
     * @param end   结束日期（含）
     * @return 每行为 [repId, 退单数(refunded), 总单数(total)]，退单率 = refunded ÷ total，调用方自行计算
     */
    public List<Object[]> queryRefundRates(LocalDate start, LocalDate end) {
        // SQL 端只做分组统计（CASE WHEN 数退单），退单率的具体计算留给调用方
        return orderRepository.findRefundRateByRep(start, end);
    }

    /** ============================================================
     * 辅助查询（名称解析）
     * ============================================================ */

    /**
     * 根据销售员ID查姓名
     * <p>用途：模型调用工具时传的是 repId，组装自然语言回答时需要把 ID 翻译成姓名
     *
     * @param repId 销售员ID
     * @return 姓名；ID 不存在时返回"未知销售员"（不返回 null，避免模型读到空值）
     */
    public String getRepName(Long repId) {
        // Optional 链式处理：有值取姓名，无值兜底占位文案
        return repRepository.findById(repId)
                .map(SalesRep::getName)
                .orElse("未知销售员");
    }

    /**
     * 根据大区ID查大区名
     * <p>用途：同 {@link #getRepName}，ID → 名称翻译
     *
     * @param regionId 大区ID
     * @return 大区名；ID 不存在时返回"未知大区"
     */
    public String getRegionName(Long regionId) {
        return regionRepository.findById(regionId)
                .map(r -> r.getName())
                .orElse("未知大区");
    }

    /**
     * 根据大区名称查大区ID
     * <p>用途：用户问"华东区怎么样"——模型从问题中提取的是名称"华东区"，
     * 而订单表的过滤字段是 regionId，需要这一步名称 → ID 的翻译
     * <p>注意：返回 null 表示该名称不存在（对应架构 4.4 的 NOT_FOUND 语义，
     * 调用方应让模型告知"查无此大区"，而不是当成"无数据"）
     *
     * @param regionName 大区名称（如"华东区"，需与库中 name 完全一致）
     * @return 大区ID；名称不存在时返回 null
     */
    public Long getRegionIdByName(String regionName) {
        // 命中返回 ID；未命中（NOT_FOUND 语义）返回 null，由调用方区分处理
        return regionRepository.findByName(regionName)
                .map(r -> r.getId())
                .orElse(null);
    }

    /**
     * 根据销售员姓名查销售员ID
     * <p>用途：同 {@link #getRegionIdByName}，用户问题中的"张磊"等姓名 → repId
     * <p>注意：返回 null 表示查无此人（NOT_FOUND 语义）；重名场景当前按第一条处理，
     * 上线前如出现重名需在工具层让模型向用户确认
     *
     * @param repName 销售员姓名（需与库中 name 完全一致）
     * @return 销售员ID；不存在时返回 null
     */
    public Long getRepIdByName(String repName) {
        return repRepository.findByName(repName)
                .map(SalesRep::getId)
                .orElse(null);
    }

}
