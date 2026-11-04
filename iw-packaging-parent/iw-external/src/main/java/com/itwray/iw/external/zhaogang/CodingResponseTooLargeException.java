package com.itwray.iw.external.zhaogang;

/** CODING 响应超过保护上限；用于把超大 diff 与普通请求失败区分开。 */
public class CodingResponseTooLargeException extends CodingOpenApiException {

    public CodingResponseTooLargeException(String action, long limitBytes) {
        super(action, "RESPONSE_TOO_LARGE", "CODING 响应超过 " + Math.max(1, limitBytes / (1024 * 1024)) + " MB 保护上限");
    }
}
