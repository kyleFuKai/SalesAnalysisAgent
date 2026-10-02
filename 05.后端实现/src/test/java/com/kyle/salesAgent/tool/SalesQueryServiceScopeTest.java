package com.kyle.salesAgent.tool;

import com.kyle.salesAgent.entity.SalesRep;
import com.kyle.salesAgent.exception.PermissionDeniedException;
import com.kyle.salesAgent.repository.ProductRepository;
import com.kyle.salesAgent.repository.SalesOrderRepository;
import com.kyle.salesAgent.repository.SalesRegionRepository;
import com.kyle.salesAgent.repository.SalesRepRepository;
import com.kyle.salesAgent.security.UserContext;
import com.kyle.salesAgent.service.SalesQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * Service 层权限范围收敛测试（架构 4.2 强制层）。
 * 覆盖：fail-closed、销售员限本人、主管限本区、越权拒绝（不静默替换）。
 * 不连数据库：Repository 全部 mock，UserContext 直接写入测试身份。
 */
class SalesQueryServiceScopeTest {

    private static final LocalDate START = LocalDate.of(2026, 7, 1);
    private static final LocalDate END = LocalDate.of(2026, 7, 31);

    private SalesOrderRepository orders;
    private SalesRepRepository reps;
    private SalesQueryService service;

    @BeforeEach
    void setUp() {
        orders = mock(SalesOrderRepository.class);
        reps = mock(SalesRepRepository.class);
        service = new SalesQueryService(orders, reps,
                mock(ProductRepository.class), mock(SalesRegionRepository.class));
    }

    @AfterEach
    void tearDown() {
        // 测试线程会被复用，身份必须清掉，和生产代码同一个纪律
        UserContext.clear();
    }

    private void loginManager() {
        // 李明：华东区主管（regionId=1）
        UserContext.set(new UserContext.UserInfo(1L, "李明", "SALES_MANAGER", 1L, 1L));
    }

    private void loginRep() {
        // 张伟：华东区销售员（repId=2）
        UserContext.set(new UserContext.UserInfo(2L, "张伟", "SALES_REP", 1L, 2L));
    }

    private void loginDirector() {
        UserContext.set(new UserContext.UserInfo(13L, "黄总", "SALES_DIRECTOR", 1L, null));
    }

    private SalesRep repInRegion(long id, long regionId) {
        SalesRep rep = new SalesRep();
        rep.setId(id);
        rep.setRegionId(regionId);
        return rep;
    }

    @Test
    void missingIdentityIsRejected() {
        // fail-closed：取不到身份必须拒绝，绝不能降级成"全公司查询"
        assertThrows(PermissionDeniedException.class,
                () -> service.queryOrders(null, null, START, END));
        verifyNoInteractions(orders);
    }

    @Test
    void managerRepAndRegionUseIntersectedOrderQuery() {
        loginManager();
        // 本区销售员张伟（id=2 属华东区 1）+ 本区条件 → 人 + 区交集查询
        when(reps.findById(2L)).thenReturn(Optional.of(repInRegion(2L, 1L)));
        when(orders.findByRepIdAndRegionIdAndOrderDateBetween(2L, 1L, START, END))
                .thenReturn(List.of());

        assertEquals(List.of(), service.queryOrders(2L, 1L, START, END));
        verify(orders).findByRepIdAndRegionIdAndOrderDateBetween(2L, 1L, START, END);
        verify(orders, never()).findByRepIdAndOrderDateBetween(any(), any(), any());
    }

    @Test
    void managerCrossRegionIsRejected() {
        loginManager();
        // 李明查华南区：直接拒绝，而不是静默换成华东区（否则就是"标题 A 数据 B"）
        assertThrows(PermissionDeniedException.class,
                () -> service.queryOrders(null, 2L, START, END));
        verifyNoInteractions(orders);
    }

    @Test
    void managerRepOutsideRegionIsRejected() {
        loginManager();
        // 张磊 id=8 属华北区(3)，不在李明的大区
        when(reps.findById(8L)).thenReturn(Optional.of(repInRegion(8L, 3L)));
        assertThrows(PermissionDeniedException.class,
                () -> service.queryOrders(8L, null, START, END));
        verify(orders, never()).findByRepIdAndOrderDateBetween(any(), any(), any());
    }

    @Test
    void salesRepAutoNarrowedToOwnOrders() {
        loginRep();
        // 销售员不传条件 → 自动收窄为本人（需求 4.4 通用收窄规则），不能落进全量分支
        when(orders.findByRepIdAndOrderDateBetween(2L, START, END)).thenReturn(List.of());
        assertEquals(List.of(), service.queryOrders(null, null, START, END));
        verify(orders).findByRepIdAndOrderDateBetween(2L, START, END);
        verify(orders, never()).findAll();
    }

    @Test
    void salesRepCannotQueryOthers() {
        loginRep();
        // 张伟查张磊：拒绝，绝不静默替换成自己的数据
        assertThrows(PermissionDeniedException.class,
                () -> service.queryOrders(8L, null, START, END));
        verifyNoInteractions(orders);
    }

    @Test
    void salesRepRegionConditionIsRejected() {
        loginRep();
        // 销售员带大区条件（哪怕是自己所在区）→ 按需求 4.4"本区维度分析"拒绝
        assertThrows(PermissionDeniedException.class,
                () -> service.queryOrders(null, 1L, START, END));
        verifyNoInteractions(orders);
    }

    @Test
    void salesRepWithoutRepIdBindingIsRejected() {
        // 账号没关联销售员身份：放行会落到全量分支，必须 fail-closed
        UserContext.set(new UserContext.UserInfo(99L, "无关联", "SALES_REP", 1L, null));
        assertThrows(PermissionDeniedException.class,
                () -> service.queryOrders(null, null, START, END));
        verifyNoInteractions(orders);
    }

    @Test
    void salesRepRankingIsRejected() {
        loginRep();
        // 需求 4.4：按人排名对销售员无意义（只能看自己），拒绝而非收窄
        assertThrows(PermissionDeniedException.class,
                () -> service.queryRepRanking(1L, START, END, 5));
        verify(orders, never()).findRepRanking(any(), any(), any());
    }

    @Test
    void regionRankingIsDirectorOnly() {
        loginManager();
        assertThrows(PermissionDeniedException.class,
                () -> service.queryRegionRanking(START, END));

        loginDirector();
        when(orders.findRegionRanking(START, END)).thenReturn(List.of());
        service.queryRegionRanking(START, END);
        verify(orders).findRegionRanking(START, END);
    }

    @Test
    void directorCanQueryWholeCompany() {
        loginDirector();
        when(orders.findAll()).thenReturn(List.of());
        assertEquals(List.of(), service.queryOrders(null, null, START, END));
        verify(orders).findAll();
    }
}
