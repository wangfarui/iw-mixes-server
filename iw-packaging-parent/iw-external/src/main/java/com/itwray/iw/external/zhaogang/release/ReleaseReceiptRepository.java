package com.itwray.iw.external.zhaogang.release;

import java.time.LocalDateTime;
import java.util.Optional;

interface ReleaseReceiptRepository {

    Optional<LocalDateTime> findReadAt(long codingTeamId, long codingUserId, String releaseId);

    LocalDateTime markRead(long codingTeamId, long codingUserId, String releaseId);
}
