package com.kyle.salesAgent.exception;

/**
 * 权限拒绝异常：行级权限校验不通过或 fail-closed 兜底触发时抛出（架构 4.2）。
 * <p>消息措辞面向最终用户（"您只能查询…"），HTTP 层由 GlobalExceptionHandler 转 403；
 * 工具层捕获后把消息原样透传给模型，模型据此明确拒答，而不是把 403 误答成"没有数据"。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/1
 */
public class PermissionDeniedException extends RuntimeException {

    public PermissionDeniedException(String message) {
        super(message);
    }
}
