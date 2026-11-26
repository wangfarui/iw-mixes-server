package com.itwray.iw.external.zhaogang.codestats;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.itwray.iw.external.mapper.ZhaogangCodeIdentityMapper;
import com.itwray.iw.external.zhaogang.team.OrganizationStatisticsDirectory.Membership;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.IdentityEntity;

@Component
public class CodeStatisticsSource {
    private static final int BATCH_SIZE = 1500;

    public record Key(long member, LocalDate date) {}
    public record Totals(long commits, long additions, long deletions, boolean linesIncomplete) {
        Totals plus(Totals other) {
            return new Totals(commits + other.commits, additions + other.additions,
                    deletions + other.deletions, linesIncomplete || other.linesIncomplete);
        }
    }

    public record Month(Map<Key, Totals> data, Set<LocalDate> ambiguousDates,
                        Set<Long> unmappedMembers, String reason, Long syncedAt, boolean repositoriesPartial) {}
    private record Scan(long id, String status, int repositories, int failedRepos, int failedDiffs,
                        boolean truncated, String error, LocalDateTime started, LocalDateTime finished) {
        boolean usableMetadata(LocalDateTime permittedSince) {
            return started != null && finished != null && (permittedSince == null || started.isAfter(permittedSince))
                    && repositories > 0 && !truncated
                    && (("COMPLETED".equals(status) && failedRepos == 0) || ("PARTIAL".equals(status)
                    && "PARTIAL_SCAN".equals(error) && (failedRepos > 0 || failedDiffs > 0)));
        }
    }

    private record Coverage(List<Scan> scans, String reason, Long syncedAt) {
        boolean repositoriesPartial() {
            return reason == null && scans.stream().anyMatch(scan -> scan.failedRepos > 0);
        }
    }

    private record Commit(long id, long depot, String sha, String repository, LocalDateTime at, LocalDate date,
                          String email, String name, boolean merge, String diff, Long additions, Long deletions,
                          boolean dateConflict) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final ZhaogangCodeIdentityMapper identityMapper;
    private final CodeIdentityResolver identities;

    CodeStatisticsSource(NamedParameterJdbcTemplate jdbc, ZhaogangCodeIdentityMapper identityMapper,
                         CodeIdentityResolver identities) {
        this.jdbc = jdbc;
        this.identityMapper = identityMapper;
        this.identities = identities;
    }

