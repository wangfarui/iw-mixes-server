package com.itwray.iw.external.zhaogang.agent.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class ZhaogangAgentTokenEntity {

    private Long codingTeamId;

    private Long codingUserId;

    private String userName;

    private String avatar;

    private String tokenHash;

    private String tokenHint;

    private LocalDateTime createdAt;

    private LocalDateTime lastUsedAt;
}
