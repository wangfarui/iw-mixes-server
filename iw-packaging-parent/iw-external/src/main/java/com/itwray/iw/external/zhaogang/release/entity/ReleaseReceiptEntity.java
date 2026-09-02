package com.itwray.iw.external.zhaogang.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("external_zhaogang_release_receipt")
public class ReleaseReceiptEntity {

    private Long codingTeamId;
    private Long codingUserId;
    private String releaseId;
    private LocalDateTime readAt;
}
