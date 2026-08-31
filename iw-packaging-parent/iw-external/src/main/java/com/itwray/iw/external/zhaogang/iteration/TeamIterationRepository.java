package com.itwray.iw.external.zhaogang.iteration;

import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Actor;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CodingIssueType;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CreateCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IssueSyncStatus;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IterationQuery;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.MemberInput;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Role;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Stage;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.UpdateCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.UpdateIssueCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.UserSnapshot;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.IterationEntity;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.IssueEntity;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.IssueWorklogEntity;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.MemberEntity;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.ReleasePlanEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

interface TeamIterationRepository {

    record StoredMember(MemberEntity member, List<Role> roles) {
    }

    record StoredIteration(IterationEntity iteration, List<StoredMember> members,
                           List<IssueEntity> issues, List<IssueWorklogEntity> worklogs,
                           List<ReleasePlanEntity> releasePlans) {
        StoredIteration(IterationEntity iteration, List<StoredMember> members, List<IssueEntity> issues) {
            this(iteration, members, issues, List.of(), List.of());
        }

        StoredIteration(IterationEntity iteration, List<StoredMember> members, List<IssueEntity> issues,
                        List<IssueWorklogEntity> worklogs) {
            this(iteration, members, issues, worklogs, List.of());
        }
    }

    record StoredPage(List<StoredIteration> items, long total, int pageNumber, int pageSize) {
    }

    Optional<StoredIteration> findByRequestId(String requestId);

    Optional<StoredIteration> findById(long iterationId);

    StoredPage findPage(IterationQuery query, long currentUserId, String teamKey);

    StoredIteration create(CreateCommand command, Actor actor, List<ResolvedMember> members);

    StoredIteration update(long iterationId, UpdateCommand command, Actor actor);

    StoredIteration updateStage(long iterationId, int versionNo, Stage stage, Actor actor);

    StoredIteration replaceMembers(long iterationId, int versionNo, Actor actor, List<ResolvedMember> members);

    void softDelete(long iterationId, Actor actor);

    IssueEntity addCodingIssue(long iterationId, Long parentId, String url, String urlHash, String projectName,
                               long issueId, long issueCode, CodingIssueType issueType,
                               String codingSystemType, long codingIssueTypeId, String issueTypeName,
                               String title, Actor actor);

    IssueEntity addChildIssue(long iterationId, long parentId, String projectName, CodingIssueType issueType,
                              String issueTypeName, String title, String description, String developmentTeam,
                              String definitionOfDone, BigDecimal estimatedHours, String taskType,
                              Boolean onlineBug, String bugPriority, IssueSyncStatus syncStatus, Actor actor);

    Optional<IssueEntity> findIssue(long iterationId, String urlHash);

    Optional<IssueEntity> findIssue(long iterationId, long issueId);

    IssueEntity updateIssue(long iterationId, long issueId, UpdateIssueCommand command, Actor actor);

    boolean claimIssueSync(long iterationId, long issueId, Actor actor);

    IssueEntity markIssueSynced(long iterationId, long issueId, String url, String urlHash, long codingIssueId,
                                long codingIssueCode, CodingIssueType issueType, String codingSystemType,
                                long codingIssueTypeId, String issueTypeName, String title, Long codingParentCode,
                                Actor actor);

    IssueEntity upsertCodingSnapshot(long iterationId, Long parentId, String url, String urlHash, String projectName,
                                     long codingIssueId, long codingIssueCode, CodingIssueType issueType,
                                     String codingSystemType, long codingIssueTypeId, String issueTypeName,
                                     String title, String description, String developmentTeam,
                                     String definitionOfDone, BigDecimal estimatedHours, String taskType,
                                     Long codingParentCode, Actor actor);

    IssueEntity updateCodingWorklogSummary(long iterationId, long issueId, BigDecimal recordedHours,
                                           int worklogCount, Actor actor);

    void markIssueSyncFailed(long iterationId, long issueId, IssueSyncStatus status, String errorCode,
                             String message, Actor actor);

    IssueWorklogEntity addWorklog(long iterationId, long issueId, BigDecimal spendHours,
                                  LocalDateTime registeredAt, IssueSyncStatus syncStatus, Actor actor);

    boolean claimWorklogSync(long worklogId);

    IssueWorklogEntity markWorklogSynced(long worklogId, String codingRequestId);

    IssueWorklogEntity markWorklogSyncFailed(long worklogId, IssueSyncStatus status, String errorCode,
                                              String message);

    void removeIssueTrees(long iterationId, List<Long> issueIds, Actor actor);

    Optional<ReleasePlanEntity> findReleasePlan(long iterationId, long projectId, long planId);

    ReleasePlanEntity addReleasePlan(long iterationId,
                                     TeamIterationReleasePlanResolver.ResolvedReleasePlan releasePlan,
                                     Actor actor);

    void removeReleasePlan(long iterationId, long releasePlanId, Actor actor);

    record ResolvedMember(long teamId, String teamName, UserSnapshot user, List<Role> roles) {
        ResolvedMember(UserSnapshot user, List<Role> roles) {
            this(0, "", user, roles);
        }
        static ResolvedMember from(UserSnapshot user, MemberInput input) {
            return new ResolvedMember(input.teamId(), "", user, input.roles());
        }
    }
}
