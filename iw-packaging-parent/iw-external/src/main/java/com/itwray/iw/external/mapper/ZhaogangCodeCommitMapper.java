package com.itwray.iw.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itwray.iw.external.zhaogang.codestats.CodeAuthorRow;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.CommitEntity;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public interface ZhaogangCodeCommitMapper extends BaseMapper<CommitEntity> {
    int FILE_TYPES_BACKFILL_BATCH_SIZE = 30;

    @Insert("""
            <script>
            insert into external_zhaogang_shared_code_commit
              (coding_team_id, scope_type, scope_id, month, project_id, project_name, project_display_name,
               depot_id, depot_name, sha, short_sha, author_name, author_email_hash, author_name_key,
               commit_at, commit_date, message, branch_names, branch_count, branch_truncated,
               additions, deletions, files_changed, diff_status, is_merge, commit_url, last_seen_scan_id)
            values
            <foreach collection="list" item="item" separator=",">
              (#{item.codingTeamId}, 'SHARED', 0, #{item.month}, #{item.projectId},
               #{item.projectName}, #{item.projectDisplayName}, #{item.depotId}, #{item.depotName}, #{item.sha},
               #{item.shortSha}, #{item.authorName}, #{item.authorEmailHash}, #{item.authorNameKey},
               #{item.commitAt}, #{item.commitDate}, #{item.message}, #{item.branchNames}, #{item.branchCount},
               #{item.branchTruncated}, #{item.additions}, #{item.deletions}, #{item.filesChanged},
               #{item.diffStatus}, #{item.isMerge}, #{item.commitUrl}, #{item.lastSeenScanId})
            </foreach>
            on duplicate key update
              project_name = values(project_name), project_display_name = values(project_display_name),
              depot_name = values(depot_name),
              author_conflict = author_conflict OR
                  (author_email_hash IS NOT NULL AND values(author_email_hash) IS NOT NULL
                   AND author_email_hash != values(author_email_hash)) OR
                  (author_name_key != '' AND values(author_name_key) != '' AND author_name_key != values(author_name_key)),
              author_name = values(author_name),
              author_email_hash = coalesce(values(author_email_hash), author_email_hash),
              author_name_key = coalesce(nullif(values(author_name_key),''), author_name_key),
              commit_at = values(commit_at), commit_date = values(commit_date), message = values(message),
              branch_names = if(values(branch_count) >= branch_count, values(branch_names), branch_names),
              branch_truncated = if(values(branch_count) >= branch_count, values(branch_truncated), branch_truncated),
              branch_count = greatest(branch_count, values(branch_count)), is_merge = values(is_merge),
              commit_url = values(commit_url), last_seen_scan_id = values(last_seen_scan_id),
              additions = case when values(diff_status) = 'SKIPPED_REPOSITORY' then null else additions end,
              deletions = case when values(diff_status) = 'SKIPPED_REPOSITORY' then null else deletions end,
              files_changed = case when values(diff_status) = 'SKIPPED_REPOSITORY' then null else files_changed end,
              file_type_stats = case when values(diff_status) = 'SKIPPED_REPOSITORY' then null else file_type_stats end,
              diff_status = case when values(diff_status) = 'SKIPPED_REPOSITORY' then values(diff_status) else diff_status end,
              update_time = current_timestamp
            </script>
            """)
    int upsertBatch(@Param("list") List<CommitEntity> list);

    @Update("""
            update external_zhaogang_shared_code_commit
               set additions = null, deletions = null, files_changed = null, file_type_stats = null,
                   diff_status = 'SKIPPED_REPOSITORY', update_time = current_timestamp
             where coding_team_id = #{codingTeamId}
               and month = #{month} and binary depot_name = binary #{depotName} and is_merge = 0
               and (diff_status <> 'SKIPPED_REPOSITORY' or additions is not null
                    or deletions is not null or files_changed is not null or file_type_stats is not null)
            """)
    int applyCommitCountOnlyPolicy(@Param("codingTeamId") long codingTeamId, @Param("scopeType") String scopeType,
                                  @Param("scopeId") long scopeId, @Param("month") String month,
                                  @Param("depotName") String depotName);

    @Select("""
            <script>
            select * from external_zhaogang_shared_code_commit
             where coding_team_id=#{org} and commit_date between #{from} and #{to}
               and is_merge=#{merge}
               and depot_id in
               <foreach collection="depots" item="depot" open="(" close=")" separator=",">#{depot}</foreach>
             order by commit_at desc, id desc
            </script>
            """)
    List<CommitEntity> selectVisible(@Param("org") long org, @Param("depots") java.util.Set<Long> depots,
                                     @Param("from") LocalDate from, @Param("to") LocalDate to,
                                     @Param("merge") boolean merge);

    @Select("""
            select * from external_zhaogang_shared_code_commit
             where coding_team_id = #{codingTeamId}
               and commit_date between #{from} and #{to} and is_merge = 0
             order by commit_at desc, id desc
            """)
    List<CommitEntity> selectForRange(@Param("codingTeamId") long codingTeamId, @Param("scopeType") String scopeType,
                                      @Param("scopeId") long scopeId, @Param("from") LocalDate from,
                                      @Param("to") LocalDate to);

    @Select("""
            select * from external_zhaogang_shared_code_commit
             where coding_team_id = #{codingTeamId}
               and month = #{month} and is_merge = 0
               and diff_status in ('PENDING', 'FAILED', 'DIFF_RATE_LIMITED', 'DIFF_TIMEOUT', 'DIFF_UNAVAILABLE')
             order by id
            """)
    List<CommitEntity> selectPendingDiffs(@Param("codingTeamId") long codingTeamId,
                                          @Param("scopeType") String scopeType, @Param("scopeId") long scopeId,
                                          @Param("month") String month);

    @Select("""
            select * from external_zhaogang_shared_code_commit
             where coding_team_id = #{codingTeamId}
               and month = #{month} and is_merge = 0 and diff_status = 'PENDING'
             order by id
            """)
    List<CommitEntity> selectNewDiffs(@Param("codingTeamId") long codingTeamId, @Param("scopeType") String scopeType,
                                     @Param("scopeId") long scopeId, @Param("month") String month);

    @Select("""
            <script>
            select c.id, c.depot_id, c.commit_date from external_zhaogang_shared_code_commit c
             where c.coding_team_id = #{codingTeamId} and c.scope_type = 'SHARED' and c.scope_id = 0
               and c.commit_date between #{from} and #{to} and c.is_merge = 0
               and binary c.depot_name != 'online.base.apijar.service'
               and c.diff_status in ('FAILED', 'DIFF_RATE_LIMITED', 'DIFF_TIMEOUT', 'DIFF_UNAVAILABLE',
                                    'DIFF_INVALID_RESPONSE', 'DIFF_REQUEST_FAILED', 'DIFF_PERMISSION_DENIED',
                                    'TOO_LARGE', 'DIFF_UPSTREAM_LIMIT', 'DIFF_RESPONSE_LIMIT', 'DIFF_INVALID_ARGUMENT')
               and (c.diff_retry_after is null or c.diff_retry_after &lt;= #{now})
               and exists (
                   select 1 from external_zhaogang_code_access a
                    where a.coding_team_id = c.coding_team_id
                      and a.credential_fingerprint in
                      <foreach collection="fingerprints" item="fp" open="(" separator="," close=")">#{fp}</foreach>
                      and json_contains(a.depot_ids, cast(c.depot_id as json))
                      and not exists (
                          select 1 from external_zhaogang_code_diff_attempt denied
                           where denied.commit_id = c.id and denied.credential_fingerprint = a.credential_fingerprint
                             and denied.read_method = 'DIFF' and denied.failure_reason = 'PERMISSION_DENIED'
                      )
                      and (
                        (c.diff_status in ('TOO_LARGE', 'DIFF_UPSTREAM_LIMIT', 'DIFF_RESPONSE_LIMIT', 'DIFF_INVALID_ARGUMENT')
                         and (
                            not exists (
                                select 1 from external_zhaogang_code_diff_attempt compared
                                 where compared.commit_id = c.id
                                   and compared.credential_fingerprint = a.credential_fingerprint
                                   and compared.read_method = 'COMPARE' and compared.terminal = 1
                            ) or (#{gitAvailable} and not exists (
                                select 1 from external_zhaogang_code_diff_attempt blocked
                                 where blocked.commit_id = c.id
                                   and blocked.credential_fingerprint = a.credential_fingerprint
                                   and ((blocked.read_method = 'GIT' and blocked.terminal = 1)
                                       or (blocked.read_method = 'COMPARE' and blocked.failure_reason in ('PERMISSION_DENIED', 'MERGE')))
                            ))
                         )
                         and (c.diff_retry_attempts &lt; #{maxAttempts} or not exists (
                             select 1 from external_zhaogang_code_diff_attempt prior
                              where prior.commit_id = c.id and prior.credential_fingerprint = a.credential_fingerprint
                         )))
                        or (c.diff_status not in ('TOO_LARGE', 'DIFF_UPSTREAM_LIMIT', 'DIFF_RESPONSE_LIMIT', 'DIFF_INVALID_ARGUMENT')
                            and c.diff_retry_attempts &lt; #{maxAttempts}
                            and (c.diff_retry_credential_signature is null
                                or c.diff_retry_credential_signature != #{legacySignature}
                                or exists (select 1 from external_zhaogang_code_diff_attempt prior where prior.commit_id = c.id)))
                      )
               )
             order by (c.commit_date >= #{to} - interval 30 day) desc, c.diff_retry_attempts, c.commit_date desc, c.id desc
             limit #{batchSize}
            </script>
            """)
    List<CommitEntity> selectAutomaticDiffRetries(@Param("codingTeamId") long codingTeamId,
            @Param("fingerprints") Set<String> fingerprints, @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("now") LocalDateTime now, @Param("legacySignature") String legacySignature, @Param("gitAvailable") boolean gitAvailable,
            @Param("maxAttempts") int maxAttempts, @Param("batchSize") int batchSize);

    record DiffReadAttempt(String credentialFingerprint, String method, String reason, boolean terminal) {}

    @ConstructorArgs({
            @Arg(column = "credential_fingerprint", javaType = String.class),
            @Arg(column = "read_method", javaType = String.class),
            @Arg(column = "failure_reason", javaType = String.class),
            @Arg(column = "terminal", javaType = boolean.class)
    })
    @Select("""
            select credential_fingerprint, read_method, failure_reason, terminal
              from external_zhaogang_code_diff_attempt where commit_id = #{commitId}
            """)
    List<DiffReadAttempt> selectDiffReadAttempts(@Param("commitId") long commitId);

    @Insert("""
            <script>
            insert into external_zhaogang_code_diff_attempt
                (commit_id, credential_fingerprint, read_method, failure_reason, terminal)
            values
            <foreach collection="attempts" item="item" separator=",">
                 (#{commitId}, #{item.credentialFingerprint}, #{item.method}, #{item.reason}, #{item.terminal})
            </foreach>
            on duplicate key update failure_reason = values(failure_reason), terminal = values(terminal)
            </script>
            """)
    int recordDiffReadAttempts(@Param("commitId") long commitId, @Param("attempts") List<DiffReadAttempt> attempts);

    @Update("""
            update external_zhaogang_shared_code_commit
               set additions = null, deletions = null, files_changed = null, file_type_stats = null,
                   diff_status = #{diffStatus}, diff_retry_attempts = #{attempts}, diff_retry_after = #{retryAfter},
                   diff_retry_credential_signature = #{credentialSignature}, diff_retry_error = #{retryError},
                   update_time = current_timestamp
             where id = #{id} and diff_status <> 'OK'
            """)
    int updateFailedDiffWithRetryState(@Param("id") long id, @Param("diffStatus") String diffStatus,
                                       @Param("attempts") int attempts, @Param("retryAfter") LocalDateTime retryAfter,
                                       @Param("credentialSignature") String credentialSignature, @Param("retryError") String retryError);

    @Select("""
            select * from external_zhaogang_shared_code_commit
             where coding_team_id = #{codingTeamId}
               and month = #{month}
            """)
    List<CommitEntity> selectByMonth(@Param("codingTeamId") long codingTeamId,
                                     @Param("scopeType") String scopeType, @Param("scopeId") long scopeId,
                                     @Param("month") String month);

    @Select("""
            select count(*) from external_zhaogang_shared_code_commit
             where coding_team_id = #{codingTeamId}
               and commit_date between #{from} and #{to} and is_merge = 1
            """)
    long countMerges(@Param("codingTeamId") long codingTeamId, @Param("scopeType") String scopeType,
                     @Param("scopeId") long scopeId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Update("""
            update external_zhaogang_shared_code_commit
               set additions = #{additions}, deletions = #{deletions}, files_changed = #{filesChanged},
                   file_type_stats = #{fileTypeStats},
                   diff_retry_attempts = if(#{diffStatus} = 'OK' or diff_status <> #{diffStatus}, 0, diff_retry_attempts),
                   diff_retry_after = if(#{diffStatus} = 'OK' or diff_status <> #{diffStatus}, null, diff_retry_after),
                   diff_retry_credential_signature = if(#{diffStatus} = 'OK' or diff_status <> #{diffStatus}, null, diff_retry_credential_signature),
                   diff_retry_error = if(#{diffStatus} = 'OK' or diff_status <> #{diffStatus}, null, diff_retry_error),
                   diff_status = #{diffStatus}, update_time = current_timestamp
             where id = #{id} and (diff_status <> 'OK' or #{diffStatus} = 'OK')
            """)
    int updateDiff(@Param("id") long id, @Param("additions") Long additions, @Param("deletions") Long deletions,
                   @Param("filesChanged") Integer filesChanged, @Param("diffStatus") String diffStatus,
                   @Param("fileTypeStats") String fileTypeStats);

    @Update("""
            update external_zhaogang_shared_code_commit
               set additions = #{additions}, deletions = #{deletions}, files_changed = #{filesChanged},
                   file_type_stats = null, file_types_checked_at = current_timestamp,
                   diff_status = 'OK', diff_retry_attempts = 0, diff_retry_after = null,
                   diff_retry_credential_signature = null, diff_retry_error = null, update_time = current_timestamp
             where id = #{id} and diff_status <> 'OK'
            """)
    int updateComparedDiff(@Param("id") long id, @Param("additions") long additions,
                           @Param("deletions") long deletions, @Param("filesChanged") int filesChanged);

    @Select("""
            select c.* from external_zhaogang_shared_code_commit c
             where coding_team_id = #{codingTeamId}
               and is_merge = 0 and diff_status = 'OK'
               and binary depot_name <> 'online.base.apijar.service'
               and additions is not null and deletions is not null
               and (additions > 0 or deletions > 0)
               and (file_types_checked_at is null or file_types_checked_at < #{retryBefore})
               and not exists (
                   select 1
                     from json_table(c.file_type_stats, '$.*' columns (
                         added bigint path '$.additions', deleted bigint path '$.deletions'
                     )) as classified
                   having sum(added) = c.additions and sum(deleted) = c.deletions
                      and min(added) >= 0 and min(deleted) >= 0
                      and count(added) = count(*) and count(deleted) = count(*)
               )
             order by case when commit_date between #{priorityFrom} and #{priorityTo} then 0 else 1 end,
                      file_types_checked_at, commit_date desc, id desc
             limit #{batchSize}
            """)
    List<CommitEntity> selectFileTypesBackfill(@Param("codingTeamId") long codingTeamId, @Param("scopeId") long scopeId,
                                               @Param("retryBefore") LocalDateTime retryBefore,
                                               @Param("priorityFrom") LocalDate priorityFrom,
                                               @Param("priorityTo") LocalDate priorityTo,
                                               @Param("batchSize") int batchSize);

    @Update("""
            update external_zhaogang_shared_code_commit
               set file_type_stats = coalesce(cast(#{fileTypeStats} as json), file_type_stats),
                   file_types_checked_at = current_timestamp
             where id = #{id} and diff_status = 'OK'
               and additions = #{additions} and deletions = #{deletions}
               and file_type_stats <=> cast(#{previousStats} as json)
            """)
    int updateFileTypesBackfill(@Param("id") long id, @Param("additions") long additions,
                                @Param("deletions") long deletions, @Param("previousStats") String previousStats,
                                @Param("fileTypeStats") String fileTypeStats);

    @Update("""
            update external_zhaogang_shared_code_commit
               set diff_status = #{diffStatus}, update_time = current_timestamp
             where id = #{id}
            """)
    int updateDiffStatus(@Param("id") long id, @Param("diffStatus") String diffStatus);

    @Update("""
            update external_zhaogang_shared_code_commit
               set diff_status = 'PENDING', update_time = current_timestamp
             where coding_team_id = #{codingTeamId}
               and diff_status = 'SKIPPED_UNMAPPED'
               and ((#{identityType} = 'EMAIL' and author_email_hash = #{identityKey})
                 or (#{identityType} = 'NAME' and author_name_key = #{identityKey}))
            """)
    int rependIdentityCommits(@Param("codingTeamId") long codingTeamId, @Param("scopeType") String scopeType,
                              @Param("scopeId") long scopeId, @Param("identityType") String identityType,
                              @Param("identityKey") String identityKey);

    @Select("""
            select distinct month from external_zhaogang_shared_code_commit
             where coding_team_id = #{codingTeamId}
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
              from external_zhaogang_shared_code_commit
             where coding_team_id = #{codingTeamId}
               and is_merge = 0
             group by author_email_hash, author_name_key
            """)
    List<CodeAuthorRow> selectAuthorAggregates(@Param("codingTeamId") long codingTeamId,
                                               @Param("scopeType") String scopeType,
                                               @Param("scopeId") long scopeId);

    @Select("""
            select count(*) from external_zhaogang_shared_code_commit
             where coding_team_id = #{codingTeamId}
               and commit_date between #{from} and #{to} and is_merge = 0
               and (diff_status in ('PENDING', 'FAILED', 'TOO_LARGE') or left(diff_status, 5) = 'DIFF_')
               and binary depot_name <> 'online.base.apijar.service'
            """)
    long countIncompleteDiffs(@Param("codingTeamId") long codingTeamId, @Param("scopeType") String scopeType,
                              @Param("scopeId") long scopeId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Select("""
            select * from external_zhaogang_shared_code_commit
             where coding_team_id = #{codingTeamId}
               and commit_date between #{from} and #{to} and is_merge = 0
               and (diff_status in ('FAILED', 'TOO_LARGE') or left(diff_status, 5) = 'DIFF_')
               and binary depot_name <> 'online.base.apijar.service'
             order by commit_at desc, id desc
            """)
    List<CommitEntity> selectDiffFailures(@Param("codingTeamId") long codingTeamId,
                                          @Param("scopeType") String scopeType, @Param("scopeId") long scopeId,
                                          @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Delete("""
            delete from external_zhaogang_shared_code_commit
             where coding_team_id = #{codingTeamId}
               and month = #{month} and last_seen_scan_id <> #{jobId}
               and not exists (
                    select 1 from external_zhaogang_code_scan_repo r
                     where r.job_id = #{jobId} and r.depot_id = external_zhaogang_shared_code_commit.depot_id
                       and r.status = 'SKIPPED' and r.error_code = 'BRANCH_CHANGED'
               )
            """)
    int cleanupFullScan(@Param("codingTeamId") long codingTeamId, @Param("scopeType") String scopeType,
                        @Param("scopeId") long scopeId, @Param("month") String month, @Param("jobId") long jobId);

    @Delete("""
            <script>
            delete from external_zhaogang_shared_code_commit
             where coding_team_id=#{codingTeamId}
               and month=#{month} and id in
               <foreach collection="ids" item="id" open="(" close=")" separator=",">#{id}</foreach>
            </script>
            """)
    int deleteUnmatchedRows(@Param("codingTeamId") long codingTeamId, @Param("scopeType") String scopeType,
                            @Param("scopeId") long scopeId, @Param("month") String month,
                            @Param("ids") List<Long> ids);

    @Select("""
            select * from external_zhaogang_shared_code_commit
             where coding_team_id=#{codingTeamId}
               and is_merge=1 and commit_date between #{from} and #{to}
            """)
    List<CommitEntity> selectMerges(@Param("codingTeamId") long codingTeamId, @Param("scopeType") String scopeType,
                                  @Param("scopeId") long scopeId, @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);


    @Select("""
            select * from external_zhaogang_shared_code_commit
             where coding_team_id=#{codingTeamId}
               and depot_id=#{depotId} and commit_date between #{from} and #{to}
            """)
    List<CommitEntity> selectSyncRepo(@Param("codingTeamId") long codingTeamId,
            @Param("scopeType") String scopeType, @Param("scopeId") long scopeId,
            @Param("depotId") long depotId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Delete("""
            delete from external_zhaogang_shared_code_commit
             where coding_team_id=#{codingTeamId}
               and depot_id=#{depotId} and commit_date between #{from} and #{to}
               and last_seen_scan_id <> #{jobId}
            """)
    int cleanupSyncRepo(@Param("codingTeamId") long codingTeamId,
            @Param("scopeType") String scopeType, @Param("scopeId") long scopeId,
            @Param("depotId") long depotId, @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("jobId") long jobId);

    @Delete("delete from external_zhaogang_shared_code_commit where month < #{cutoffMonth}")
    int deleteBeforeMonth(@Param("cutoffMonth") String cutoffMonth);
}
