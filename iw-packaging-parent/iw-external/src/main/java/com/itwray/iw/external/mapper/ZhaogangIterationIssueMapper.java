package com.itwray.iw.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.IssueEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ZhaogangIterationIssueMapper extends BaseMapper<IssueEntity> {

    @Delete("<script>delete from external_zhaogang_iteration_issue where iteration_id = #{iterationId} and id in "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteOwned(@Param("iterationId") long iterationId, @Param("ids") List<Long> ids);
}
