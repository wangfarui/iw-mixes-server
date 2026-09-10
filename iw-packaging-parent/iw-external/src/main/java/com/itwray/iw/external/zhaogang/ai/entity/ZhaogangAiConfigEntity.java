package com.itwray.iw.external.zhaogang.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("external_zhaogang_ai_config")
public class ZhaogangAiConfigEntity {

    private Long codingTeamId;
    private Long codingUserId;
    private String apiUrl;
    private String apiKey;
    private String model;
    private String executionLocation;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
