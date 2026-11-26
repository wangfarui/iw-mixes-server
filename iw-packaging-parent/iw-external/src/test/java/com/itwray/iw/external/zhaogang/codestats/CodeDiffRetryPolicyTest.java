package com.itwray.iw.external.zhaogang.codestats;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CodeDiffRetryPolicyTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 26, 10, 0);

    @Test
    void temporaryFailureBacksOffAndStopsAfterTheAttemptLimit() {
        long[] delays = {5, 15, 60};
        for (int attempts = 0; attempts < delays.length; attempts++) {
            var retry = CodeDiffRetryPolicy.afterFailure(CodeDiffFailure.RATE_LIMITED, null, true, attempts, NOW, null);
            assertEquals(attempts + 1, retry.attempts());
            assertEquals(NOW.plusMinutes(delays[attempts]), retry.retryAfter());
            assertNull(retry.credentialSignature());
        }
        var exhausted = CodeDiffRetryPolicy.afterFailure(CodeDiffFailure.RATE_LIMITED, null, true, 3, NOW, null);
        assertEquals(CodeDiffRetryPolicy.MAX_ATTEMPTS, exhausted.attempts());
        assertNull(exhausted.retryAfter());
    }

    @Test
    void permissionWaitsForCredentialChangeAndUnavailableGitKeepsTheRemainingBudget() {
        var denied = CodeDiffRetryPolicy.afterFailure(CodeDiffFailure.UPSTREAM_LIMIT,
                CodeGitLineStats.Failure.PERMISSION_DENIED, true, 2, NOW, "fingerprint");
        assertEquals("fingerprint", denied.credentialSignature());
        assertEquals(0, denied.attempts());
        assertNull(denied.retryAfter());
        var missing = CodeDiffRetryPolicy.afterFailure(CodeDiffFailure.UPSTREAM_LIMIT,
                CodeGitLineStats.Failure.EXECUTABLE_UNAVAILABLE, true, 2, NOW, null);
        assertEquals(2, missing.attempts());
        assertEquals(NOW.plusMinutes(5), missing.retryAfter());
        var oversized = CodeDiffRetryPolicy.afterFailure(CodeDiffFailure.UPSTREAM_LIMIT,
                CodeGitLineStats.Failure.SIZE_LIMIT, true, 2, NOW, null);
        assertEquals(CodeDiffRetryPolicy.MAX_ATTEMPTS, oversized.attempts());
        assertEquals("SIZE_LIMIT", oversized.error());
    }
}
