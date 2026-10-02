package com.kyle.salesAgent.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoginAttemptLimiterTest {

    @Test
    void limitsOneAccountAcrossDifferentIps() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(2, 10, 60);
        assertTrue(limiter.tryAcquire(1L, "192.0.2.1"));
        assertTrue(limiter.tryAcquire(1L, "192.0.2.2"));
        assertFalse(limiter.tryAcquire(1L, "192.0.2.3"));
    }

    @Test
    void limitsOneIpAcrossDifferentAccounts() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(10, 2, 60);
        assertTrue(limiter.tryAcquire(1L, "192.0.2.1"));
        assertTrue(limiter.tryAcquire(2L, "192.0.2.1"));
        assertFalse(limiter.tryAcquire(3L, "192.0.2.1"));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new LoginAttemptLimiter(0, 30, 60));
    }
}
