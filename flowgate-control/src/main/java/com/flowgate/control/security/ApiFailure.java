package com.flowgate.control.security;

/** 显式携带 HTTP 语义的业务失败；不向客户端泄露底层 SQL 或连接详情。 */
public class ApiFailure extends RuntimeException {
    public final int status;
    public final String code;
    public ApiFailure(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
}
