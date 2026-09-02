package com.itwray.iw.external.zhaogang.k8s.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("external_zhaogang_k8s_token")
public class K8sTokenEntity {

    @TableId
    private Long codingTeamId;

    private Long codingUserId;

    private String environment;

    private String tokenPlaintext;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
