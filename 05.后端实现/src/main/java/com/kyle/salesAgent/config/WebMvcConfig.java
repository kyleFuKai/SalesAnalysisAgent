package com.kyle.salesAgent.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.kyle.salesAgent.entity.SalesRep;
import com.kyle.salesAgent.repository.SalesRepRepository;
import com.kyle.salesAgent.security.UserContext;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
/**
 *
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/1 15:56
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer{

    private final SalesRepRepository repRepository;

    @Value("${app.auth.enabled:true}")
    private boolean authEnabled;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (!authEnabled) {
            log.warn(">>> 权限校验已关闭（app.auth.enabled=false），仅限开发测试使用 <<<");
            // 只注册 ThreadLocal 清理拦截器，不注册 Sa-Token 登录校验
            registry.addInterceptor(new HandlerInterceptor() {
                @Override
                public void afterCompletion(HttpServletRequest request,
                                            HttpServletResponse response,
                                            Object handler, Exception ex) {
                    UserContext.clear();
                }
            }).addPathPatterns("/**");
            return;
        }
        // Sa-Token 登录校验拦截器——白名单之外的接口都需要登录
        // 以下是正常的权限拦截器逻辑（auth.enabled=true 时生效）
        registry.addInterceptor(
                        new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/login",
                        "/actuator/**",
                        "/static/**",
                        "/error"          // 异常转发路径：不拦，否则原始报错会被二次 NotLoginException 掩盖
                );

        // 每次从数据库读取最新角色和账号状态，避免停用/角色变化后旧 Session 继续越权。
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request,
                                     HttpServletResponse response,
                                     Object handler) {
                if (StpUtil.isLogin()) {
                    Long userId    = StpUtil.getLoginIdAsLong();
                    SalesRep rep = repRepository.findById(userId).orElse(null);
                    if (rep == null || !Boolean.TRUE.equals(rep.getActive())) {
                        StpUtil.logout(userId);
                        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号已停用或登录信息已失效");
                    }
                    UserContext.set(new UserContext.UserInfo(userId, rep.getName(), rep.getRole(),
                            rep.getRegionId(), rep.getId()));
                    log.debug("用户已认证: userId={}, role={}", userId, rep.getRole());
                }
                return true;
            }

            @Override
            public void afterCompletion(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Object handler, Exception ex) {
                UserContext.clear();   // 请求完成后清理 ThreadLocal，防止内存泄漏
            }
        }).addPathPatterns("/**");
    }

}
