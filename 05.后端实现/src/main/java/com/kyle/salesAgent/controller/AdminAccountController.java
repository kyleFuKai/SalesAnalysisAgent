package com.kyle.salesAgent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.kyle.salesAgent.entity.SalesRep;
import com.kyle.salesAgent.entity.SalesRegion;
import com.kyle.salesAgent.repository.SalesRegionRepository;
import com.kyle.salesAgent.repository.SalesRepRepository;
import com.kyle.salesAgent.security.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 独立系统管理员的账号管理接口；管理员不拥有销售数据访问权。 */
@RestController
@RequestMapping("/admin/accounts")
@RequiredArgsConstructor
public class AdminAccountController {
    private final SalesRepRepository reps;
    private final SalesRegionRepository regions;
    private final PasswordEncoder encoder;

    record CreateAccountRequest(
            @NotBlank @Size(max = 50) String name,
            @NotBlank String role,
            @NotNull Long regionId,
            @Email @Size(max = 100) String email,
            @NotBlank String password) {}

    record ResetPasswordRequest(@NotBlank String password) {}

    record AccountView(Long id, String name, String role, Long regionId, String email, boolean active) {
        static AccountView from(SalesRep rep) {
            return new AccountView(rep.getId(), rep.getName(), rep.getRole(), rep.getRegionId(),
                    rep.getEmail(), Boolean.TRUE.equals(rep.getActive()));
        }
    }

    record RegionView(Long id, String name) {
        static RegionView from(SalesRegion region) {
            return new RegionView(region.getId(), region.getName());
        }
    }

    private boolean isAdmin() {
        UserContext.UserInfo user = UserContext.get();
        return user != null && "SYS_ADMIN".equals(user.role()) && user.userId() != null
                && reps.findById(user.userId())
                .map(rep -> "SYS_ADMIN".equals(rep.getRole()) && Boolean.TRUE.equals(rep.getActive()))
                .orElse(false);
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "仅系统管理员可操作账号"));
    }

    private boolean validPassword(String value) {
        return value != null && value.length() >= 12
                && value.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    @GetMapping
    public ResponseEntity<?> list() {
        if (!isAdmin()) return forbidden();
        List<AccountView> accounts = reps.findAll().stream()
                .filter(rep -> !"SYS_ADMIN".equals(rep.getRole()))
                .map(AccountView::from).toList();
        return ResponseEntity.ok(accounts);
    }

    /** 管理员创建账号时使用的真实大区选项，不向未授权用户开放。 */
    @GetMapping("/regions")
    public ResponseEntity<?> listRegions() {
        if (!isAdmin()) return forbidden();
        return ResponseEntity.ok(regions.findAll(Sort.by("id")).stream().map(RegionView::from).toList());
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateAccountRequest request) {
        if (!isAdmin()) return forbidden();
        if (!List.of("SALES_REP", "SALES_MANAGER", "SALES_DIRECTOR").contains(request.role())) {
            return ResponseEntity.badRequest().body(Map.of("error", "不支持的销售角色"));
        }
        if (!regions.existsById(request.regionId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "大区不存在"));
        }
        if (!validPassword(request.password())) {
            return ResponseEntity.badRequest().body(Map.of("error", "密码需至少 12 位且不超过 72 字节"));
        }
        SalesRep rep = new SalesRep();
        rep.setName(request.name());
        rep.setRole(request.role());
        rep.setRegionId(request.regionId());
        rep.setEmail(request.email());
        rep.setPassword(encoder.encode(request.password()));
        rep.setActive(true);
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountView.from(reps.save(rep)));
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<?> disable(@PathVariable Long id) {
        if (!isAdmin()) return forbidden();
        SalesRep rep = reps.findById(id).orElse(null);
        if (rep == null || "SYS_ADMIN".equals(rep.getRole())) return ResponseEntity.notFound().build();
        StpUtil.logout(id);
        rep.setActive(false);
        reps.save(rep);
        return ResponseEntity.ok(AccountView.from(rep));
    }

    @PostMapping("/{id}/enable")
    public ResponseEntity<?> enable(@PathVariable Long id) {
        if (!isAdmin()) return forbidden();
        SalesRep rep = reps.findById(id).orElse(null);
        if (rep == null || "SYS_ADMIN".equals(rep.getRole())) return ResponseEntity.notFound().build();
        rep.setActive(true);
        reps.save(rep);
        return ResponseEntity.ok(AccountView.from(rep));
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<?> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest request) {
        if (!isAdmin()) return forbidden();
        SalesRep rep = reps.findById(id).orElse(null);
        if (rep == null || "SYS_ADMIN".equals(rep.getRole())) return ResponseEntity.notFound().build();
        if (!validPassword(request.password())) {
            return ResponseEntity.badRequest().body(Map.of("error", "密码需至少 12 位且不超过 72 字节"));
        }
        String encoded = encoder.encode(request.password());
        StpUtil.logout(id);
        rep.setPassword(encoded);
        reps.save(rep);
        return ResponseEntity.ok(Map.of("message", "密码已重置，原有登录会话已失效"));
    }
}
