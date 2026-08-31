package com.itwray.iw.external.zhaogang.credential.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Server-side CODING credentials used only by the team worklog aggregator. */
@Getter
@Setter
@TableName("external_zhaogang_coding_credential")
public class CodingCredentialEntity {

    @TableId
    private Long codingTeamId;

    private Long codingUserId;

    private String tokenPlaintext;

    private String tokenFingerprint;

    private String userName;

    private String avatar;

    private LocalDateTime lastVerifiedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
