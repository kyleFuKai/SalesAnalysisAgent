package com.kyle.salesAgent.controller;

import com.kyle.salesAgent.entity.SalesRep;
import com.kyle.salesAgent.entity.SalesRegion;
import com.kyle.salesAgent.repository.SalesRegionRepository;
import com.kyle.salesAgent.repository.SalesRepRepository;
import com.kyle.salesAgent.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Sort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AdminAccountControllerTest {
    private final SalesRepRepository reps = mock(SalesRepRepository.class);
    private final SalesRegionRepository regions = mock(SalesRegionRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AdminAccountController controller = new AdminAccountController(reps, regions, encoder);

    @AfterEach
    void clear() { UserContext.clear(); }

    @Test
    void directorCannotManageAccounts() {
        UserContext.set(new UserContext.UserInfo(13L, "总监", "SALES_DIRECTOR", 1L, 13L));
        assertEquals(HttpStatus.FORBIDDEN, controller.list().getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.listRegions().getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.create(new AdminAccountController.CreateAccountRequest(
                "新人", "SALES_REP", 1L, null, "very-long-test-password")).getStatusCode());
        verifyNoInteractions(regions, encoder);
    }

    @Test
    void staleAdminSessionCannotManageAccounts() {
        UserContext.set(new UserContext.UserInfo(20L, "管理员", "SYS_ADMIN", null, 20L));
        when(reps.findById(20L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.FORBIDDEN, controller.list().getStatusCode());
    }

    @Test
    void adminCannotCreateAnotherAdmin() {
        SalesRep admin = new SalesRep();
        admin.setRole("SYS_ADMIN");
        admin.setActive(true);
        UserContext.set(new UserContext.UserInfo(20L, "管理员", "SYS_ADMIN", null, 20L));
        when(reps.findById(20L)).thenReturn(Optional.of(admin));

        assertEquals(HttpStatus.BAD_REQUEST, controller.create(new AdminAccountController.CreateAccountRequest(
                "另一个管理员", "SYS_ADMIN", 1L, null, "very-long-test-password")).getStatusCode());
        verify(reps, never()).save(any());
    }

    @Test
    void adminReceivesRealRegionNamesForSelection() {
        SalesRep admin = new SalesRep();
        admin.setRole("SYS_ADMIN");
        admin.setActive(true);
        UserContext.set(new UserContext.UserInfo(20L, "管理员", "SYS_ADMIN", null, 20L));
        when(reps.findById(20L)).thenReturn(Optional.of(admin));
        SalesRegion east = new SalesRegion();
        east.setId(1L);
        east.setName("华东区");
        when(regions.findAll(Sort.by("id"))).thenReturn(List.of(east));

        var response = controller.listRegions();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(new AdminAccountController.RegionView(1L, "华东区")), response.getBody());
    }
}
