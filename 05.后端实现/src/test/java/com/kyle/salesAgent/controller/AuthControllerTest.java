package com.kyle.salesAgent.controller;

import com.kyle.salesAgent.repository.SalesRepRepository;
import com.kyle.salesAgent.entity.SalesRep;
import com.kyle.salesAgent.security.LoginAttemptLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;
import java.util.Optional;
import java.util.Map;

class AuthControllerTest {

    @Test
    void rejectedAttemptDoesNotQueryAccount() {
        SalesRepRepository repository = mock(SalesRepRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        LoginAttemptLimiter limiter = mock(LoginAttemptLimiter.class);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getRemoteAddr()).thenReturn("192.0.2.1");
        when(limiter.tryAcquire(2L, "192.0.2.1")).thenReturn(false);

        AuthController controller = new AuthController(repository, encoder, limiter);
        var response = controller.login(new AuthController.LoginRequest(2L, "wrong"), servletRequest);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        verifyNoInteractions(repository, encoder);
    }

    @Test
    void wrongPasswordDoesNotRevealWhetherAccountExists() {
        SalesRepRepository repository = mock(SalesRepRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        LoginAttemptLimiter limiter = mock(LoginAttemptLimiter.class);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getRemoteAddr()).thenReturn("192.0.2.1");
        when(limiter.tryAcquire(2L, "192.0.2.1")).thenReturn(true);

        AuthController controller = new AuthController(repository, encoder, limiter);
        var response = controller.login(new AuthController.LoginRequest(2L, "wrong"), servletRequest);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse(response.getBody().toString().contains("不存在"));
    }

    @Test
    void disabledAccountUsesSameErrorAsUnknownAccount() {
        SalesRepRepository repository = mock(SalesRepRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        LoginAttemptLimiter limiter = mock(LoginAttemptLimiter.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.0.2.1");
        when(limiter.tryAcquire(2L, "192.0.2.1")).thenReturn(true);
        SalesRep disabled = new SalesRep();
        disabled.setActive(false);
        when(repository.findById(2L)).thenReturn(Optional.of(disabled));

        var response = new AuthController(repository, encoder, limiter)
                .login(new AuthController.LoginRequest(2L, "wrong"), request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(Map.of("error", "账号或密码错误"), response.getBody());
        verifyNoInteractions(encoder);
    }
}
