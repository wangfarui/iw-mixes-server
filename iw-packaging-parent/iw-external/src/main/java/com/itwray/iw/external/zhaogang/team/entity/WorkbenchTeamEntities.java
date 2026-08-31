package com.itwray.iw.external.zhaogang.team.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

public final class WorkbenchTeamEntities {

    private WorkbenchTeamEntities() {
    }

    @Data
    @TableName("external_zhaogang_workbench_team")
    public static class TeamEntity {
        @TableId(value = "id", type = IdType.AUTO)
        private Long id;
        private String requestId;
        private String name;
        private String inviteCode;
        private Long codingTeamId;
        private String codingTeamKey;
        private String codingTeamHost;
        private Long creatorUserId;
        private Long administratorUserId;
        private Integer versionNo;
        private LocalDateTime createTime;
        private LocalDateTime updateTime;
    }

    @Data
    @TableName("external_zhaogang_workbench_team_member")
    public static class MemberEntity {
        @TableId(value = "id", type = IdType.AUTO)
        private Long id;
        private Long teamId;
        private Long codingUserId;
        private String userName;
        private String avatar;
        private Integer sortNo;
        private LocalDateTime createTime;
        private LocalDateTime updateTime;
    }
}
