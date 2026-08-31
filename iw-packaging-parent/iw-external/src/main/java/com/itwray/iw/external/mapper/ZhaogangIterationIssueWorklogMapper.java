package com.itwray.iw.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.IssueWorklogEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ZhaogangIterationIssueWorklogMapper extends BaseMapper<IssueWorklogEntity> {

    @Delete("<script>delete from external_zhaogang_iteration_issue_worklog where iteration_id = #{iterationId} "
            + "and issue_id in <foreach collection='issueIds' item='id' open='(' separator=',' close=')'>"
            + "#{id}</foreach></script>")
    int deleteByIssueIds(@Param("iterationId") long iterationId, @Param("issueIds") List<Long> issueIds);
}
