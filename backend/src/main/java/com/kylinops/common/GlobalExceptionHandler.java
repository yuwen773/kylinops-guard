package com.kylinops.common;

import com.kylinops.tool.ToolNotRegisteredException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * <p>
 * 捕获所有 Controller 层未处理的异常，转换为统一的 {@link ApiResponse} 格式返回。
 * </p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 请求参数校验失败（@Valid 注解）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", message);
        return ApiResponse.error(400, "参数校验失败: " + message);
    }

    /**
     * 请求参数校验失败（@RequestParam + @Validated）
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleConstraintViolationException(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数约束违规: {}", message);
        return ApiResponse.error(400, "参数约束违规: " + message);
    }

    /**
     * 缺少请求参数
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMissingParamException(MissingServletRequestParameterException ex) {
        log.warn("缺少请求参数: {}", ex.getParameterName());
        return ApiResponse.error(400, "缺少请求参数: " + ex.getParameterName());
    }

    /**
     * 请求体不可读（JSON 解析失败等）
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleHttpMessageNotReadableException(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败: {}", ex.getMessage());
        return ApiResponse.error(400, "请求体解析失败，请检查 JSON 格式");
    }

    /**
     * 参数类型转换失败
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleTypeMismatchException(MethodArgumentTypeMismatchException ex) {
        log.warn("参数类型转换失败: {} -> {}", ex.getName(), ex.getRequiredType());
        return ApiResponse.error(400, "参数类型错误: " + ex.getName());
    }

    /**
     * 请求方法不支持
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResponse<Void> handleMethodNotSupportedException(HttpRequestMethodNotSupportedException ex) {
        log.warn("不支持的请求方法: {}", ex.getMethod());
        return ApiResponse.error(405, "不支持的请求方法: " + ex.getMethod());
    }

    /**
     * 资源不存在（404 接口路径错误）
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNoResourceFoundException(NoResourceFoundException ex) {
        log.warn("资源不存在: {}", ex.getResourcePath());
        return ApiResponse.error(404, "接口不存在: " + ex.getResourcePath());
    }

    /**
     * 工具未注册异常
     */
    @ExceptionHandler(ToolNotRegisteredException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleToolNotRegisteredException(ToolNotRegisteredException ex) {
        log.warn("工具未注册: {}", ex.getMessage());
        return ApiResponse.error(400, ex.getMessage());
    }

    /**
     * 通用业务异常
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleBusinessException(BusinessException ex) {
        log.warn("业务异常: code={}, message={}", ex.getCode(), ex.getMessage());
        return ApiResponse.error(ex.getCode(), ex.getMessage());
    }

    /**
     * 兜底 — 未捕获的异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnhandledException(Exception ex) {
        log.error("未捕获异常: ", ex);
        return ApiResponse.error(500, "服务器内部错误: " + ex.getMessage());
    }
}
