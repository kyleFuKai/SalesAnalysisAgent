package com.kyle.salesAgent.audit;

import com.kyle.salesAgent.entity.AuditLogEntity;
import com.kyle.salesAgent.repository.AuditLogRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 审计日志异步写入（需求 7.5 / 架构 7.5 落地）。
 * <p>用独立单线程执行器而非 Spring @Async：主链路零等待（submit 即返回），
 * 写库失败只记日志、绝不影响对话请求本身——审计是旁路，不是业务。
 * <p>保留周期：90 天滚动删除（架构 8 节待办，需定时任务，本期未实现）。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/2
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    /** 审计写库专用单线程：削峰且天然串行化，避免并发写同一张日志表。 */
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audit-writer");
        t.setDaemon(true);
        return t;
    });

    /** 异步落库：立即返回，失败只记错误日志（审计失败不阻断业务）。 */
    public void record(AuditLogEntity entry) {
        writer.execute(() -> {
            try {
                auditLogRepository.save(entry);
            } catch (Exception e) {
                log.error("审计日志落库失败（不影响业务请求）: userId={}", entry.getUserId(), e);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        writer.shutdown();
    }
}
