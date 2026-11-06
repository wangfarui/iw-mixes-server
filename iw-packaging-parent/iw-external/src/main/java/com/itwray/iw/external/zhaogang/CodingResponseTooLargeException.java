package com.itwray.iw.external.zhaogang;

/** CODING 响应超过保护上限；用于把超大 diff 与普通请求失败区分开。 */
public class CodingResponseTooLargeException extends CodingOpenApiException {

    private final long limitBytes;

    public CodingResponseTooLargeException(String action, long limitBytes) {
        this(action, limitBytes, null);
    }

    public CodingResponseTooLargeException(String action, long limitBytes, Integer httpStatus) {
        super(action, "RESPONSE_TOO_LARGE", "CODING 响应超过大小保护上限", httpStatus, null);
        this.limitBytes = limitBytes;
    }

    public long limitBytes() {
        return limitBytes;
    }
}
