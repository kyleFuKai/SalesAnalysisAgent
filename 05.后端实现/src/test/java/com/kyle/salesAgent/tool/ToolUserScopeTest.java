package com.kyle.salesAgent.tool;

import com.kyle.salesAgent.entity.SalesRep;
import com.kyle.salesAgent.exception.PermissionDeniedException;
import com.kyle.salesAgent.repository.SalesRepRepository;
import com.kyle.salesAgent.security.ToolUserScope;
import com.kyle.salesAgent.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 流式工具线程的身份恢复与清理必须 fail-closed，不能复用上一位用户的身份。 */
class ToolUserScopeTest {

    private final SalesRepRepository repository = mock(SalesRepRepository.class);
    private final ToolUserScope scope = new ToolUserScope(repository);

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void restoresServerBoundIdentityOnlyDuringToolCall() {
        SalesRep rep = new SalesRep();
        rep.setId(2L);
        rep.setName("张伟");
        rep.setRole("SALES_REP");
        rep.setRegionId(1L);
        when(repository.findById(2L)).thenReturn(Optional.of(rep));

        Long scopedRep = scope.call("2:session-1", () -> UserContext.get().repId());

        assertEquals(2L, scopedRep);
        assertNull(UserContext.get());
    }

    @Test
    void rejectsMismatchedAndMalformedIdentity() {
        UserContext.set(new UserContext.UserInfo(2L, "张伟", "SALES_REP", 1L, 2L));
        assertThrows(PermissionDeniedException.class, () -> scope.call("13:session-1", () -> "unsafe"));
        assertThrows(PermissionDeniedException.class, () -> scope.call("invalid", () -> "unsafe"));
        assertEquals(2L, UserContext.get().userId());
        verifyNoInteractions(repository);
    }

    @Test
    void disabledAccountCannotExecuteStreamingTool() {
        SalesRep rep = new SalesRep();
        rep.setId(2L);
        rep.setRole("SALES_REP");
        rep.setActive(false);
        when(repository.findById(2L)).thenReturn(Optional.of(rep));
        assertThrows(PermissionDeniedException.class, () -> scope.call("2:session-1", () -> "unsafe"));
        assertNull(UserContext.get());
    }
}
