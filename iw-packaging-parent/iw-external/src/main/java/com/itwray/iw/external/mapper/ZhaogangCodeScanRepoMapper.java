package com.itwray.iw.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.ScanRepoEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface ZhaogangCodeScanRepoMapper extends BaseMapper<ScanRepoEntity> {

    @Insert("""
            insert into external_zhaogang_code_scan_repo
              (job_id, project_id, project_name, project_display_name, depot_id, depot_name, web_url,
               branch_count, commit_count, status, error_code, error_message)
            values
              (#{jobId}, #{projectId}, #{projectName}, #{projectDisplayName}, #{depotId}, #{depotName}, #{webUrl},
               #{branchCount}, #{commitCount}, #{status}, #{errorCode}, #{errorMessage})
            on duplicate key update
              project_name = values(project_name), project_display_name = values(project_display_name),
              depot_name = values(depot_name), web_url = values(web_url), branch_count = values(branch_count),
              commit_count = values(commit_count), status = values(status), error_code = values(error_code),
              error_message = values(error_message), update_time = current_timestamp
            """)
    int upsert(ScanRepoEntity repo);

    @Select("select * from external_zhaogang_code_scan_repo where job_id = #{jobId} order by depot_id")
    List<ScanRepoEntity> selectByJob(@Param("jobId") long jobId);

    @Select("""
            select * from external_zhaogang_code_scan_repo
             where job_id = #{jobId} and status = 'FAILED'
             order by depot_id
            """)
    List<ScanRepoEntity> selectFailures(@Param("jobId") long jobId);

    @Select("""
            select * from external_zhaogang_code_scan_repo
             where job_id = #{jobId} and status in ('PENDING', 'RUNNING')
             order by depot_id
            """)
    List<ScanRepoEntity> selectUnfinished(@Param("jobId") long jobId);

    @Update("""
            update external_zhaogang_code_scan_repo
               set status = #{status}, branch_count = #{branchCount}, commit_count = #{commitCount},
                   error_code = #{errorCode}, error_message = #{errorMessage}, update_time = current_timestamp
             where job_id = #{jobId} and depot_id = #{depotId}
            """)
    int updateState(@Param("jobId") long jobId, @Param("depotId") long depotId, @Param("status") String status,
                    @Param("branchCount") int branchCount, @Param("commitCount") int commitCount,
                    @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage);


    @Select("""
            select r.* from external_zhaogang_code_scan_repo r
              join external_zhaogang_code_scan_job j on j.id=r.job_id
             where j.coding_team_id=#{codingTeamId} and j.scope_type=#{scopeType} and j.scope_id=#{scopeId}
               and j.scan_mode in ('SYNC','SYNC_V2','SNAPSHOT_V1') and j.identity_signature=#{signature} and j.sync_from=#{from}
               and r.depot_id=#{depotId} and r.sync_state is not null
               and j.id < #{jobId}
             order by j.id desc limit 1
            """)
    ScanRepoEntity selectSyncCheckpoint(@Param("codingTeamId") long codingTeamId,
            @Param("scopeType") String scopeType, @Param("scopeId") long scopeId,
            @Param("depotId") long depotId, @Param("signature") String signature,
            @Param("from") java.time.LocalDate from, @Param("jobId") long jobId);

    @Update("""
            update external_zhaogang_code_scan_repo set sync_state=#{state}, update_time=current_timestamp
             where job_id=#{jobId} and depot_id=#{depotId}
            """)
    int saveSyncCheckpoint(@Param("jobId") long jobId, @Param("depotId") long depotId,
                           @Param("state") String state);

    @Update("""
            update external_zhaogang_code_scan_repo r
              join external_zhaogang_code_scan_job j on j.id=r.job_id
               set r.sync_state=null
             where j.coding_team_id=#{codingTeamId} and j.scope_type=#{scopeType} and j.scope_id=#{scopeId}
               and r.depot_id=#{depotId} and j.id < #{jobId} and r.sync_state is not null
            """)
    int retireOldCheckpoints(@Param("codingTeamId") long codingTeamId, @Param("scopeType") String scopeType,
            @Param("scopeId") long scopeId, @Param("depotId") long depotId, @Param("jobId") long jobId);

    @Delete("""
            <script>delete from external_zhaogang_code_scan_repo where job_id in
            <foreach collection="ids" item="id" open="(" close=")" separator=",">#{id}</foreach></script>
            """)
    int deleteJobIds(@Param("ids") List<Long> ids);

    @Delete("delete from external_zhaogang_code_scan_repo where job_id in " +
            "(select id from external_zhaogang_code_scan_job where create_time < #{cutoff} and scan_mode <> 'SYNC')")
    int deleteByJobBefore(@Param("cutoff") LocalDateTime cutoff);
}
