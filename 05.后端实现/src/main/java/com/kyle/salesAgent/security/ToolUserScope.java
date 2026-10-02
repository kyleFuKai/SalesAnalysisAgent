package com.kyle.salesAgent.security;

import com.kyle.salesAgent.entity.SalesRep;
import com.kyle.salesAgent.exception.PermissionDeniedException;
import com.kyle.salesAgent.repository.SalesRepRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 把服务端生成的会话记忆 ID 还原为工具执行线程的用户身份。
 * LangChain4j 流式工具会在独立线程执行，Servlet 拦截器的 ThreadLocal 不会自动传播。
 * memoryId 由 Controller 绑定真实登录用户，不能使用模型传入的普通工具参数代替。
 */
@Component
@RequiredArgsConstructor
public class ToolUserScope {

    private final SalesRepRepository repRepository;

    public <T> T call(String memoryId, Supplier<T> action) {
        if (memoryId == null) throw new PermissionDeniedException("缺少会话身份，拒绝执行工具");
        int separator = memoryId.indexOf(':');
        if (separator < 1 || separator == memoryId.length() - 1) {
            throw new PermissionDeniedException("会话身份格式错误，拒绝执行工具");
        }
        final long userId;
        try {
            userId = Long.parseLong(memoryId.substring(0, separator));
        } catch (NumberFormatException e) {
            throw new PermissionDeniedException("会话身份格式错误，拒绝执行工具");
        }

        UserContext.UserInfo previous = UserContext.get();
        if (previous != null && !previous.userId().equals(userId)) {
            throw new PermissionDeniedException("会话用户与当前身份不一致");
        }
        SalesRep rep = repRepository.findById(userId)
                .orElseThrow(() -> new PermissionDeniedException("会话用户不存在"));
        if (!Boolean.TRUE.equals(rep.getActive())) {
            throw new PermissionDeniedException("账号已停用，拒绝执行工具");
        }
        if (previous == null) {
            UserContext.UserInfo recovered = new UserContext.UserInfo(rep.getId(), rep.getName(), rep.getRole(),
                    rep.getRegionId(), rep.getId());
            if (!recovered.hasValidScope()) throw new PermissionDeniedException("会话用户权限无效");
            UserContext.set(recovered);
        }
        try {
            return action.get();
        } finally {
            if (previous == null) UserContext.clear();
        }
    }
}
