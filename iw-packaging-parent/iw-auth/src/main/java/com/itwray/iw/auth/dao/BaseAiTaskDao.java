package com.itwray.iw.auth.dao;

import com.itwray.iw.auth.mapper.BaseAiTaskMapper;
import com.itwray.iw.auth.model.entity.BaseAiTaskEntity;
import com.itwray.iw.web.dao.BaseDao;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * AI任务表DAO
 *
 * @author wray
 * @since 2026-03-26
 */
@Component
public class BaseAiTaskDao extends BaseDao<BaseAiTaskMapper, BaseAiTaskEntity> {

    public void updateTop(Integer id, Integer isTop, LocalDateTime topTime) {
        lambdaUpdate()
                .eq(BaseAiTaskEntity::getId, id)
                .set(BaseAiTaskEntity::getIsTop, isTop)
                .set(BaseAiTaskEntity::getTopTime, topTime)
                .set(BaseAiTaskEntity::getUpdateTime, LocalDateTime.now())
                .update();
    }

    public void updateActive(Integer id, LocalDateTime activeTime) {
        lambdaUpdate()
                .eq(BaseAiTaskEntity::getId, id)
                .set(BaseAiTaskEntity::getLastActiveAt, activeTime)
                .set(BaseAiTaskEntity::getUpdateTime, activeTime)
                .update();
    }
}
