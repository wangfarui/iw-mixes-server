package com.itwray.iw.external.zhaogang.codestats;

import lombok.Data;

import java.time.LocalDateTime;

/** 提交作者聚合行，仅用于未归属候选。 */
@Data
public class CodeAuthorRow {
    private String authorEmailHash;
    private String authorNameKey;
    private String authorName;
    private Long commitCount;
    private Integer repoCount;
    private LocalDateTime lastCommitAt;
}
