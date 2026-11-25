package com.itwray.iw.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.IssueWorklogEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ZhaogangIterationIssueWorklogMapper extends BaseMapper<IssueWorklogEntity> {

    @org.apache.ibatis.annotations.Select("SELECT id FROM external_zhaogang_iteration_issue WHERE id=#{issueId} AND iteration_id=#{iterationId} AND deleted=0 FOR UPDATE")
    Long lockIssue(@Param("iterationId") long iterationId, @Param("issueId") long issueId);

    @org.apache.ibatis.annotations.Select("""
            SELECT w.* FROM external_zhaogang_iteration_issue_worklog w
            JOIN external_zhaogang_iteration t ON t.id=w.iteration_id
            WHERE w.iteration_id=#{iterationId} AND w.deleted=0 AND w.coding_missing=0
            AND NOT EXISTS (SELECT 1 FROM external_zhaogang_worklog_deleted_entry d
                WHERE d.coding_team_key=t.team_key AND d.worklog_id=w.coding_worklog_id)
            ORDER BY w.id
            """)
    List<IssueWorklogEntity> visibleForIteration(@Param("iterationId") long iterationId);

    @Delete("<script>delete from external_zhaogang_iteration_issue_worklog where iteration_id = #{iterationId} "
            + "and issue_id in <foreach collection='issueIds' item='id' open='(' separator=',' close=')'>"
            + "#{id}</foreach></script>")
    int deleteByIssueIds(@Param("iterationId") long iterationId, @Param("issueIds") List<Long> issueIds);
}
