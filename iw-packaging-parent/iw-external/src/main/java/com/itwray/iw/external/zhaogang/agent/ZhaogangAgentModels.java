package com.itwray.iw.external.zhaogang.agent;

/** Agent 授权接口模型；不包含 CODING PAT 或 Agent 令牌哈希。 */
public final class ZhaogangAgentModels {

    private ZhaogangAgentModels() {
    }

    public record TokenStatus(boolean configured, String tokenHint, String createdAt, String lastUsedAt) {
    }

    public record TokenCreated(String token, TokenStatus status) {
    }

    public record Me(long teamId, long userId, String userName, String avatar, TokenStatus token) {
    }
}
