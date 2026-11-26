package com.itwray.iw.external.zhaogang.codestats;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

final class CodeDiffRetryPolicy {
    static final int MAX_ATTEMPTS = 4;
    static final int BATCH_SIZE = 5;
    private static final long[] BACKOFF_MINUTES = {5, 15, 60};

    private CodeDiffRetryPolicy() {
    }

    static String credentialSignature(CodeStatsModule.ScopeInfo scope) {
        return CodeStatsSupport.sha256(scope.members().stream()
                .filter(scope::hasRepositoryToken)
                .map(scope::repositoryToken)
                .map(CodeStatsSupport::sha256)
                .distinct().sorted().collect(Collectors.joining(",")));
    }

    static State afterFailure(CodeDiffFailure reason, CodeGitLineStats.Failure gitFailure, boolean automatic,
                              int previousAttempts, LocalDateTime now, String credentials) {
        String error = gitFailure == null ? null : gitFailure.name();
        if (reason == CodeDiffFailure.PERMISSION_DENIED || gitFailure == CodeGitLineStats.Failure.PERMISSION_DENIED) {
            return new State(0, null, credentials, error);
        }
        if (!automatic) {
            if (previousAttempts > 0 || !isTemporary(reason)) return null;
            return new State(0, now.plusMinutes(BACKOFF_MINUTES[0]), null, error);
        }
        if (gitFailure == CodeGitLineStats.Failure.SIZE_LIMIT || gitFailure == CodeGitLineStats.Failure.INVALID_TARGET
                || gitFailure == CodeGitLineStats.Failure.INVALID_OUTPUT || gitFailure == CodeGitLineStats.Failure.MERGE) {
            return new State(MAX_ATTEMPTS, null, null, error);
        }
        if (gitFailure == CodeGitLineStats.Failure.EXECUTABLE_UNAVAILABLE || gitFailure == CodeGitLineStats.Failure.BUSY) {
            return new State(previousAttempts, now.plusMinutes(BACKOFF_MINUTES[0]), null, error);
        }
        int attempts = Math.min(MAX_ATTEMPTS, previousAttempts + 1);
        LocalDateTime retryAfter = attempts < MAX_ATTEMPTS ? now.plusMinutes(BACKOFF_MINUTES[attempts - 1]) : null;
        return new State(attempts, retryAfter, null, error);
    }

    private static boolean isTemporary(CodeDiffFailure reason) {
        return switch (reason) {
            case RATE_LIMITED, TIMEOUT, UNAVAILABLE, INVALID_RESPONSE, REQUEST_FAILED -> true;
            default -> false;
        };
    }

    static String gitFailureMessage(String error) {
        if (error == null) return "";
        return switch (error) {
            case "EXECUTABLE_UNAVAILABLE" -> "，Git 不可执行";
            case "PERMISSION_DENIED" -> "，Git 凭证无权读取";
            case "CREDENTIAL_UNAVAILABLE" -> "，Git 凭证暂不可用";
            case "SIZE_LIMIT" -> "，Git 补算超过资源上限";
            case "TIMEOUT" -> "，Git 补算超时";
            case "BUSY" -> "，Git 补算繁忙";
            case "OBJECT_UNAVAILABLE" -> "，Git 未能取得提交对象";
            case "INVALID_TARGET", "INVALID_OUTPUT", "MERGE" -> "，Git 无法确认行数";
            default -> "，Git 补算失败";
        };
    }

    record State(int attempts, LocalDateTime retryAfter, String credentialSignature, String error) {
    }
}
