package com.itwray.iw.external.zhaogang.iteration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.itwray.iw.web.model.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public final class TeamIterationEntities {

    private TeamIterationEntities() {
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @TableName("external_zhaogang_iteration")
    public static class IterationEntity extends BaseEntity<Long> {
        @TableId(value = "id", type = IdType.AUTO)
        private Long id;
        private String requestId;
        private String teamKey;
        private String name;
        private String version;
        private String stage;
        private LocalDate startDate;
        private LocalDate plannedReleaseDate;
        private Long creatorUserId;
        private String creatorUserName;
        private String creatorAvatar;
        private Long updaterUserId;
        private String updaterUserName;
        private Integer versionNo;
        private LocalDateTime releasedAt;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @TableName("external_zhaogang_iteration_member")
    public static class MemberEntity extends BaseEntity<Long> {
        @TableId(value = "id", type = IdType.AUTO)
        private Long id;
        private Long iterationId;
        private Long workbenchTeamId;
        private String workbenchTeamName;
        private Long codingUserId;
        private String userName;
        private String avatar;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @TableName("external_zhaogang_iteration_member_role")
    public static class MemberRoleEntity extends BaseEntity<Long> {
        @TableId(value = "id", type = IdType.AUTO)
        private Long id;
        private Long memberId;
        private String role;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @TableName("external_zhaogang_iteration_issue")
    public static class IssueEntity extends BaseEntity<Long> {
        @TableId(value = "id", type = IdType.AUTO)
        private Long id;
        private Long iterationId;
        private Long parentId;
        private String source;
        private String codingUrl;
        private String urlHash;
        private String projectName;
        private Long issueId;
        private Long issueCode;
        private String issueType;
        private String codingSystemType;
        private Long codingIssueTypeId;
        private String issueTypeName;
        private String title;
        private String description;
        private String developmentTeam;
        private String definitionOfDone;
        private BigDecimal estimatedHours;
        private String taskType;
        private BigDecimal codingRecordedHours;
        private Integer codingWorklogCount;
        private Boolean onlineBug;
        private String bugPriority;
        private String syncStatus;
        private String syncMessage;
        private String syncErrorCode;
        private LocalDateTime syncStartedAt;
        private Integer syncAttemptCount;
        private LocalDateTime syncedAt;
        private Long codingParentCode;
        private Long creatorUserId;
        private String creatorUserName;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @TableName("external_zhaogang_iteration_issue_worklog")
    public static class IssueWorklogEntity extends BaseEntity<Long> {
        @TableId(value = "id", type = IdType.AUTO)
        private Long id;
        private Long iterationId;
        private Long issueId;
        private BigDecimal spendHours;
        private LocalDateTime registeredAt;
        private String syncStatus;
        private String syncMessage;
        private String syncErrorCode;
        private LocalDateTime syncStartedAt;
        private Integer syncAttemptCount;
        private String codingRequestId;
        private LocalDateTime syncedAt;
        private Long creatorUserId;
        private String creatorUserName;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @TableName("external_zhaogang_iteration_release_plan")
    public static class ReleasePlanEntity extends BaseEntity<Long> {
        @TableId(value = "id", type = IdType.AUTO)
        private Long id;
        private Long iterationId;
        private Long codingProjectId;
        private String codingProjectName;
        private String projectDisplayName;
        private Long codingPlanId;
        private String planName;
        private Boolean quickBuildSupported;
        private Long creatorUserId;
        private String creatorUserName;
        private String creatorAvatar;
    }
}
