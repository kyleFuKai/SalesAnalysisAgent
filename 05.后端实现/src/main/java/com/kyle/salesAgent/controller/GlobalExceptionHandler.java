package com.kyle.salesAgent.controller;

import cn.dev33.satoken.exception.NotLoginException;
import com.kyle.salesAgent.exception.PermissionDeniedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 统一处理 HTTP 请求参数错误和未预期异常，避免向客户端暴露内部错误信息。
 * 流式响应开始后的错误由 SSE 回调处理，不经过这里。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/9/24 10:09
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 请求体字段未通过 Bean Validation 校验时，返回第一个字段错误。
     *
     * @param e 字段校验异常
     * @return HTTP 400 和字段错误信息
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .findFirst().orElse("参数校验失败");
        return ResponseEntity.badRequest().body(msg);
    }

    /**
     * 请求体缺失、JSON 格式错误或 JSON 字段类型不匹配时返回统一提示。
     * 不返回解析器的原始错误，避免暴露内部实现细节。
     *
     * @param e 请求体读取异常
     * @return HTTP 400 和安全的格式错误提示
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> handleUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body("请求体格式错误，请检查 JSON 内容");
    }

    /**
     * 路径或查询参数无法转换为目标类型时，告知出错的参数名。
     *
     * @param e 参数类型转换异常
     * @return HTTP 400 和参数格式错误提示
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<String> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body("请求参数格式错误：" + e.getName());
    }

    /**
     * 未登录/登录过期：Sa-Token 登录校验抛出的异常。
     * 之前会落进兜底 500，前端无法据此跳转登录页。
     *
     * @param e 未登录异常
     * @return HTTP 401 和提示
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<String> handleNotLogin(NotLoginException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("未登录或登录已过期");
    }

    /**
     * 带 HTTP 状态码的业务异常（如限流 429）：按异常自带的状态码和原因返回。
     *
     * @param e 状态码异常
     * @return 异常自带的状态码和原因
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> handleResponseStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(e.getReason() != null ? e.getReason() : "请求被拒绝");
    }

    /**
     * 权限拒绝：Service 层行级校验不通过或 fail-closed 兜底（架构 4.2）。
     * 返回 403 和面向用户的话术；工具链路里该消息也会透传给模型用于拒答。
     *
     * @param e 权限拒绝异常
     * @return HTTP 403 和拒绝原因
     */
    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<String> handlePermissionDenied(PermissionDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
    }

    /**
     * 记录未预期异常的完整堆栈，对客户端只返回通用错误提示。
     *
     * @param e 未被其他处理方法覆盖的异常
     * @return HTTP 500 和通用服务错误提示
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleUnknown(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.internalServerError().body("服务暂时不可用，请稍后重试");
    }
}
