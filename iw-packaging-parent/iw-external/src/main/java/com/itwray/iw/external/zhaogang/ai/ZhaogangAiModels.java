package com.itwray.iw.external.zhaogang.ai;

import java.util.Locale;

public final class ZhaogangAiModels {

    private ZhaogangAiModels() {
    }

    public enum ExecutionLocation {
        AUTO,
        SERVER,
        LOCAL_AGENT;

        public static ExecutionLocation parse(String value) {
            if (value == null || value.isBlank()) {
                return AUTO;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("AI 执行位置必须是 AUTO、SERVER 或 LOCAL_AGENT");
            }
        }
    }

    public record ConfigStatus(String apiUrl, boolean configured, String apiKeyMasked, String model,
                               ExecutionLocation executionLocation) {
    }

    public record ConfigCommand(String apiUrl, String apiKey, String model,
                                String executionLocation) {
    }

    public record ConnectionTestResult(boolean success, ExecutionLocation executionLocation,
                                       String errorCode, String message) {
    }

    public record AgentTicket(String ticket, String recognitionTaskId, String backendUrl, int expiresInSeconds) {
    }

    public record AgentRedeem(String apiUrl, String apiKey, String model,
                              String prompt, String codingDirectory, long codingTeamId,
                              long codingUserId, long iterationId, String recognitionTaskId) {
    }
}
