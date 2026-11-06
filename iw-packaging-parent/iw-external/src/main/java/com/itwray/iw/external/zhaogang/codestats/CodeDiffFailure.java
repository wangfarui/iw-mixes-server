package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingResponseTooLargeException;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.regex.Pattern;

/** 细分 diff 失败原因，不将上游消息、响应内容或凭证写入日志和 API。 */
enum CodeDiffFailure {
    UPSTREAM_LIMIT("CODING 拒绝返回过大的 diff", true),
    RESPONSE_LIMIT("diff 响应超过工作台的大小上限", true),
    INVALID_ARGUMENT("CODING 拒绝了 diff 请求参数", false),
    PERMISSION_DENIED("CODING 拒绝访问该提交的 diff", false),
    RATE_LIMITED("CODING 请求频率受限", false),
    TIMEOUT("CODING diff 请求超时", false),
    UNAVAILABLE("暂时无法读取 CODING diff", false),
    INVALID_RESPONSE("CODING 未返回可用的 diff 列表", false),
    REQUEST_FAILED("diff 读取失败", false);

    private static final Pattern SAFE_CODE = Pattern.compile(
            "(?:FailedOperation|InvalidParameter(?:Value)?|UnauthorizedOperation|AuthFailure|RequestLimitExceeded"
                    + "|InternalError|ResourceNotFound|UnsupportedOperation)(?:\\.[A-Za-z0-9_]{1,32})?"
                    + "|HTTP_[1-5][0-9]{2}|RESPONSE_TOO_LARGE|InvalidDiffResponse");

    final String reason;
    final boolean sizeLimit;

    CodeDiffFailure(String reason, boolean sizeLimit) {
        this.reason = reason;
        this.sizeLimit = sizeLimit;
    }

    String status() {
        return "DIFF_" + name();
    }

    static CodeDiffFailure classify(RuntimeException failure) {
        if (failure instanceof CodingOpenApiException error) {
            String code = error.code();
            String message = java.util.Objects.toString(error.getMessage(), "");
            if (Integer.valueOf(401).equals(error.httpStatus()) || Integer.valueOf(403).equals(error.httpStatus())
                    || error.isPermissionDenied()) return PERMISSION_DENIED;
            if (Integer.valueOf(413).equals(error.httpStatus()) || "HTTP_413".equals(code)
                    || ("FailedOperation".equals(code) && message.contains("diff 修改文件过多"))) return UPSTREAM_LIMIT;
            if (failure instanceof CodingResponseTooLargeException) return RESPONSE_LIMIT;
            if (Integer.valueOf(429).equals(error.httpStatus()) || "HTTP_429".equals(code)
                    || code.startsWith("RequestLimitExceeded")) return RATE_LIMITED;
            if (error.getCause() instanceof HttpTimeoutException || "HTTP_408".equals(code)
                    || Integer.valueOf(408).equals(error.httpStatus())) return TIMEOUT;
            if (code.startsWith("InternalError") || code.startsWith("HTTP_5")
                    || (error.httpStatus() != null && error.httpStatus() >= 500)
                    || error.getCause() instanceof IOException) return UNAVAILABLE;
            if (code.startsWith("InvalidParameter")
                    || ("FailedOperation".equals(code) && (message.contains("参数异常") || message.contains("参数错误")))) {
                return INVALID_ARGUMENT;
            }
            if ("InvalidDiffResponse".equals(code)) return INVALID_RESPONSE;
        }
        return REQUEST_FAILED;
    }

    static String safeUpstreamCode(CodingOpenApiException error) {
        return error != null && SAFE_CODE.matcher(error.code()).matches() ? error.code() : null;
    }

    static CodeDiffFailure fromStatus(String status) {
        if (status == null || !status.startsWith("DIFF_")) return null;
        for (CodeDiffFailure failure : values()) {
            if (failure.status().equals(status)) return failure;
        }
        return null;
    }

    static boolean isFailure(String status) {
        return "FAILED".equals(status) || "TOO_LARGE".equals(status) || fromStatus(status) != null;
    }

    /** 提交明细保留原来的状态契约；详细原因在失败明细接口返回。 */
    static String entryStatus(String status) {
        CodeDiffFailure failure = fromStatus(status);
        return failure == null ? status : failure.sizeLimit ? "TOO_LARGE" : "FAILED";
    }
}
