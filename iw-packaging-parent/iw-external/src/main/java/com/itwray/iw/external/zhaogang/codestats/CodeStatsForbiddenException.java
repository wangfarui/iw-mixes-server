package com.itwray.iw.external.zhaogang.codestats;

/** 代码统计的业务权限拒绝，用于返回明确的 403 业务响应。 */
public class CodeStatsForbiddenException extends RuntimeException {

    public CodeStatsForbiddenException(String message) {
        super(message);
    }
}
