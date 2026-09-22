package com.kyle.salesAgent.tool;

import com.kyle.salesAgent.dto.AnomalyDTO;
import com.kyle.salesAgent.entity.Product;
import com.kyle.salesAgent.entity.SalesRegion;
import com.kyle.salesAgent.entity.SalesRep;
import com.kyle.salesAgent.service.SalesQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * AnomalyDetectionTool 的纯 Mockito 单测，不起 Spring 容器、不连数据库，
 * Service 全部 mock，跑得快且不受云数据库状态影响。
 *
 * 两个测试特有的处理：
 *   @Value 字段不走配置，用 ReflectionTestUtils 手动塞值（阈值天数 5、降幅 0.3）；
 *   私有检测方法用 ReflectionTestUtils.invokeMethod 直调，一个用例盯一条规则。
 *
 * 有两个用例是"防退化"性质：verify(never()) 断言某些慢查询/错误方法没被调用，
 * 改代码时别看到它们碍眼就删——删了之后同样的问题会悄悄回来。
 */
class AnomalyDetectionToolTest {
    private final SalesQueryService service = mock(SalesQueryService.class);
    private final AnomalyDetectionTool tool = new AnomalyDetectionTool(service);
    // 固定"今天"，所有时间窗口都基于它推算，用例里的天数才能写死
    private final LocalDate end = LocalDate.of(2026, 9, 20);

    @BeforeEach
    void configure() {
        ReflectionTestUtils.setField(tool, "zeroSaleThresholdDays", 5);
        ReflectionTestUtils.setField(tool, "trendDropThreshold", 0.3);
    }

    /**
     * 业绩骤降主路径：上期 1 万、本期 0，降幅 100% 应记 HIGH。
     * 同时验证走的是 queryRepTotalAmount 而不是大区那条查询（防复制粘贴串门）。
     */
    @Test
    void detectsRepDropUsingTwoAdjacentThirtyDayWindows() {
        SalesRep rep = mock(SalesRep.class);
        when(rep.getId()).thenReturn(8L);
        when(rep.getName()).thenReturn("张磊");
        when(service.queryAnomalyReps()).thenReturn(List.of(rep));
        when(service.queryRepTotalAmount(8L, end.minusDays(59), end.minusDays(30)))
                .thenReturn(new BigDecimal("10000.00"));
        when(service.queryRepTotalAmount(8L, end.minusDays(29), end))
                .thenReturn(BigDecimal.ZERO);
        List<AnomalyDTO> result = ReflectionTestUtils.invokeMethod(tool, "detectRepPerformanceDrop", end);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().severity()).isEqualTo("HIGH");
        assertThat(result.getFirst().description()).contains("100.00%");
        verify(service, never()).queryTotalAmount(any(), any(), any());
    }

    /**
     * 上期金额为零的销售员直接跳过：没有基准就算不出降幅，
     * 连当期的查询都不该发生（verify never 那行管这个）。
     */
    @Test
    void skipsRepWithoutPositiveBaseline() {
        SalesRep rep = mock(SalesRep.class);
        when(rep.getId()).thenReturn(8L);
        when(service.queryAnomalyReps()).thenReturn(List.of(rep));
        when(service.queryRepTotalAmount(8L, end.minusDays(59), end.minusDays(30)))
                .thenReturn(BigDecimal.ZERO);
        List<AnomalyDTO> result = ReflectionTestUtils.invokeMethod(tool, "detectRepPerformanceDrop", end);
        assertThat(result).isEmpty();
        verify(service, never()).queryRepTotalAmount(8L, end.minusDays(29), end);
    }

    /**
     * 大区骤降的窗口验证：近 14 天 0 单、基准 28 天 4 单（折算两周均值 2），
     * 恰好踩着样本门槛之上，降幅 100% 必须报出来。对应用例里的华北区断单场景。
     */
    @Test
    void regionUsesFourteenDaysAgainstTwentyEightDayBaseline() {
        SalesRegion region = mock(SalesRegion.class);
        when(region.getId()).thenReturn(3L);
        when(service.queryAnomalyRegions()).thenReturn(List.of(region));
        when(service.queryOrderCount(3L, end.minusDays(13), end)).thenReturn(0L);
        when(service.queryOrderCount(3L, end.minusDays(41), end.minusDays(14))).thenReturn(4L);
        List<AnomalyDTO> result = ReflectionTestUtils.invokeMethod(tool, "detectRegionDropAnomalies", end);
        assertThat(result).hasSize(1);
    }

    /**
     * 退单率正好 15% 要算异常（>= 而不是 >，边界不能漏）。
     * 3/20 = 15%，同时验证描述里是按笔数口径、没有"团队平均"这种旧话术。
     */
    @Test
    void refundThresholdIncludesExactlyFifteenPercent() {
        when(service.queryRefundRates(end.minusDays(29), end))
                .thenReturn(java.util.Collections.singletonList(new Object[]{3L, 3L, 20L}));
        when(service.getRepName(3L)).thenReturn("王芳");
        List<AnomalyDTO> result = ReflectionTestUtils.invokeMethod(tool, "detectHighRefundReps", end);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().description()).contains("15.0%").doesNotContain("团队平均");
    }

    /**
     * 断销检测两件事一起验：批量取最近成交日只查一次库（times(1)），
     * 从未成交的产品（Map 里没有的）跳过不算断销。
     * times(1) 同时也是防退化断言——改代码时别退回"每个 SKU 单查一次"的写法。
     */
    @Test
    void zeroSalesUsesBatchDatesAndSkipsNeverSoldProducts() {
        Product sold = mock(Product.class);
        Product neverSold = mock(Product.class);
        when(sold.getId()).thenReturn(6L);
        when(neverSold.getId()).thenReturn(7L);
        when(service.queryAnomalyProducts()).thenReturn(List.of(sold, neverSold));
        when(service.queryLastOrderDates(end)).thenReturn(Map.of(6L, end.minusDays(5)));
        List<AnomalyDTO> result = ReflectionTestUtils.invokeMethod(tool, "detectZeroSaleProducts", end);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().description()).contains("5 天");
        verify(service, times(1)).queryLastOrderDates(end);
    }
}
