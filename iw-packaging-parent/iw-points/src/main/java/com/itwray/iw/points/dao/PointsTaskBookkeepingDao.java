package com.itwray.iw.points.dao;
import com.itwray.iw.points.mapper.PointsTaskBookkeepingMapper;
import com.itwray.iw.points.model.entity.PointsTaskBookkeepingEntity;
import com.itwray.iw.web.dao.BaseDao;
import org.springframework.stereotype.Component;
@Component
public class PointsTaskBookkeepingDao extends BaseDao<PointsTaskBookkeepingMapper, PointsTaskBookkeepingEntity> {
    public PointsTaskBookkeepingEntity byTaskId(Integer taskId) {
        return lambdaQuery().eq(PointsTaskBookkeepingEntity::getTaskId, taskId).one();
    }

    public PointsTaskBookkeepingEntity lockByTaskId(Integer taskId) {
        return lambdaQuery().eq(PointsTaskBookkeepingEntity::getTaskId, taskId).last("FOR UPDATE").one();
    }
}
