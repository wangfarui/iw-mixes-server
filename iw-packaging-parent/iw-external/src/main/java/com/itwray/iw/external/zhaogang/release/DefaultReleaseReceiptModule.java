package com.itwray.iw.external.zhaogang.release;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.itwray.iw.external.zhaogang.release.ReleaseReceiptModels.Context;
import static com.itwray.iw.external.zhaogang.release.ReleaseReceiptModels.Receipt;

@Service
class DefaultReleaseReceiptModule implements ReleaseReceiptModule {

    private static final Pattern RELEASE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final ReleaseReceiptRepository repository;

    DefaultReleaseReceiptModule(ReleaseReceiptRepository repository) {
        this.repository = repository;
    }

    @Override
    public Receipt receipt(Context context, String releaseId) {
        validate(context, releaseId);
        Optional<LocalDateTime> readAt = repository.findReadAt(context.codingTeamId(), context.codingUserId(), releaseId);
        return new Receipt(readAt.isPresent(), readAt.orElse(null));
    }

    @Override
    public Receipt acknowledge(Context context, String releaseId) {
        validate(context, releaseId);
        LocalDateTime readAt = repository.markRead(context.codingTeamId(), context.codingUserId(), releaseId);
        return new Receipt(true, readAt);
    }

    private void validate(Context context, String releaseId) {
        if (context == null || context.codingTeamId() <= 0 || context.codingUserId() <= 0) {
            throw new IllegalArgumentException("找钢工作台会话信息不完整");
        }
        if (releaseId == null || !RELEASE_ID.matcher(releaseId).matches()) {
            throw new IllegalArgumentException("版本标识格式无效");
        }
    }
}
