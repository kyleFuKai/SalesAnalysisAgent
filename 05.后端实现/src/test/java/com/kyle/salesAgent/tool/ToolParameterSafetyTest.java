package com.kyle.salesAgent.tool;

import com.kyle.salesAgent.entity.SalesOrder;
import com.kyle.salesAgent.service.SalesQueryService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/** 工具入口的可选参数与组合筛选边界测试，不连接数据库或模型。 */
class ToolParameterSafetyTest {

    @Test
    void literalNullComparisonDatesAreAutoCalculated() {
        SalesQueryService service = mock(SalesQueryService.class);
        when(service.queryTotalAmount(null, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
                .thenReturn(BigDecimal.valueOf(100));
        when(service.queryTotalAmount(null, LocalDate.of(2026, 5, 31), LocalDate.of(2026, 6, 30)))
                .thenReturn(BigDecimal.valueOf(50));

        String result = new SalesTrendTool(null, service, new ToolInputValidator()).calcMonthOverMonth(
                "2026-07-01", "2026-07-31", "null", " NULL ", "");

        assertTrue(result.contains("2026-05-31 至 2026-06-30"));
        verify(service, times(2)).queryTotalAmount(any(), any(), any());
    }

    @Test
    void comparisonDatesMustBeSuppliedTogether() {
        SalesQueryService service = mock(SalesQueryService.class);
        String result = new SalesTrendTool(null, service, new ToolInputValidator()).calcMonthOverMonth(
                "2026-07-01", "2026-07-31", "null", "2026-06-30", "");

        assertTrue(result.contains("同时传 prevStart 和 prevEnd"));
        verifyNoInteractions(service);
    }

    @Test
    void reversedDateRangeIsRejectedBeforeQuery() {
        // 安全自检第 3 项：开始晚于结束必须拦，否则静默空集会被模型答成"没有数据"
        SalesQueryService service = mock(SalesQueryService.class);
        String result = new SalesQueryTool(service, new ToolInputValidator(), null).queryOrders(
                "2026-07-31", "2026-07-01", "", "", 20);

        assertTrue(result.contains("开始日期不能晚于结束日期"));
        verifyNoInteractions(service);
    }

    @Test
    void mismatchedRepAndRegionDoNotQueryOrders() {
        SalesQueryService service = mock(SalesQueryService.class);
        when(service.getRegionIdByName("华东区")).thenReturn(1L);
        when(service.getRepIdByName("张磊")).thenReturn(8L);
        when(service.repBelongsToRegion(8L, 1L)).thenReturn(false);

        String result = new SalesQueryTool(service, new ToolInputValidator(), null).queryOrders(
                "2026-07-01", "2026-07-31", "华东区", "张磊", 20);

        assertTrue(result.contains("张磊 不属于 华东区"));
        verify(service, never()).queryOrders(any(), any(), any(), any());
    }

    @Test
    void mismatchedRepAndRegionDoNotQuerySummary() {
        SalesQueryService service = mock(SalesQueryService.class);
        when(service.getRegionIdByName("华东区")).thenReturn(1L);
        when(service.getRepIdByName("张磊")).thenReturn(8L);
        when(service.repBelongsToRegion(8L, 1L)).thenReturn(false);

        String result = new SalesSummaryTool(null, service, new ToolInputValidator()).getSalesSummary(
                "2026-07-01", "2026-07-31", "华东区", "张磊");

        assertTrue(result.contains("张磊 不属于 华东区"));
        verify(service, never()).queryRepTotalAmount(any(), any(), any());
    }

    @Test
    void nonPositiveLimitUsesDefaultTwenty() {
        SalesQueryService service = mock(SalesQueryService.class);
        SalesOrder order = mock(SalesOrder.class);
        when(order.getRepId()).thenReturn(8L);
        when(order.getOrderNo()).thenReturn("ORD-1");
        when(order.getOrderDate()).thenReturn(LocalDate.of(2026, 7, 1));
        when(order.getCustomerName()).thenReturn("测试客户");
        when(order.getAmount()).thenReturn(BigDecimal.TEN);
        when(order.getStatus()).thenReturn("COMPLETED");
        when(service.getRepName(8L)).thenReturn("张磊");
        when(service.queryOrders(eq(null), eq(null), any(), any()))
                .thenReturn(Collections.nCopies(21, order));

        String result = new SalesQueryTool(service, new ToolInputValidator(), null).queryOrders(
                "2026-07-01", "2026-07-31", "", "", 0);

        assertTrue(result.contains("以下显示前 20 条"));
    }
}