    public Month read(long org, YearMonth month, LocalDate today, LocalDate retentionStart,
                      long visibilityMs, List<Membership> memberships) {
        if (month.atEndOfMonth().isBefore(retentionStart)) {
            return new Month(Map.of(), Set.of(), Set.of(), "CODE_OUTSIDE_COVERAGE", null, false);
        }
        var developers = developers(memberships);
        if (developers.isEmpty()) return new Month(Map.of(), Set.of(), Set.of(), null, null, false);
        var teams = memberships.stream().map(Membership::teamId).distinct().toList();
        var coverage = coverage(org, month, today, visibilityMs, memberships, teams);
        if (coverage.reason() != null) {
            return new Month(Map.of(), Set.of(), Set.of(), coverage.reason(), coverage.syncedAt(), false);
        }
        boolean repositoriesPartial = coverage.repositoriesPartial();
        var verifiedScanIds = repositoriesPartial ? coverage.scans().stream().map(Scan::id).toList() : List.<Long>of();

        var userIds = memberships.stream().map(Membership::userId).distinct().toList();
        var mappings = identityMapper.selectList(Wrappers.<IdentityEntity>lambdaQuery()
                .eq(IdentityEntity::getCodingTeamId, org).eq(IdentityEntity::getScopeType, CodeIdentityResolver.SELF)
                .in(IdentityEntity::getScopeId, userIds));
        var mappingIndex = identities.mappingIndex(mappings);
        Set<Long> coveredIdentities = mappingIndex.values().stream().map(IdentityEntity::getCodingUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> unmappedMembers = developers.stream().map(Membership::userId)
                .filter(id -> !coveredIdentities.contains(id)).collect(Collectors.toSet());

        // Include all current team scopes in the union where metadata is established for the
        // developers. Commit keys are grouped across scopes and consumed in bounded batches.
        Map<Key, Totals> data = new HashMap<>();
        Set<LocalDate> ambiguous = new HashSet<>();
        List<Commit> group = new ArrayList<>();
        long depot = 0;
        String sha = "";
        long id = 0;
        List<Commit> batch;
        do {
            batch = commits(org, teams, month, depot, sha, id, verifiedScanIds);
            for (Commit commit : batch) {
                if (!group.isEmpty() && (commit.depot != depot || !commit.sha.equals(sha))) {
                    accumulate(group, mappingIndex, data, ambiguous);
                    group.clear();
                }
                group.add(commit);
                depot = commit.depot;
                sha = commit.sha;
                id = commit.id;
            }
        } while (batch.size() == BATCH_SIZE);
        if (!group.isEmpty()) accumulate(group, mappingIndex, data, ambiguous);
        return new Month(data, ambiguous, unmappedMembers, null, coverage.syncedAt(), repositoriesPartial);
    }

    private static List<Membership> developers(List<Membership> memberships) {
        return memberships.stream()
                .filter(m -> !m.ignored() && ("FRONTEND".equals(m.role()) || "BACKEND".equals(m.role()))).toList();
    }

    private Coverage coverage(long org, YearMonth month, LocalDate today, long visibilityMs,
                              List<Membership> memberships, List<Long> teams) {
        LocalDate yesterday = today.minusDays(1);
        LocalDate coveredThrough = yesterday.isBefore(month.atEndOfMonth()) ? yesterday : month.atEndOfMonth();
        var scans = jdbc.query("""
                SELECT id, status, total_repos, failed_repos, failed_diffs, truncated, error_code, started_at, finished_at
                FROM (
                    SELECT j.*, ROW_NUMBER() OVER (PARTITION BY scope_id ORDER BY id DESC) AS position_no
                    FROM external_zhaogang_code_scan_job j
                    WHERE coding_team_id=:org AND scope_type='WORKBENCH_TEAM' AND scope_id IN (:teams)
                    AND ((scan_mode IN ('SYNC','SYNC_V2','SNAPSHOT_V1') AND sync_from <= :from AND sync_to >= :to)
                      OR (scan_mode NOT IN ('SYNC','SYNC_V2','SNAPSHOT_V1','DIFF_RETRY','DIFF_BACKFILL') AND month=:month))
                ) scans WHERE position_no=1
                """, Map.of("org", org, "teams", teams, "from", month.atDay(1), "to", coveredThrough, "month", month.toString()),
                (rs, n) -> new Scan(rs.getLong("id"), rs.getString("status"), rs.getInt("total_repos"),
                        rs.getInt("failed_repos"), rs.getInt("failed_diffs"), rs.getBoolean("truncated"),
                        rs.getString("error_code"), rs.getObject("started_at", LocalDateTime.class),
                        rs.getObject("finished_at", LocalDateTime.class)));
        LocalDateTime permittedSince = CodeStatsSupport.fromMillis(visibilityMs);
        long syncedAt = scans.stream().filter(scan -> scan.usableMetadata(permittedSince)
                        && scan.repositories > scan.failedRepos)
                .mapToLong(scan -> scan.finished.atZone(CodeStatsSupport.CHINA_ZONE).toInstant().toEpochMilli()).max().orElse(0);
        Long syncTime = syncedAt == 0 ? null : syncedAt;
        if (memberships.stream().anyMatch(member -> !member.codeCredentialAvailable())) {
            return new Coverage(scans, "CODE_COVERAGE", syncTime);
        }
        if (scans.stream().anyMatch(scan -> "RUNNING".equals(scan.status()) || "QUEUED".equals(scan.status()))) {
            return new Coverage(scans, "CODE_SYNC_RUNNING", syncTime);
        }
        if (syncTime == null || scans.size() != teams.size() || scans.stream().anyMatch(scan -> !scan.usableMetadata(permittedSince))) {
            return new Coverage(scans, "CODE_COVERAGE", syncTime);
        }
        return new Coverage(scans, null, syncTime);
    }

    private List<Commit> commits(long org, List<Long> teams, YearMonth month, long depot, String sha, long afterId,
                                 List<Long> verifiedScanIds) {
        boolean partial = !verifiedScanIds.isEmpty();
        return jdbc.query("""
                SELECT c.id, c.depot_id, c.sha, c.depot_name, c.commit_at, c.commit_date, c.author_email_hash,
                    c.author_name_key, c.is_merge, c.diff_status, c.additions, c.deletions,
                    EXISTS (SELECT 1 FROM external_zhaogang_code_commit d FORCE INDEX (idx_org_depot_sha)
                        WHERE d.coding_team_id=c.coding_team_id AND d.scope_type='WORKBENCH_TEAM'
                        AND d.scope_id IN (:teams) AND d.depot_id=c.depot_id AND d.sha=c.sha AND d.month<>c.month
                        %s
                    ) AS date_conflict
                FROM external_zhaogang_code_commit c
                WHERE c.coding_team_id=:org AND c.scope_type='WORKBENCH_TEAM' AND c.scope_id IN (:teams)
                AND c.month=:month AND (c.depot_id > :depot
                  OR (c.depot_id=:depot AND (c.sha > :sha OR (c.sha=:sha AND c.id > :afterId))))
                %s
                ORDER BY c.depot_id, c.sha, c.id LIMIT :limit
                """.formatted(partial ? verifiedRepositories("d") : "", partial ? verifiedRepositories("c") : ""),
                Map.of("org", org, "teams", teams, "month", month.toString(), "depot", depot,
                "sha", sha, "afterId", afterId, "limit", BATCH_SIZE, "scans", verifiedScanIds), (rs, n) -> new Commit(rs.getLong("id"),
                rs.getLong("depot_id"), rs.getString("sha"), rs.getString("depot_name"),
                rs.getObject("commit_at", LocalDateTime.class), rs.getObject("commit_date", LocalDate.class),
                rs.getString("author_email_hash"), rs.getString("author_name_key"), rs.getBoolean("is_merge"),
                rs.getString("diff_status"), rs.getObject("additions", Long.class), rs.getObject("deletions", Long.class),
                rs.getBoolean("date_conflict")));
    }

    // A failed repository can retain old rows in the mirror, including after permission failures.
    // Keep contributions from other team scopes where the same repository was verified.
    private static String verifiedRepositories(String commit) {
        return """
                AND EXISTS (
                    SELECT 1 FROM external_zhaogang_code_scan_job j
                    WHERE j.id IN (:scans) AND j.scope_id=%1$s.scope_id
                    AND (j.failed_repos=0 OR EXISTS (
                        SELECT 1 FROM external_zhaogang_code_scan_repo r
                        WHERE r.job_id=j.id AND r.depot_id=%1$s.depot_id
                        AND (r.status='COMPLETED' OR (r.status='SKIPPED' AND r.error_code='BRANCH_CHANGED'))
                    ))
                )
                """.formatted(commit);
    }

    private void accumulate(List<Commit> group, Map<String, IdentityEntity> mappings,
                            Map<Key, Totals> data, Set<LocalDate> ambiguous) {
        Commit first = group.get(0);
        if (group.stream().anyMatch(other -> other.dateConflict || first.merge != other.merge
                || !Objects.equals(first.at, other.at) || !Objects.equals(first.date, other.date)
                || !Objects.equals(first.email, other.email) || !Objects.equals(first.name, other.name)
                || !Objects.equals(first.repository, other.repository))) {
            group.forEach(row -> ambiguous.add(row.date));
            return;
        }
        if (first.merge) return;
        var resolved = identities.resolve(mappings, first.email, first.name);
        if (resolved.memberId() == null) {
            ambiguous.add(first.date);
            return;
        }
        Totals contribution = new Totals(1, 0, 0, false);
        if (!CodeLinePolicy.commitCountOnly(first.repository)) {
            var valid = group.stream().filter(row -> "OK".equals(row.diff) && row.additions != null && row.deletions != null)
                    .toList();
            Commit diff = valid.isEmpty() ? null : valid.get(0);
            boolean conflict = diff != null && valid.stream().anyMatch(row ->
                    !row.additions.equals(diff.additions) || !row.deletions.equals(diff.deletions));
            contribution = diff == null || conflict ? new Totals(1, 0, 0, true)
                    : new Totals(1, diff.additions, diff.deletions, false);
        }
        data.merge(new Key(resolved.memberId(), first.date), contribution, Totals::plus);
    }
}
