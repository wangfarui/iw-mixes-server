package com.itwray.iw.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.ReleasePlanEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface ZhaogangIterationReleasePlanMapper extends BaseMapper<ReleasePlanEntity> {

    @Delete("delete from external_zhaogang_iteration_release_plan where iteration_id = #{iterationId} and id = #{id}")
    int deleteOwned(@Param("iterationId") long iterationId, @Param("id") long id);
}
