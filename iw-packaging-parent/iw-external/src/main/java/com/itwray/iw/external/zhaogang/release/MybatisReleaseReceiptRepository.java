package com.itwray.iw.external.zhaogang.release;

import com.itwray.iw.external.mapper.ZhaogangReleaseReceiptMapper;
import com.itwray.iw.external.zhaogang.release.entity.ReleaseReceiptEntity;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
class MybatisReleaseReceiptRepository implements ReleaseReceiptRepository {

    private final ZhaogangReleaseReceiptMapper mapper;

    MybatisReleaseReceiptRepository(ZhaogangReleaseReceiptMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<LocalDateTime> findReadAt(long codingTeamId, long codingUserId, String releaseId) {
        ReleaseReceiptEntity entity = mapper.find(codingTeamId, codingUserId, releaseId);
        return Optional.ofNullable(entity).map(ReleaseReceiptEntity::getReadAt);
    }

    @Override
    public LocalDateTime markRead(long codingTeamId, long codingUserId, String releaseId) {
        mapper.upsert(codingTeamId, codingUserId, releaseId);
        ReleaseReceiptEntity entity = mapper.find(codingTeamId, codingUserId, releaseId);
        if (entity == null || entity.getReadAt() == null) {
            throw new IllegalStateException("版本已读状态保存失败");
        }
        return entity.getReadAt();
    }
}
