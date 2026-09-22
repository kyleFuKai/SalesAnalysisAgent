package com.kyle.salesAgent.repository;

import com.kyle.salesAgent.entity.SalesOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {

    // 批量取截止日（含）之前的最近成交日，避免每个 SKU 各查一次。
    @Query("SELECT o.productId, MAX(o.orderDate) FROM SalesOrder o " +
           "WHERE o.status = 'COMPLETED' AND o.orderDate <= :end GROUP BY o.productId")
    List<Object[]> findLastOrderDates(@Param("end") LocalDate end);

    // 按销售员查
    List<SalesOrder> findByRepIdAndOrderDateBetween(Long repId, LocalDate start, LocalDate end);

    // 按大区查
    List<SalesOrder> findByRegionIdAndOrderDateBetween(Long regionId, LocalDate start, LocalDate end);

    // 按产品查
    List<SalesOrder> findByProductIdAndOrderDateBetween(Long productId, LocalDate start, LocalDate end);

    // 某大区某时段的完成订单总金额
    @Query("SELECT COALESCE(SUM(o.amount), 0) FROM SalesOrder o " +
           "WHERE o.regionId = :regionId AND o.status = 'COMPLETED' " +
           "AND o.orderDate BETWEEN :start AND :end")
    BigDecimal sumAmountByRegion(@Param("regionId") Long regionId,
                                  @Param("start") LocalDate start,
                                  @Param("end") LocalDate end);

    // 某销售员某时段的完成订单总金额
    @Query("SELECT COALESCE(SUM(o.amount), 0) FROM SalesOrder o " +
           "WHERE o.repId = :repId AND o.status = 'COMPLETED' " +
           "AND o.orderDate BETWEEN :start AND :end")
    BigDecimal sumAmountByRep(@Param("repId") Long repId,
                               @Param("start") LocalDate start,
                               @Param("end") LocalDate end);

    // 某销售员某时段的完成订单数（与 sumAmountByRep 配套：金额 + 笔数一起返回）
    @Query("SELECT COUNT(o) FROM SalesOrder o " +
           "WHERE o.repId = :repId AND o.status = 'COMPLETED' " +
           "AND o.orderDate BETWEEN :start AND :end")
    Long countCompletedByRep(@Param("repId") Long repId,
                              @Param("start") LocalDate start,
                              @Param("end") LocalDate end);

    // 各销售员业绩排名（regionId 传 null 表示全公司，与 findMonthlyTrend 同一过滤技巧）
    @Query("SELECT o.repId, SUM(o.amount) AS total FROM SalesOrder o " +
           "WHERE o.status = 'COMPLETED' AND o.orderDate BETWEEN :start AND :end " +
           "AND (:regionId IS NULL OR o.regionId = :regionId) " +
           "GROUP BY o.repId ORDER BY total DESC")
    List<Object[]> findRepRanking(@Param("regionId") Long regionId,
                                   @Param("start") LocalDate start,
                                   @Param("end") LocalDate end);

    // 各大区业绩排名
    @Query("SELECT o.regionId, SUM(o.amount) AS total FROM SalesOrder o " +
           "WHERE o.status = 'COMPLETED' AND o.orderDate BETWEEN :start AND :end " +
           "GROUP BY o.regionId ORDER BY total DESC")
    List<Object[]> findRegionRanking(@Param("start") LocalDate start,
                                      @Param("end") LocalDate end);

    // 各产品销售排名
    @Query("SELECT o.productId, SUM(o.amount) AS total, SUM(o.quantity) AS qty " +
           "FROM SalesOrder o WHERE o.status = 'COMPLETED' " +
           "AND o.orderDate BETWEEN :start AND :end " +
           "GROUP BY o.productId ORDER BY total DESC")
    List<Object[]> findProductRanking(@Param("start") LocalDate start,
                                       @Param("end") LocalDate end);

    // 月度汇总（用于趋势分析）
    @Query(value = "SELECT DATE_FORMAT(order_date, '%Y-%m') AS month, " +
                   "SUM(amount) AS total, COUNT(*) AS order_count " +
                   "FROM sa_sales_order WHERE status = 'COMPLETED' " +
                   "AND (:regionId IS NULL OR region_id = :regionId) " +
                   "AND order_date BETWEEN :start AND :end " +
                   "GROUP BY month ORDER BY month",
           nativeQuery = true)
    List<Object[]> findMonthlyTrend(@Param("regionId") Long regionId,
                                     @Param("start") LocalDate start,
                                     @Param("end") LocalDate end);

    // 某销售员的退单率统计
    @Query("SELECT o.repId, " +
           "SUM(CASE WHEN o.status = 'REFUNDED' THEN 1 ELSE 0 END) AS refunded, " +
           "COUNT(*) AS total " +
           "FROM SalesOrder o WHERE o.orderDate BETWEEN :start AND :end " +
           "GROUP BY o.repId")
    List<Object[]> findRefundRateByRep(@Param("start") LocalDate start,
                                        @Param("end") LocalDate end);

    // 某时段完成订单数（regionId 传 null 表示全公司；异常检测与销售额汇总共用）
    @Query("SELECT COUNT(o) FROM SalesOrder o " +
           "WHERE o.status = 'COMPLETED' AND o.orderDate BETWEEN :start AND :end " +
           "AND (:regionId IS NULL OR o.regionId = :regionId)")
    Long countCompletedByRegion(@Param("regionId") Long regionId,
                                 @Param("start") LocalDate start,
                                 @Param("end") LocalDate end);
}
