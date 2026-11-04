package com.itwray.iw.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itwray.iw.external.zhaogang.codestats.CodeAuthorRow;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.CommitEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

public interface ZhaogangCodeCommitMapper extends BaseMapper<CommitEntity> {

    @Insert("""
            <script>
            insert into external_zhaogang_code_commit
              (coding_team_id, scope_type, scope_id, month, project_id, project_name, project_display_name,
               depot_id, depot_name, sha, short_sha, author_name, author_email_hash, author_name_key,
               commit_at, commit_date, message, branch_names, branch_count, branch_truncated,
               additions, deletions, files_changed, diff_status, is_merge, commit_url, last_seen_scan_id)
            values
            <foreach collection="list" item="item" separator=",">
              (#{item.codingTeamId}, #{item.scopeType}, #{item.scopeId}, #{item.month}, #{item.projectId},
               #{item.projectName}, #{item.projectDisplayName}, #{item.depotId}, #{item.depotName}, #{item.sha},
               #{item.shortSha}, #{item.authorName}, #{item.authorEmailHash}, #{item.authorNameKey},
               #{item.commitAt}, #{item.commitDate}, #{item.message}, #{item.branchNames}, #{item.branchCount},
               #{item.branchTruncated}, #{item.additions}, #{item.deletions}, #{item.filesChanged},
               #{item.diffStatus}, #{item.isMerge}, #{item.commitUrl}, #{item.lastSeenScanId})
            </foreach>
            on duplicate key update
              project_name = values(project_name), project_display_name = values(project_display_name),
              depot_name = values(depot_name), author_name = values(author_name),
              author_email_hash = values(author_email_hash), author_name_key = values(author_name_key),
              commit_at = values(commit_at), commit_date = values(commit_date), message = values(message),
              branch_names = values(branch_names), branch_count = values(branch_count),
              branch_truncated = values(branch_truncated), is_merge = values(is_merge),
              commit_url = values(commit_url), last_seen_scan_id = values(last_seen_scan_id),
              additions = case when values(diff_status) = 'SKIPPED_REPOSITORY' then null else additions end,
              deletions = case when values(diff_status) = 'SKIPPED_REPOSITORY' then null else deletions end,
              files_changed = case when values(diff_status) = 'SKIPPED_REPOSITORY' then null else files_changed end,
              diff_status = case when values(diff_status) = 'SKIPPED_REPOSITORY' then values(diff_status) else diff_status end,
              update_time = current_timestamp
            </script>
            """)
    int upsertBatch(@Param("list") List<CommitEntity> list);

    @Update("""
            update external_zhaogang_code_commit
               set additions = null, deletions = null, files_changed = null,
                   diff_status = 'SKIPPED_REPOSITORY', update_time = current_timestamp
             where coding_team_id = #{codingTeamId} and scope_type = #{scopeType} and scope_id = #{scopeId}
               and month = #{month} and binary depot_name = binary #{depotName} and is_merge = 0
               and (diff_status <> 'SKIPPED_REPOSITORY' or additions is not null
                    or deletions is not null or files_changed is not null)
            """)
    int applyCommitCountOnlyPolicy(@Param("codingTeamId") long codingTeamId, @Param("scopeType") String scopeType,
                                  @Param("scopeId") long scopeId, @Param("month") String month,
                                  @Param("depotName") String depotName);

    @Select("""
            select * from external_zhaogang_code_commit
             where coding_team_id = #{codingTeamId} and scope_type = #{scopeType} and scope_id = #{scopeId}
               and commit_date between #{from} and #{to} and is_merge = 0
             order by commit_at desc, id desc
            """)
    List<CommitEntity> selectForRange(@Param("codingTeamId") long codingTeamId, @Param("scopeType") String scopeType,
                                      @Param("scopeId") long scopeId, @Param("from") LocalDate from,
                                      @Param("to") LocalDate to);

