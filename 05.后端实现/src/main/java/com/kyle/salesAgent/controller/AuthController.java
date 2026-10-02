package com.kyle.salesAgent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.kyle.salesAgent.entity.SalesRep;
import com.kyle.salesAgent.repository.SalesRepRepository;
import com.kyle.salesAgent.security.LoginAttemptLimiter;
import com.kyle.salesAgent.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
/**
 * 认证接口：登录 / 登出。
 * <p>登录流程：repId + 密码 → BCrypt 校验 → StpUtil.login() 签发 token
 * → 后续请求由拦截器按登录 ID 查询最新账号状态并填充 UserContext。
 * <p>预置账号仅用于本地演示，正式部署不能继续使用统一测试密码。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/1 16:26
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SalesRepRepository repRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptLimiter loginAttemptLimiter;

    /** 登录请求体（测试数据有重名风险，暂用 repId 作账号；校验交给 @Valid，失败由 GlobalExceptionHandler 转 400） */
    record LoginRequest(@NotNull Long repId, @NotBlank @Size(max = 128) String password) {}

    /** 登录响应体：前端保存 token，之后每个请求在 Authorization header 里携带。 */
    record LoginResponse(String token, String username, String role) {}

    record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        if (!loginAttemptLimiter.tryAcquire(request.repId(), servletRequest.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "登录尝试过于频繁，请稍后重试"));
        }
        Optional<SalesRep> repOpt = repRepository.findById(request.repId());
        SalesRep rep = repOpt.orElse(null);

        // "用户不存在"和"密码错误"返回同一句提示——不向外界暴露某个 repId 是否存在
        if (rep == null || !Boolean.TRUE.equals(rep.getActive())
                || !passwordEncoder.matches(request.password(), rep.getPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "账号或密码错误"));
        }

        // Sa-Token 登录，repId 作为登录标识（即 UserContext.userId）
        StpUtil.login(rep.getId());

        return ResponseEntity.ok(new LoginResponse(
                StpUtil.getTokenValue(),
                rep.getName(),
                rep.getRole()));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        StpUtil.logout();
        return ResponseEntity.ok(Map.of("message", "已退出登录"));
    }

    /** 登录用户自行改密。先撤销全部会话，再落库；撤销失败时不更改密码。 */
    @PostMapping("/password")
    public ResponseEntity<?> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                             HttpServletRequest servletRequest) {
        UserContext.UserInfo user = UserContext.get();
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (!loginAttemptLimiter.tryAcquire(user.userId(), servletRequest.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "尝试过于频繁，请稍后重试"));
        }
        SalesRep rep = repRepository.findById(user.userId()).orElse(null);
        if (rep == null || !Boolean.TRUE.equals(rep.getActive())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!passwordEncoder.matches(request.oldPassword(), rep.getPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "原密码错误"));
        }
        String next = request.newPassword();
        if (next.length() < 12 || next.getBytes(StandardCharsets.UTF_8).length > 72
                || next.equals(request.oldPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "新密码至少 12 位、最多 72 字节，且不能与原密码相同"));
        }
        String encoded = passwordEncoder.encode(next);
        StpUtil.logout(rep.getId());
        rep.setPassword(encoded);
        repRepository.save(rep);
        return ResponseEntity.ok(Map.of("message", "密码已修改，请重新登录"));
    }
}
