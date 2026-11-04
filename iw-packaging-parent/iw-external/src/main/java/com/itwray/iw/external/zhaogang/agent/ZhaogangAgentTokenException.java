package com.itwray.iw.external.zhaogang.agent;

/** Agent 令牌鉴权失败，与浏览器 Cookie 失效分开处理。 */
public class ZhaogangAgentTokenException extends RuntimeException {

    public ZhaogangAgentTokenException(String message) {
        super(message);
    }
}
