package com.flowgate.control.controller;

import com.flowgate.control.security.ApiFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 统一管理错误，预期参数/依赖失败返回中文说明，隐藏内部异常内容。 */
@RestControllerAdvice
public class ErrorAdvice {
    @ExceptionHandler({org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class})
    ResponseEntity<?> missing(Exception error, HttpServletRequest req) {
        return body(404, "NOT_FOUND", "接口不存在", req);
    }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> method(Exception error, HttpServletRequest req) {
        return body(405, "METHOD_NOT_ALLOWED", "请求方法不支持", req);
    }
    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<?> business(ApiFailure error, HttpServletRequest req) {
        return body(error.status, error.code, error.getMessage(), req);
    }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class,
            tools.jackson.core.JacksonException.class, org.springframework.web.HttpMediaTypeNotSupportedException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid(Exception error, HttpServletRequest req) {
        return body(400, "VALIDATION_ERROR", "参数、JSON 或业务约束不合法", req);
    }
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<?> duplicate(Exception error, HttpServletRequest req) {
        return body(409, "CONFLICT", "唯一键或版本冲突", req);
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> dependency(Exception error, HttpServletRequest req) {
        return body(503, "DEPENDENCY_UNAVAILABLE", "数据库或会话服务不可用", req);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception error, HttpServletRequest req) {
        // 只记录异常类型和代码位置，不输出可能包含 SQL、凭证或请求体的异常消息。
        org.slf4j.LoggerFactory.getLogger(getClass()).error("非预期失败类型={}，位置={}",
                error.getClass().getName(), java.util.Arrays.toString(error.getStackTrace()));
        return body(500, "INTERNAL_ERROR", "服务处理失败", req);
    }
    private ResponseEntity<?> body(int status, String code, String message, HttpServletRequest req) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message,
                "requestId", String.valueOf(req.getAttribute("requestId"))));
    }
}
