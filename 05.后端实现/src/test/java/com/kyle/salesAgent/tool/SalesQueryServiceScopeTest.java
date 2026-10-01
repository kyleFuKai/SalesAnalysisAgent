package com.kyle.salesAgent.tool;

import com.kyle.salesAgent.repository.ProductRepository;
import com.kyle.salesAgent.repository.SalesOrderRepository;
import com.kyle.salesAgent.repository.SalesRegionRepository;
import com.kyle.salesAgent.repository.SalesRepRepository;
import com.kyle.salesAgent.service.SalesQueryService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/** 验证 Service 不会在同时给出销售员与大区时丢失大区筛选条件。 */
class SalesQueryServiceScopeTest {

    @Test
    void bothRepAndRegionUseIntersectedOrderQuery() {
        SalesOrderRepository orders = mock(SalesOrderRepository.class);
        SalesQueryService service = new SalesQueryService(
                orders, mock(SalesRepRepository.class), mock(ProductRepository.class),
                mock(SalesRegionRepository.class));
        LocalDate start = LocalDate.of(2026, 7, 1);
        LocalDate end = LocalDate.of(2026, 7, 31);
        when(orders.findByRepIdAndRegionIdAndOrderDateBetween(8L, 1L, start, end))
                .thenReturn(List.of());

        assertEquals(List.of(), service.queryOrders(8L, 1L, start, end));
        verify(orders).findByRepIdAndRegionIdAndOrderDateBetween(8L, 1L, start, end);
        verify(orders, never()).findByRepIdAndOrderDateBetween(any(), any(), any());
    }
}
