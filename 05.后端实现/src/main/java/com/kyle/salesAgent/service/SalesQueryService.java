package com.kyle.salesAgent.service;

import com.kyle.salesAgent.dto.MonthlyTrendDTO;
import com.kyle.salesAgent.dto.ProductSalesDTO;
import com.kyle.salesAgent.dto.RegionSalesDTO;
import com.kyle.salesAgent.dto.RepSalesDTO;
import com.kyle.salesAgent.entity.SalesOrder;
import com.kyle.salesAgent.entity.Product;
import com.kyle.salesAgent.entity.SalesRegion;
import com.kyle.salesAgent.entity.SalesRep;
import com.kyle.salesAgent.exception.PermissionDeniedException;
import com.kyle.salesAgent.repository.ProductRepository;
import com.kyle.salesAgent.repository.SalesOrderRepository;
import com.kyle.salesAgent.repository.SalesRegionRepository;
import com.kyle.salesAgent.repository.SalesRepRepository;
import com.kyle.salesAgent.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
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
 * <p><b>权限注入（架构 4.2 强制层，已落地）</b>：每个查询方法入口通过 {@link UserContext}
 * 取当前用户身份并收敛查询范围——取不到身份直接拒绝（fail-closed）；销售员强制按本人
 * （repId 覆盖）、主管强制按本区（regionId 覆盖）、总监放行。模型/工具传入的范围参数
 * 只是"查询意图"，越出权限范围时抛 {@link PermissionDeniedException}（消息透传给模型拒答），
 * <b>绝不静默替换范围</b>——那会产生"标题写着 A、数据是 B"的冒名数据。
 * <p><b>指标口径</b>（对齐需求文档第 2 节）：销售额均为毛额口径，仅统计
 * status = COMPLETED 的订单，按 order_date 归属统计周期。
 * <p><b>缓存</b>：只读排名/汇总/趋势走 @Cacheable（分区 TTL 见 RedisConfig）；
 * Key 末尾统一拼 {@link UserContext#cacheScopeTag()}（角色|大区|人），
 * 不同角色/大区即使问法相同也不会互相命中（架构 4.3）。本系统只读、无写路径，
 * 缓存靠 TTL 自然过期，不需要 @CacheEvict。
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
    // 权限范围收敛（每个查询方法入口调用；fail-closed 的兜底就在 requireUser）
    // ============================================================

    /**
     * 取当前用户身份；取不到必须拒绝查询，绝不允许"无过滤查询"。
     * 触发场景：SSE 流式跨线程 ThreadLocal 丢失、未登录直调等。
     */
    private UserContext.UserInfo requireUser() {
        UserContext.UserInfo user = UserContext.get();
        if (user == null || !user.hasValidScope()) {
            throw new PermissionDeniedException("未获取到用户身份，拒绝查询");
        }
        return user;
    }

    /**
     * 按大区查询的范围收敛：销售员拒绝（应走按人方法）；主管强制本区——
     * 传了别区拒绝，没传填本区；总监原样放行。
     */
    private Long resolveRegionScope(UserContext.UserInfo user, Long regionId) {
        if (user.isRep()) {
            throw new PermissionDeniedException("销售员无权按大区查询，请查询自己的业绩数据");
        }
        if (user.isManager()) {
            if (regionId != null && !regionId.equals(user.regionId())) {
                throw new PermissionDeniedException("您只能查询本大区数据");
            }
            regionId = user.regionId();
        }
        return regionId;
    }

    /**
     * 按人查询的范围收敛：销售员强制本人（传别人拒绝）；主管校验本区归属；
     * 解析后的 repId 仍为空一律拒绝（身份缺关联也是安全风险）。
     */
    private Long resolveRepScope(UserContext.UserInfo user, Long repId) {
        if (user.isRep()) {
            if (repId != null && !repId.equals(user.repId())) {
                throw new PermissionDeniedException("您只能查询自己的业绩数据");
            }
            repId = user.repId();
            if (repId == null) {
                throw new PermissionDeniedException("当前账号未关联销售员身份，拒绝查询");
            }
        } else if (user.isManager()) {
            if (repId == null) {
                throw new PermissionDeniedException("请指定要查询的销售员");
            }
            if (!repBelongsToRegion(repId, user.regionId())) {
                throw new PermissionDeniedException("该销售员不在您的大区，无权查询");
            }
        }
        if (repId == null) {
            // 总监场景工具层保证传了姓名，这里是防御兜底
            throw new PermissionDeniedException("请指定要查询的销售员");
        }
        return repId;
    }

    /** "人 + 区"同时存在的明细/趋势/产品类查询的范围收敛结果。 */
    private record QueryScope(Long repId, Long regionId) {}

    /**
     * 明细/趋势/产品类查询的范围收敛：销售员只看自己（带大区条件即拒绝）；
     * 主管限本区（repId 属于本区才放行）；总监原样。
     */
    private QueryScope resolveQueryScope(UserContext.UserInfo user, Long repId, Long regionId) {
        if (user.isRep()) {
            if (regionId != null) {
                throw new PermissionDeniedException("销售员只能查询自己的数据，无权按大区查询");
            }
            if (repId != null && !repId.equals(user.repId())) {
                throw new PermissionDeniedException("您只能查询自己的数据");
            }
            if (user.repId() == null) {
                throw new PermissionDeniedException("当前账号未关联销售员身份，拒绝查询");
            }
            return new QueryScope(user.repId(), null);
        }
        if (user.isManager()) {
            if (repId != null && !repBelongsToRegion(repId, user.regionId())) {
                throw new PermissionDeniedException("该销售员不在您的大区，无权查询");
            }
            if (regionId != null && !regionId.equals(user.regionId())) {
                throw new PermissionDeniedException("您只能查询本大区数据");
            }
            return new QueryScope(repId, user.regionId());
        }
        return new QueryScope(repId, regionId);
    }

    // ============================================================
    // 基础查询
    // ============================================================

    /**
     * 查询指定时段的订单列表（原始明细）
     * <p>业务场景：需求 4.1-A 类用例，如"上个月华东区的所有订单有哪些"、"张磊这个月成交了哪几单"
     * <p>权限语义：入口先做范围收敛（{@link #resolveQueryScope}）——销售员只能查自己
     * （带大区条件即拒绝）、主管限本区、总监放行；传入参数越出范围直接拒绝，不静默替换
     * <p>口径：包含全部状态（COMPLETED/REFUNDED/CANCELLED），明细场景退单也要能看到
     *
     * @param repId    销售员ID（按人过滤的查询意图）
     * @param regionId 大区ID（按区过滤的查询意图）
     * @param start    起始日期（含）
     * @param end      结束日期（含）
     * @return 订单明细列表，按查询条件的日期区间过滤；无数据返回空列表
     */
    public List<SalesOrder> queryOrders(Long repId, Long regionId,
                                        LocalDate start, LocalDate end) {
        // 范围收敛统一在 resolveQueryScope：fail-closed + 按角色强制覆盖 + 越权拒绝。
        // 注意：必须在"原始入参"上做校验——任何先改参再校验的写法都会把越权意图抹掉
        QueryScope scope = resolveQueryScope(requireUser(), repId, regionId);
        repId = scope.repId();
        regionId = scope.regionId();
        if (repId != null && regionId != null) {
            // 两个筛选条件同时存在时取交集，不能悄悄忽略大区。
            return orderRepository.findByRepIdAndRegionIdAndOrderDateBetween(repId, regionId, start, end);
        }
        // 范围从窄到宽依次判断：先按人 → 再按区 → 最后全量
        if (repId != null) {
            // 销售员角色：只查本人订单（最窄范围，优先命中）
            return orderRepository.findByRepIdAndOrderDateBetween(repId, start, end);
        }
        if (regionId != null) {
            // 主管角色：查本大区所有人的订单
            return orderRepository.findByRegionIdAndOrderDateBetween(regionId, start, end);
        }
        // 全公司明细：由数据库按日期闭区间过滤，不把全表订单加载到应用内存。
        return orderRepository.findByOrderDateBetween(start, end);
    }

    /**
     * 查询指定范围的总销售额
     * <p>业务场景：需求 4.1-B 类用例，如"本月销售额是多少"、"华东区这个月卖了多少"
     * <p>权限语义：入口先做范围收敛——销售员拒绝（汇总应走按人方法 {@link #queryRepTotalAmount}，
     * 避免无主体问题被当成全公司口径）；主管强制本区；总监放行
     * <p>口径：毛额，仅 status = COMPLETED 的订单金额合计
     *
     * @param regionId 大区ID（null 表示全公司，仅总监语义）
     * @param start    起始日期（含）
     * @param end      结束日期（含）
     * @return 总销售额；无符合条件订单时返回 0（COALESCE 兜底，不返回 null）
     */
    @Cacheable(value = "sales-summary",
            key = "'total-amount_' + (#regionId == null ? 'all' : #regionId.toString()) + '_' + #start.toString() + '_' + #end.toString()" +
                    " + '_' + T(com.kyle.salesAgent.security.UserContext).cacheScopeTag()")
    public BigDecimal queryTotalAmount(Long regionId, LocalDate start, LocalDate end) {
        regionId = resolveRegionScope(requireUser(), regionId);
        if (regionId != null) {
            // 按区路径：JPQL 在数据库端完成过滤与聚合（含 status='COMPLETED'，口径与全公司路径一致）
            return orderRepository.sumAmountByRegion(regionId, start, end);
        }
        // 全公司路径：数据库完成状态过滤和聚合，避免加载全部订单。
        return orderRepository.sumAmountAll(start, end);
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
    @Cacheable(value = "sales-summary",
            key = "'rep-amount_' + (#repId == null ? 'self' : #repId.toString()) + '_' + #start.toString() + '_' + #end.toString()" +
                    " + '_' + T(com.kyle.salesAgent.security.UserContext).cacheScopeTag()")
    public BigDecimal queryRepTotalAmount(Long repId, LocalDate start, LocalDate end) {
        // 按人路径：先收敛范围（销售员强制本人、主管校验本区归属），再在数据库端过滤聚合
        repId = resolveRepScope(requireUser(), repId);
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
    @Cacheable(value = "sales-summary",
            key = "'rep-count_' + (#repId == null ? 'self' : #repId.toString()) + '_' + #start.toString() + '_' + #end.toString()" +
                    " + '_' + T(com.kyle.salesAgent.security.UserContext).cacheScopeTag()")
    public Long queryRepOrderCount(Long repId, LocalDate start, LocalDate end) {
        repId = resolveRepScope(requireUser(), repId);
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
     * @return 销售员业绩列表（金额降序），最多 topN 条；订单数由同一聚合查询返回
     */
    @Cacheable(value = "rep-ranking",
            key = "'v2_' + (#regionId == null ? 'all' : #regionId.toString()) + '_' + #start.toString() + '_' + #end.toString() + '_' + #topN" +
                    " + '_' + T(com.kyle.salesAgent.security.UserContext).cacheScopeTag()")
    public List<RepSalesDTO> queryRepRanking(Long regionId, LocalDate start, LocalDate end, int topN) {
        UserContext.UserInfo user = requireUser();
        if (user.isRep()) {
            // 需求 4.4 矩阵：按人排名对销售员无意义（只能看自己），直接拒绝而非收窄
            throw new PermissionDeniedException("销售员无权查看销售员排名，请查询自己的业绩数据");
        }
        regionId = resolveRegionScope(user, regionId);
        // topN 在 Service 层兜底：上游忘 clamp 时防 0/负数导致空结果被误答成"暂无数据"
        topN = Math.max(1, Math.min(topN, 50));

        // 聚合查询：raw 每行结构为 [repId(0), 总金额(1), 订单数(2)]，已按金额降序；
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
            int orderCount = ((Number) row[2]).intValue();
            // 销售员不存在（数据不一致）时跳过该行，避免 NPE
            SalesRep rep = repMap.get(repId);
            if (rep == null) continue;

            // 补齐大区名；映射查不到（脏数据）时兜底"未知"而非 null
            String regionName = regionNameMap.getOrDefault(rep.getRegionId(), "未知");
            result.add(new RepSalesDTO(repId, rep.getName(), rep.getRegionId(),
                    regionName, total, orderCount));

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
     * @return 大区业绩列表（金额降序），订单数和毛利由同一聚合查询返回
     */
    @Cacheable(value = "region-ranking",
            key = "'v2_' + #start.toString() + '_' + #end.toString()" +
                    " + '_' + T(com.kyle.salesAgent.security.UserContext).cacheScopeTag()")
    public List<RegionSalesDTO> queryRegionRanking(LocalDate start, LocalDate end) {
        // 需求 4.4 矩阵：跨区对比是总监专属，主管/销售员直接拒绝（不是收窄）
        if (!requireUser().isDirector()) {
            throw new PermissionDeniedException("各大区业绩对比仅销售总监可查看");
        }
        // raw 每行结构为 [regionId(0), 总金额(1), 订单数(2), 毛利(3)]，已按金额降序
        List<Object[]> raw = orderRepository.findRegionRanking(start, end);
        // 批量补齐大区名，避免循环内逐条查询
        Map<Long, String> regionNameMap = regionRepository.findAll().stream()
                .collect(Collectors.toMap(r -> r.getId(), r -> r.getName()));

        return raw.stream().map(row -> {
            Long regionId = ((Number) row[0]).longValue();
            BigDecimal total = new BigDecimal(row[1].toString());
            int orderCount = ((Number) row[2]).intValue();
            BigDecimal totalProfit = new BigDecimal(row[3].toString());
            // 映射查不到（脏数据）时兜底"未知"
            String regionName = regionNameMap.getOrDefault(regionId, "未知");
            return new RegionSalesDTO(regionId, regionName, total, orderCount, totalProfit);
        }).collect(Collectors.toList());
    }

    /**
     * 产品销售排名
     * <p>业务场景：需求用例"本月 Top 10 产品的柱状图"、"哪个品类最好"（按 category 分组可基于此扩展）
     * <p>权限语义：入口先做范围收敛——销售员收窄为本人经手、主管限本区、总监全公司；
     * 传入范围越出权限直接拒绝
     * <p>口径：仅 COMPLETED 订单，按金额降序取前 N；quantity 为销量合计
     *
     * @param regionId 大区ID（查询意图，越出权限会被拒绝）
     * @param repId    销售员ID（查询意图）
     * @param start    起始日期（含）
     * @param end      结束日期（含）
     * @param topN     取前几名
     * @return 产品业绩列表（金额降序），含 SKU 编码与品类；最多 topN 条
     */
    @Cacheable(value = "product-ranking",
            key = "(#regionId == null ? 'all' : #regionId.toString()) + '_' + (#repId == null ? 'all' : #repId.toString()) + '_' + #start.toString() + '_' + #end.toString() + '_' + #topN" +
                    " + '_' + T(com.kyle.salesAgent.security.UserContext).cacheScopeTag()")
    public List<ProductSalesDTO> queryProductRanking(Long regionId, Long repId,
                                                     LocalDate start, LocalDate end, int topN) {
        QueryScope scope = resolveQueryScope(requireUser(), repId, regionId);
        repId = scope.repId();
        regionId = scope.regionId();
        topN = Math.max(1, Math.min(topN, 50));

        // raw 每行结构为 [productId(0), 总金额(1), 总销量(2)]，已按金额降序
        List<Object[]> raw = orderRepository.findProductRanking(regionId, repId, start, end);
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
     * <p>口径：仅 COMPLETED 订单，按月聚合（month 格式 yyyy-MM）
     * <p>权限语义：入口先做范围收敛——销售员收窄为本人（regionId 条件拒绝）、主管限本区、总监放行
     *
     * @param regionId 大区ID（查询意图）
     * @param repId    销售员ID（查询意图；销售员角色强制覆盖为本人）
     * @param months   往回追溯的月数（如 6 表示近 6 个月）
     * @return 按月聚合的销售额与订单数列表；无订单的月份可能缺失（非连续，由调用方/模型补零说明）
     */
    @Cacheable(value = "monthly-trend",
            key = "(#regionId == null ? 'all' : #regionId.toString()) + '_' + (#repId == null ? 'all' : #repId.toString()) + '_' + #months" +
                    " + '_' + T(java.time.LocalDate).now()" +
                    " + '_' + T(com.kyle.salesAgent.security.UserContext).cacheScopeTag()")
    public List<MonthlyTrendDTO> queryMonthlyTrend(Long regionId, Long repId, int months) {
        QueryScope scope = resolveQueryScope(requireUser(), repId, regionId);
        repId = scope.repId();
        regionId = scope.regionId();

        // 统计窗口：含当月共 months 个自然月；例如 6 表示当月加之前 5 个月。
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusMonths(Math.max(months, 1) - 1L).withDayOfMonth(1);

        // 原生 SQL 聚合：raw 每行结构为 [月份字符串(0), 总金额(1), 订单数(2)]
        // regionId/repId 传 null 时由 SQL 内的 (:xx IS NULL OR ...) 分支处理全公司口径
        List<Object[]> raw = orderRepository.findMonthlyTrend(regionId, repId, start, end);
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

    /** 异常检测的大区集合：主管只拿到本区，销售员无权（调用方 AnomalyDetectionTool 入口已拦）。 */
    public List<SalesRegion> queryAnomalyRegions() {
        UserContext.UserInfo user = requireUser();
        if (user.isRep()) {
            throw new PermissionDeniedException("销售员无权执行大区维度的异常检测");
        }
        if (user.isManager()) {
            return regionRepository.findById(user.regionId()).stream().collect(Collectors.toList());
        }
        return regionRepository.findAll();
    }

    /**
     * 异常检测的销售员集合，只含普通销售员（主管不背个人业绩）。
     * 主管只拿到本区销售员；销售员无权看他人异常（工具入口已拦，这里防御）。
     */
    public List<SalesRep> queryAnomalyReps() {
        UserContext.UserInfo user = requireUser();
        if (user.isRep()) {
            throw new PermissionDeniedException("销售员无权查看他人的业绩异常");
        }
        if (user.isManager()) {
            return repRepository.findByRoleAndRegionId("SALES_REP", user.regionId());
        }
        return repRepository.findByRole("SALES_REP");
    }

    /** 仅检测当前在售产品（全局目录）；主管的"本区断销"由 {@link #queryLastOrderDates} 按 regionId 收窄。 */
    public List<Product> queryAnomalyProducts() {
        requireUser();
        return productRepository.findByStatus("ACTIVE");
    }

    /** 一次聚合各产品截至 end（含）的最近已完成订单日期；主管限本区，总监全公司。 */
    public Map<Long, LocalDate> queryLastOrderDates(Long regionId, LocalDate end) {
        regionId = resolveRegionScope(requireUser(), regionId);
        return orderRepository.findLastOrderDates(regionId, end).stream().collect(Collectors.toMap(
                row -> ((Number) row[0]).longValue(), row -> (LocalDate) row[1]));
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
        // 先收敛范围（主管强制本区），再计数；regionId 为 null 时 (:regionId IS NULL OR ...) 条件自动失效（全公司）
        regionId = resolveRegionScope(requireUser(), regionId);
        return orderRepository.countCompletedByRegion(regionId, start, end);
    }

    /**
     * 查询所有销售员退单率
     * <p>业务场景：异常检测用例"退单率异常高的有哪些"——
     * 判定规则（需求 4.1-E20）：近 30 天退单率 ≥ 全公司均值 + 2 倍标准差，或绝对值 ≥ 15%
     * <p>口径：退单率 = REFUNDED 订单数 ÷ 该销售员订单总数（时段内，分母含 CANCELLED）
     * <p>权限语义：按角色过滤返回行——销售员只保留自己一行、主管只保留本区销售员的行、
     * 总监返回全量（raw 里只有 repId，借用户表映射大区后过滤）
     *
     * @param start 起始日期（含）
     * @param end   结束日期（含）
     * @return 每行为 [repId, 退单数(refunded), 总单数(total)]，退单率 = refunded ÷ total，调用方自行计算
     */
    public List<Object[]> queryRefundRates(LocalDate start, LocalDate end) {
        UserContext.UserInfo user = requireUser();
        List<Object[]> all = orderRepository.findRefundRateByRep(start, end);
        if (user.isDirector()) {
            return all;
        }
        // raw 里只有 repId，借用户表建 repId → regionId 映射后按角色过滤行
        Map<Long, Long> repRegionMap = repRepository.findAll().stream()
                .collect(Collectors.toMap(SalesRep::getId, SalesRep::getRegionId));
        return all.stream().filter(row -> {
            Long rowRepId = ((Number) row[0]).longValue();
            return user.isRep()
                    ? rowRepId.equals(user.repId())
                    : user.regionId().equals(repRegionMap.get(rowRepId));
        }).collect(Collectors.toList());
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

    /** 同时指定销售员和大区时，校验销售员当前所属大区是否一致。 */
    public boolean repBelongsToRegion(Long repId, Long regionId) {
        return repRepository.findById(repId)
                .map(rep -> regionId.equals(rep.getRegionId()))
                .orElse(false);
    }

}
