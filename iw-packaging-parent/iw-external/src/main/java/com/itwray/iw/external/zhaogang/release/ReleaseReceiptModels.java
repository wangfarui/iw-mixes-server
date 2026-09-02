package com.itwray.iw.external.zhaogang.release;

import java.time.LocalDateTime;

public final class ReleaseReceiptModels {

    private ReleaseReceiptModels() {
    }

    public record Context(long codingTeamId, long codingUserId) {
    }

    public record Receipt(boolean read, LocalDateTime readAt) {
    }
}
