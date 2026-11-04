package com.itwray.iw.external.zhaogang.agent;

/** Agent 令牌有效，但绑定的 CODING 凭证缺失或已解除。 */
public class ZhaogangAgentCodingCredentialException extends RuntimeException {

    public ZhaogangAgentCodingCredentialException(String message) {
        super(message);
    }
}
