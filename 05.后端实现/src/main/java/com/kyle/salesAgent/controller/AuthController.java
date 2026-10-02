package com.kyle.salesAgent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.kyle.salesAgent.entity.SalesRep;
import com.kyle.salesAgent.repository.SalesRepRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;
/**
 * 认证接口：登录 / 登出。
 * <p>登录流程：repId + 密码 → BCrypt 校验 → StpUtil.login() 签发 token
 * → 用户身份写入 SaSession（供 WebMvcConfig 拦截器读取填充 UserContext）。
 * <p>测试账号见 data.sql：repId 1~13，密码统一 123456。
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

    /** 登录请求体（测试数据有重名风险，暂用 repId 作账号；校验交给 @Valid，失败由 GlobalExceptionHandler 转 400） */
    record LoginRequest(@NotNull Long repId, @NotBlank String password) {}

    /** 登录响应体：前端保存 token，之后每个请求在 header（satoken）里携带 */
    record LoginResponse(String token, String username, String role) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        Optional<SalesRep> repOpt = repRepository.findById(request.repId());
        SalesRep rep = repOpt.orElse(null);

        // "用户不存在"和"密码错误"返回同一句提示——不向外界暴露某个 repId 是否存在
        if (rep == null || !passwordEncoder.matches(request.password(), rep.getPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "账号或密码错误"));
        }

        // Sa-Token 登录，repId 作为登录标识（即 UserContext.userId）
        StpUtil.login(rep.getId());

        // 用户信息写入 Sa-Token Session，后续请求由拦截器读取填充 UserContext——
        // 这四个键与 WebMvcConfig 里的 session.get(...) 一一对应，改任何一处必须同步另一处
        StpUtil.getSession()
                .set("username", rep.getName())
                .set("role",     rep.getRole())
                .set("regionId", rep.getRegionId())
                .set("repId",    rep.getId());

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
}
