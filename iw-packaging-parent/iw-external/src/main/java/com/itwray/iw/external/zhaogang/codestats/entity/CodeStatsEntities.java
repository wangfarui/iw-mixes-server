package com.itwray.iw.external.zhaogang.codestats.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

public final class CodeStatsEntities {

    private CodeStatsEntities() {
    }

    @Data
    @TableName("external_zhaogang_code_scan_job")
    public static class ScanJobEntity {
        @TableId(value = "id", type = IdType.AUTO)
        private Long id;
        private Long codingTeamId;
        private String scopeType;
        private Long scopeId;
        private String month;
        private String scanMode;
        private String status;
        private String stage;
        private String activeKey;
        private Integer totalRepos;
        private Integer doneRepos;
        private Integer totalBranches;
        private Integer doneBranches;
        private Integer totalCommits;
        private Integer doneCommits;
        private Integer totalDiffs;
        private Integer doneDiffs;
        private Integer failedRepos;
        private Integer failedDiffs;
        private Boolean truncated;
        private String errorCode;
        private String errorMessage;
        private Long requestedBy;
        private String requestedMode;
        private String identitySignature;
        private LocalDate syncFrom;
        private LocalDate syncTo;
        private String syncTeamKey;
        private String syncTeamHost;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
        private LocalDateTime heartbeatAt;
        private LocalDateTime createTime;
        private LocalDateTime updateTime;
        @TableField(exist = false)
        private boolean coverageIncomplete;
    }

    @Data
    @TableName("external_zhaogang_code_scan_repo")
    public static class ScanRepoEntity {
        @TableId(value = "id", type = IdType.AUTO)
        private Long id;
        private Long jobId;
        private Long projectId;
        private String projectName;
        private String projectDisplayName;
        private Long depotId;
        private String depotName;
        private String webUrl;
        private String syncState;
        private Integer branchCount;
        private Integer commitCount;
        private String status;
        private String errorCode;
        private String errorMessage;
        private LocalDateTime createTime;
        private LocalDateTime updateTime;
    }

    @Data
    @TableName("external_zhaogang_code_commit")
    public static class CommitEntity {
        @TableId(value = "id", type = IdType.AUTO)
        private Long id;
        private Long codingTeamId;
        private String scopeType;
        private Long scopeId;
        private String month;
        private Long projectId;
        private String projectName;
        private String projectDisplayName;
        private Long depotId;
        private String depotName;
        private String sha;
        private String shortSha;
        private String authorName;
        private String authorEmailHash;
        private String authorNameKey;
        private LocalDateTime commitAt;
        private LocalDate commitDate;
        private String message;
        private String branchNames;
        private Integer branchCount;
        private Boolean branchTruncated;
        private Long additions;
        private Long deletions;
        private Integer filesChanged;
        private String fileTypeStats;
        private String diffStatus;
        private Boolean isMerge;
        private String commitUrl;
        private Long lastSeenScanId;
        private LocalDateTime createTime;
        private LocalDateTime updateTime;
    }

    @Data
    @TableName("external_zhaogang_code_identity")
    public static class IdentityEntity {
        @TableId(value = "id", type = IdType.AUTO)
        private Long id;
        private Long codingTeamId;
        private String scopeType;
        private Long scopeId;
        private String identityType;
        private String identityKey;
        private Long codingUserId;
        private Boolean ignored;
        private String authorName;
        private String source;
        private Long updatedBy;
        private LocalDateTime createTime;
        private LocalDateTime updateTime;
    }
}