    @Select("""
            select * from external_zhaogang_code_commit
             where coding_team_id = #{codingTeamId} and scope_type = #{scopeType} and scope_id = #{scopeId}
               and month = #{month} and is_merge = 0 and diff_status in ('PENDING', 'FAILED')
             order by id
            """)
    List<CommitEntity> selectPendingDiffs(@Param("codingTeamId") long codingTeamId,
                                          @Param("scopeType") String scopeType, @Param("scopeId") long scopeId,
                                          @Param("month") String month);

    @Select("""
            select * from external_zhaogang_code_commit
             where coding_team_id = #{codingTeamId} and scope_type = #{scopeType} and scope_id = #{scopeId}
               and month = #{month}
            """)
    List<CommitEntity> selectByMonth(@Param("codingTeamId") long codingTeamId,
                                     @Param("scopeType") String scopeType, @Param("scopeId") long scopeId,
                                     @Param("month") String month);

    @Select("""
            select count(*) from external_zhaogang_code_commit
             where coding_team_id = #{codingTeamId} and scope_type = #{scopeType} and scope_id = #{scopeId}
               and commit_date between #{from} and #{to} and is_merge = 1
            """)
    long countMerges(@Param("codingTeamId") long codingTeamId, @Param("scopeType") String scopeType,
                     @Param("scopeId") long scopeId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Update("""
            update external_zhaogang_code_commit
               set additions = #{additions}, deletions = #{deletions}, files_changed = #{filesChanged},
                   diff_status = #{diffStatus}, update_time = current_timestamp
             where id = #{id}
            """)
    int updateDiff(@Param("id") long id, @Param("additions") Long additions, @Param("deletions") Long deletions,
                   @Param("filesChanged") Integer filesChanged, @Param("diffStatus") String diffStatus);

    @Update("""
            update external_zhaogang_code_commit
               set diff_status = #{diffStatus}, update_time = current_timestamp
             where id = #{id}
            """)
    int updateDiffStatus(@Param("id") long id, @Param("diffStatus") String diffStatus);

    @Update("""
            update external_zhaogang_code_commit
               set diff_status = 'PENDING', update_time = current_timestamp
             where coding_team_id = #{codingTeamId} and scope_type = #{scopeType} and scope_id = #{scopeId}
               and diff_status = 'SKIPPED_UNMAPPED'
               and ((#{identityType} = 'EMAIL' and author_email_hash = #{identityKey})
                 or (#{identityType} = 'NAME' and author_name_key = #{identityKey}))
            """)
    int rependIdentityCommits(@Param("codingTeamId") long codingTeamId, @Param("scopeType") String scopeType,
                              @Param("scopeId") long scopeId, @Param("identityType") String identityType,
                              @Param("identityKey") String identityKey);

    @Select("""
            select distinct month from external_zhaogang_code_commit
             where coding_team_id = #{codingTeamId} and scope_type = #{scopeType} and scope_id = #{scopeId}
               and ((#{identityType} = 'EMAIL' and author_email_hash = #{identityKey})
                 or (#{identityType} = 'NAME' and author_name_key = #{identityKey}))
            """)
    List<String> selectMonthsByAuthor(@Param("codingTeamId") long codingTeamId,
                                      @Param("scopeType") String scopeType, @Param("scopeId") long scopeId,
                                      @Param("identityType") String identityType,
                                      @Param("identityKey") String identityKey);

    @Select("""
            select author_email_hash, author_name_key, max(author_name) as author_name,
                   count(*) as commit_count, count(distinct depot_id) as repo_count, max(commit_at) as last_commit_at
              from external_zhaogang_code_commit
             where coding_team_id = #{codingTeamId} and scope_type = #{scopeType} and scope_id = #{scopeId}
               and is_merge = 0
             group by author_email_hash, author_name_key
            """)
    List<CodeAuthorRow> selectAuthorAggregates(@Param("codingTeamId") long codingTeamId,
                                               @Param("scopeType") String scopeType,
                                               @Param("scopeId") long scopeId);

    @Select("""
            select count(*) from external_zhaogang_code_commit
             where coding_team_id = #{codingTeamId} and scope_type = #{scopeType} and scope_id = #{scopeId}
               and commit_date between #{from} and #{to} and is_merge = 0
               and diff_status in ('PENDING', 'FAILED', 'TOO_LARGE')
               and binary depot_name <> 'online.base.apijar.service'
            """)
    long countIncompleteDiffs(@Param("codingTeamId") long codingTeamId, @Param("scopeType") String scopeType,
                              @Param("scopeId") long scopeId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Select("""
            select * from external_zhaogang_code_commit
             where coding_team_id = #{codingTeamId} and scope_type = #{scopeType} and scope_id = #{scopeId}
               and commit_date between #{from} and #{to} and diff_status in ('FAILED', 'TOO_LARGE') and is_merge = 0
               and binary depot_name <> 'online.base.apijar.service'
             order by commit_at desc
            """)
    List<CommitEntity> selectDiffFailures(@Param("codingTeamId") long codingTeamId,
                                          @Param("scopeType") String scopeType, @Param("scopeId") long scopeId,
                                          @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Delete("""
            delete from external_zhaogang_code_commit
             where coding_team_id = #{codingTeamId} and scope_type = #{scopeType} and scope_id = #{scopeId}
               and month = #{month} and last_seen_scan_id <> #{jobId}
            """)
    int cleanupFullScan(@Param("codingTeamId") long codingTeamId, @Param("scopeType") String scopeType,
                        @Param("scopeId") long scopeId, @Param("month") String month, @Param("jobId") long jobId);

    @Delete("""
            <script>
            delete from external_zhaogang_code_commit
             where coding_team_id=#{codingTeamId} and scope_type=#{scopeType} and scope_id=#{scopeId}
               and month=#{month} and id in
               <foreach collection="ids" item="id" open="(" close=")" separator=",">#{id}</foreach>
            </script>
            """)
    int deleteUnmatchedRows(@Param("codingTeamId") long codingTeamId, @Param("scopeType") String scopeType,
                            @Param("scopeId") long scopeId, @Param("month") String month,
                            @Param("ids") List<Long> ids);

    @Select("""
            select * from external_zhaogang_code_commit
             where coding_team_id=#{codingTeamId} and scope_type=#{scopeType} and scope_id=#{scopeId}
               and is_merge=1 and commit_date between #{from} and #{to}
            """)
    List<CommitEntity> selectMerges(@Param("codingTeamId") long codingTeamId, @Param("scopeType") String scopeType,
                                  @Param("scopeId") long scopeId, @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);


    @Select("""
            select * from external_zhaogang_code_commit
             where coding_team_id=#{codingTeamId} and scope_type=#{scopeType} and scope_id=#{scopeId}
               and depot_id=#{depotId} and commit_date between #{from} and #{to}
            """)
    List<CommitEntity> selectSyncRepo(@Param("codingTeamId") long codingTeamId,
            @Param("scopeType") String scopeType, @Param("scopeId") long scopeId,
            @Param("depotId") long depotId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Delete("""
            delete from external_zhaogang_code_commit
             where coding_team_id=#{codingTeamId} and scope_type=#{scopeType} and scope_id=#{scopeId}
               and depot_id=#{depotId} and commit_date between #{from} and #{to}
               and last_seen_scan_id <> #{jobId}
            """)
    int cleanupSyncRepo(@Param("codingTeamId") long codingTeamId,
            @Param("scopeType") String scopeType, @Param("scopeId") long scopeId,
            @Param("depotId") long depotId, @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("jobId") long jobId);

    @Delete("delete from external_zhaogang_code_commit where month < #{cutoffMonth}")
    int deleteBeforeMonth(@Param("cutoffMonth") String cutoffMonth);
}
