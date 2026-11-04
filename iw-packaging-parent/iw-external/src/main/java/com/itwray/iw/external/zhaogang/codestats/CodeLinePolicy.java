package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.CommitEntity;

/** 仓库级行数统计口径；提交计数及归属规则不受影响。 */
final class CodeLinePolicy {
    static final String COMMIT_COUNT_ONLY_DEPOT = "online.base.apijar.service";
    static final String SKIPPED_REPOSITORY = "SKIPPED_REPOSITORY";
    static final String CACHE_VERSION = "apijar-count-only-v1";

    private CodeLinePolicy() {}

    static boolean commitCountOnly(String depotName) {
        return COMMIT_COUNT_ONLY_DEPOT.equals(depotName);
    }

    static void apply(CommitEntity commit) {
        if (commitCountOnly(commit.getDepotName()) && !Boolean.TRUE.equals(commit.getIsMerge())) {
            commit.setAdditions(null);
            commit.setDeletions(null);
            commit.setFilesChanged(null);
            commit.setDiffStatus(SKIPPED_REPOSITORY);
        }
    }
}
