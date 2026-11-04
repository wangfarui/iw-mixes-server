package com.itwray.iw.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.ScanJobEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface ZhaogangCodeScanJobMapper extends BaseMapper<ScanJobEntity> {

    @Select("""
            select * from external_zhaogang_code_scan_job
             where coding_team_id = #{codingTeamId} and scope_type = #{scopeType} and scope_id = #{scopeId}
               and ((scan_mode in ('SYNC','SYNC_V2','SNAPSHOT_V1') and sync_from <= concat(#{month}, '-01')
                    and sync_to >= least(last_day(concat(#{month}, '-01')), current_date))
                    or (scan_mode not in ('SYNC','SYNC_V2','SNAPSHOT_V1') and month = #{month}))
             order by id desc limit 1
            """)
    ScanJobEntity selectLatest(@Param("codingTeamId") long codingTeamId, @Param("scopeType") String scopeType,
                               @Param("scopeId") long scopeId, @Param("month") String month);

    @Select("""
            select * from external_zhaogang_code_scan_job
             where coding_team_id = #{codingTeamId} and scope_type = #{scopeType} and scope_id = #{scopeId}
               and ((scan_mode in ('SYNC','SYNC_V2','SNAPSHOT_V1') and sync_from <= concat(#{month}, '-01')
                    and sync_to >= least(last_day(concat(#{month}, '-01')), current_date))
                    or (scan_mode not in ('SYNC','SYNC_V2','SNAPSHOT_V1') and month = #{month})) and status in ('COMPLETED', 'PARTIAL')
             order by finished_at desc, id desc limit 1
            """)
    ScanJobEntity selectLatestSuccessful(@Param("codingTeamId") long codingTeamId,
                                         @Param("scopeType") String scopeType,
                                         @Param("scopeId") long scopeId, @Param("month") String month);


    @Select("""
            select j.* from external_zhaogang_code_scan_job j
             join (select coding_team_id, scope_type, scope_id, max(id) id
                     from external_zhaogang_code_scan_job where scan_mode='SYNC_V2'
                    group by coding_team_id,scope_type,scope_id) s on s.id=j.id
            """)
    List<ScanJobEntity> selectSyncSubscriptions();

    @Select("""
            select * from external_zhaogang_code_scan_job
             where coding_team_id=#{codingTeamId} and scope_type=#{scopeType} and scope_id=#{scopeId}
               and scan_mode='SYNC_V2' order by id desc limit 1
            """)
    ScanJobEntity selectLatestSync(@Param("codingTeamId") long codingTeamId,
                                 @Param("scopeType") String scopeType, @Param("scopeId") long scopeId);

    @Select("select * from external_zhaogang_code_scan_job where active_key = #{activeKey} limit 1")
    ScanJobEntity selectByActiveKey(@Param("activeKey") String activeKey);

    @Select("""
            select * from external_zhaogang_code_scan_job
             where status in ('QUEUED', 'RUNNING') and heartbeat_at < #{cutoff}
            """)
    List<ScanJobEntity> selectStaleActive(@Param("cutoff") LocalDateTime cutoff);

    @Update("""
            update external_zhaogang_code_scan_job
               set status = #{status}, error_code = #{errorCode}, error_message = #{errorMessage},
                   active_key = null, finished_at = #{finishedAt}, update_time = current_timestamp
             where id = #{id} and status in ('QUEUED', 'RUNNING')
            """)
    int finishActive(@Param("id") long id, @Param("status") String status,
                     @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage,
                     @Param("finishedAt") LocalDateTime finishedAt);

    @Update("""
            update external_zhaogang_code_scan_job
               set status = #{status}, stage = #{stage}, active_key = #{activeKey},
                   started_at = #{startedAt}, finished_at = #{finishedAt}, heartbeat_at = current_timestamp,
                   total_repos = #{totalRepos}, done_repos = #{doneRepos},
                   total_branches = #{totalBranches}, done_branches = #{doneBranches},
                   total_commits = #{totalCommits}, done_commits = #{doneCommits},
                   total_diffs = #{totalDiffs}, done_diffs = #{doneDiffs},
                   failed_repos = #{failedRepos}, failed_diffs = #{failedDiffs},
                   identity_signature = #{identitySignature},
                   truncated = #{truncated}, error_code = #{errorCode}, error_message = #{errorMessage},
                   update_time = current_timestamp
             where id = #{id} and status in ('QUEUED', 'RUNNING')
            """)
    int updateProgress(ScanJobEntity job);

    @Update("""
            update external_zhaogang_code_scan_job
               set status = #{status}, stage = #{stage}, active_key = #{activeKey},
                   started_at = #{startedAt}, finished_at = #{finishedAt}, heartbeat_at = current_timestamp,
                   total_repos = #{totalRepos}, done_repos = #{doneRepos},
                   total_branches = #{totalBranches}, done_branches = #{doneBranches},
                   total_commits = #{totalCommits}, done_commits = #{doneCommits},
                   total_diffs = #{totalDiffs}, done_diffs = #{doneDiffs},
                   failed_repos = #{failedRepos}, failed_diffs = #{failedDiffs},
                   identity_signature = #{identitySignature},
                   truncated = #{truncated}, error_code = #{errorCode}, error_message = #{errorMessage},
                   update_time = current_timestamp
             where id = #{id}
            """)
    int updateFinal(ScanJobEntity job);

    @Update("""
            update external_zhaogang_code_scan_job
               set heartbeat_at = current_timestamp, update_time = current_timestamp
             where id = #{id} and status = 'RUNNING'
            """)
    int heartbeat(@Param("id") long id);

    @Select("""
            select j.id from external_zhaogang_code_scan_job j
              left join (select coding_team_id,scope_type,scope_id,max(id) latest,
                         max(case when status in ('COMPLETED','PARTIAL') then id end) successful
                           from external_zhaogang_code_scan_job where scan_mode='SYNC_V2'
                          group by coding_team_id,scope_type,scope_id) k
                on k.coding_team_id=j.coding_team_id and k.scope_type=j.scope_type and k.scope_id=j.scope_id
             where j.create_time < #{cutoff} and j.status not in ('QUEUED','RUNNING')
               and (j.scan_mode <> 'SYNC_V2' or (j.id <> k.latest and (k.successful is null or j.id <> k.successful)))
               and not exists (select 1 from external_zhaogang_code_scan_repo r where r.job_id=j.id and r.sync_state is not null)
            """)
    List<Long> selectExpiredJobIds(@Param("cutoff") LocalDateTime cutoff);

    @Delete("delete from external_zhaogang_code_scan_job where create_time < #{cutoff} and scan_mode not in ('SYNC','SYNC_V2','SNAPSHOT_V1')")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff);
}
