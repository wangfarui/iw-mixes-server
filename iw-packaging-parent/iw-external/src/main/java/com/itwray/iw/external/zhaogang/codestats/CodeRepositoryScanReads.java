package com.itwray.iw.external.zhaogang.codestats;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingBranchHead;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingCommitDiff;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingCommitPage;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingCommitQuery;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;

import java.util.List;
import java.util.function.Supplier;

/** Raw repository reads shared by one automatic sync round, before team-specific attribution. */
final class CodeRepositoryScanReads {
    private record Access(long organization, String credential) {
        static Access of(long organization, String token) {
            return new Access(organization, CodingCredentialService.fingerprint(token));
        }
    }

    private record BranchRead(Access access, long project, long depot, boolean verification) {}
    private record CommitRead(Access access, CodingCommitQuery query) {}
    private record DiffRead(Access access, long project, long depot, String sha) {}

    private final Cache<BranchRead, List<CodingBranchHead>> branches = Caffeine.newBuilder()
            .maximumWeight(200_000)
            .weigher((BranchRead key, List<CodingBranchHead> rows) -> Math.max(1, rows.size()))
            .build();
    private final Cache<CommitRead, CodingCommitPage> commits = Caffeine.newBuilder()
            .maximumWeight(50_000)
            .weigher((CommitRead key, CodingCommitPage page) -> Math.max(1, page.items().size()))
            .build();
    private final Cache<DiffRead, CodingCommitDiff> diffs = Caffeine.newBuilder().maximumSize(4096).build();

    List<CodingBranchHead> branches(CodingOpenApiPort coding, long org, String token, long project, long depot,
                                   boolean verification) {
        var key = new BranchRead(Access.of(org, token), project, depot, verification);
        return branches.get(key, ignored -> List.copyOf(coding.branchHeads(token, project, depot, "CODING")));
    }

    CodingCommitPage commits(long org, String token, CodingCommitQuery query, Supplier<CodingCommitPage> load) {
        return commits.get(new CommitRead(Access.of(org, token), query), ignored -> load.get());
    }

    CodingCommitDiff diff(CodingOpenApiPort coding, long org, String token, long project, long depot, String sha) {
        return diffs.get(new DiffRead(Access.of(org, token), project, depot, sha),
                ignored -> coding.commitDiff(token, project, depot, sha));
    }
}
