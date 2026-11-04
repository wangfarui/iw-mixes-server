package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.ZhaogangCodeCommitMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeScanJobMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeScanRepoMapper;
import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarDefaults;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.DayType;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarResolver;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Branch;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Coverage;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Daily;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Entry;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.EntryPage;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Hourly;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.MemberIssue;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.MemberMonthly;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.MemberStatistics;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Page;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.RepoIssue;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Repository;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.ScanState;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Scope;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.SelfStatistics;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.SelfSummary;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.TeamStatistics;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.TeamSummary;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.User;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.CommitEntity;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.IdentityEntity;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.ScanJobEntity;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.ScanRepoEntity;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
class CodeStatsQueryService {

    private final ZhaogangCodeCommitMapper commitMapper;
    private final ZhaogangCodeScanJobMapper jobMapper;
    private final ZhaogangCodeScanRepoMapper repoMapper;
    private final CodeIdentityResolver identities;
    private final WorkCalendarResolver calendar;

    CodeStatsQueryService(ZhaogangCodeCommitMapper commitMapper, ZhaogangCodeScanJobMapper jobMapper,
                          ZhaogangCodeScanRepoMapper repoMapper, CodeIdentityResolver identities,
                          WorkCalendarResolver calendar) {
        this.commitMapper = commitMapper;
        this.jobMapper = jobMapper;
        this.repoMapper = repoMapper;
        this.identities = identities;
        this.calendar = calendar;
    }

    TeamStatistics teamStatistics(CodeStatsModule.ScopeInfo scope, CodeRange range, ScanState scan,
                                  boolean refreshing, boolean dataReady) {
        List<CommitEntity> commits = commits(scope, range);
        Map<String, IdentityEntity> mappingIndex = identities.mappingIndex(identities.mappings(scope));
        Set<Long> memberIds = new HashSet<>();
        scope.members().forEach(member -> memberIds.add(member.userId()));
        Map<Long, MemberAccumulator> acc = new LinkedHashMap<>();
        scope.members().forEach(member -> acc.put(member.userId(), new MemberAccumulator(member)));
        int unmapped = 0;
        int ownedCommitCount = 0;
        long excludedMerges = commitMapper.selectMerges(scope.codingTeamId(), scope.scope().name(), scope.scopeId(),
                range.from(), range.to()).stream().filter(row -> {
                    var owner = identities.resolve(mappingIndex, row.getAuthorEmailHash(), row.getAuthorNameKey());
                    return !owner.ignored() && owner.memberId() != null && memberIds.contains(owner.memberId());
                }).count();
        for (CommitEntity commit : commits) {
            CodeIdentityResolver.Resolved resolved = identities.resolve(mappingIndex, commit.getAuthorEmailHash(),
                    commit.getAuthorNameKey());
            if (resolved.ignored()) continue;
            MemberAccumulator member = resolved.memberId() == null ? null : acc.get(resolved.memberId());
            if (member == null) {
                unmapped++;
                continue;
            }
            member.add(commit);
            ownedCommitCount++;
        }
        List<MemberMonthly> members = acc.values().stream()
                .sorted(Comparator.comparingLong((MemberAccumulator item) -> item.commitCount).reversed()
                        .thenComparing(item -> item.member.userName(), String.CASE_INSENSITIVE_ORDER))
                .map(MemberAccumulator::view).toList();
        long additions = members.stream().mapToLong(MemberMonthly::additions).sum();
        long deletions = members.stream().mapToLong(MemberMonthly::deletions).sum();
        int activeMembers = (int) members.stream().filter(member -> member.commitCount() > 0).count();
        Coverage coverage = coverage(scope, range, scan, commits, mappingIndex, memberIds, unmapped,
                excludedMerges);
        return new TeamStatistics(dataReady || !commits.isEmpty() || excludedMerges > 0, refreshing, range.view(), scan, coverage,
                new TeamSummary(ownedCommitCount, additions, deletions, additions - deletions, activeMembers,
                        scope.members().size(), 0), members);
    }

    SelfStatistics selfStatistics(CodeStatsModule.ScopeInfo scope, CodeRange range, ScanState scan,
                                  boolean refreshing, boolean dataReady) {
        List<CommitEntity> commits = commits(scope, range);
        Map<String, IdentityEntity> mappingIndex = identities.mappingIndex(identities.mappings(scope));
        Set<Long> memberIds = Set.of(scope.scopeId());
        List<CommitEntity> mine = new ArrayList<>();
        int unmatched = 0;
        for (CommitEntity commit : commits) {
            CodeIdentityResolver.Resolved resolved = identities.resolve(mappingIndex, commit.getAuthorEmailHash(),
                    commit.getAuthorNameKey());
            if (resolved.ignored()) continue;
            if (resolved.memberId() != null && memberIds.contains(resolved.memberId())) {
                mine.add(commit);
            } else {
                unmatched++;
            }
        }
        long excludedMerges = commitMapper.selectMerges(scope.codingTeamId(), scope.scope().name(), scope.scopeId(),
                range.from(), range.to()).stream().filter(row -> {
                    var owner = identities.resolve(mappingIndex, row.getAuthorEmailHash(), row.getAuthorNameKey());
                    return !owner.ignored() && owner.memberId() != null && memberIds.contains(owner.memberId());
                }).count();
        Coverage coverage = coverage(scope, range, scan, mine, mappingIndex, memberIds, unmatched, excludedMerges);
        SelfSummary summary = summary(mine);
        return new SelfStatistics(dataReady || !commits.isEmpty() || excludedMerges > 0, refreshing, range.view(), scan, coverage, summary, daily(range, mine, scope.codingTeamId()),
                hourly(mine), repositories(mine), branches(mine));
    }

    MemberStatistics memberStatistics(CodeStatsModule.ScopeInfo scope, CodeRange range, long memberUserId) {
        CodeStatsModule.MemberCredential member = scope.members().stream()
                .filter(item -> item.userId() == memberUserId).findFirst()
                .orElseThrow(() -> new CodingOpenApiException("指定成员不在当前工作台团队中"));
        Map<String, IdentityEntity> mappingIndex = identities.mappingIndex(identities.mappings(scope));
        List<CommitEntity> mine = commits(scope, range).stream()
                .filter(commit -> {
                    CodeIdentityResolver.Resolved resolved = identities.resolve(mappingIndex,
                            commit.getAuthorEmailHash(), commit.getAuthorNameKey());
                    return !resolved.ignored() && resolved.memberId() != null && resolved.memberId() == memberUserId;
                }).toList();
        return new MemberStatistics(new User(member.userId(), member.userName(), member.avatar()), summary(mine),
                daily(range, mine, scope.codingTeamId()));
    }

    EntryPage entries(CodeStatsModule.ScopeInfo scope, CodeRange range, Long depotId, String keyword,
                      int pageNumber, int pageSize) {
        Map<String, IdentityEntity> mappingIndex = identities.mappingIndex(identities.mappings(scope));
        String normalizedKeyword = StringUtils.trimToEmpty(keyword).toLowerCase(Locale.ROOT);
        List<CommitEntity> mine = commits(scope, range).stream()
                .filter(commit -> {
                    CodeIdentityResolver.Resolved resolved = identities.resolve(mappingIndex,
                            commit.getAuthorEmailHash(), commit.getAuthorNameKey());
                    return !resolved.ignored() && resolved.memberId() != null
                            && resolved.memberId() == scope.scopeId();
                })
                .filter(commit -> depotId == null || depotId <= 0 || commit.getDepotId().equals(depotId))
                .filter(commit -> normalizedKeyword.isEmpty()
                        || StringUtils.defaultString(commit.getMessage()).toLowerCase(Locale.ROOT)
                        .contains(normalizedKeyword))
                .toList();
        int safeSize = Math.max(1, Math.min(100, pageSize));
        int safePage = Math.max(1, pageNumber);
        int from = Math.min(mine.size(), (safePage - 1) * safeSize);
        int to = Math.min(mine.size(), from + safeSize);
        List<Entry> items = mine.subList(from, to).stream().map(this::toEntry).toList();
        return new EntryPage(range.view(), new Page(safePage, safeSize, mine.size(),
                mine.isEmpty() ? 0 : (mine.size() + safeSize - 1) / safeSize), items);
    }

    private List<CommitEntity> commits(CodeStatsModule.ScopeInfo scope, CodeRange range) {
        return commitMapper.selectForRange(scope.codingTeamId(), scope.scope().name(), scope.scopeId(),
                range.from(), range.to()).stream()
                .peek(CodeLinePolicy::apply)
                .sorted(Comparator.comparing(CommitEntity::getCommitAt).reversed())
                .toList();
    }

    private Coverage coverage(CodeStatsModule.ScopeInfo scope, CodeRange range, ScanState scan,
                              List<CommitEntity> scopedCommits, Map<String, IdentityEntity> mappingIndex,
                              Set<Long> memberIds, int unmapped, long excludedMerges) {
        Set<Long> depots = new LinkedHashSet<>();
        Set<String> branches = new LinkedHashSet<>();
        long ok = 0;
        long failed = 0;
        long pending = 0;
        for (CommitEntity commit : scopedCommits) {
            CodeIdentityResolver.Resolved resolved = identities.resolve(mappingIndex, commit.getAuthorEmailHash(),
                    commit.getAuthorNameKey());
            boolean owned = !resolved.ignored() && resolved.memberId() != null
                    && memberIds.contains(resolved.memberId());
            if (!owned) continue;
            depots.add(commit.getDepotId());
            CodeStatsSupport.fromBranchJson(commit.getBranchNames()).forEach(branches::add);
            if ("PENDING".equals(commit.getDiffStatus())) pending++;
            if ("OK".equals(commit.getDiffStatus())) ok++;
            if ("FAILED".equals(commit.getDiffStatus()) || "TOO_LARGE".equals(commit.getDiffStatus())) failed++;
        }
        Set<Long> scannedRepos = scannedRepos(scope, range);
        if (!scannedRepos.isEmpty()) depots = scannedRepos;
        int scannedBranches = scannedBranchCount(scope, range);
        int branchCount = scannedBranches > 0 ? scannedBranches : branches.size();
        int pats = (int) scope.members().stream().filter(member -> StringUtils.isNotBlank(member.token())).count();
        List<MemberIssue> memberIssues = scope.scope() == Scope.WORKBENCH_TEAM
                ? scope.members().stream().filter(member -> StringUtils.isBlank(member.token()))
                .map(member -> new MemberIssue(new User(member.userId(), member.userName(), member.avatar()),
                        "TOKEN_MISSING", "尚未绑定 CODING 令牌，其独有仓库可能未覆盖"))
                .toList()
                : List.of();
        int conflicts = (int) mappingIndex.values().stream().filter(item -> item.getCodingUserId() == null).count();
        List<RepoIssue> repoIssues = repoIssues(scope, range);
        boolean truncated = scanTruncated(scope, range);
        boolean partial = "NOT_STARTED".equals(scan.aggregateStatus()) || truncated || memberIssues.size() > 0 || repoIssues.size() > 0 || failed > 0
                || scan.months().stream().anyMatch(month -> "PARTIAL".equals(month.status())
                || "FAILED".equals(month.status()) || "INTERRUPTED".equals(month.status())
                || "CANCELLED".equals(month.status()) || "RUNNING".equals(month.status())
                || "QUEUED".equals(month.status()));
        return new Coverage(scope.scope().name(),
                scope.scope() == Scope.WORKBENCH_TEAM ? scope.scopeId() : null,
                scope.members().size(), pats, depots.size(), branchCount, partial, truncated,
                conflicts,
                scope.scope() == Scope.SELF ? 0 : null, (int) failed,
                CodeStatsSupport.ratio(ok, ok + failed + pending), excludedMerges, memberIssues, repoIssues);
    }

    private Coverage emptyCoverage(CodeStatsModule.ScopeInfo scope, ScanState scan) {
        return new Coverage(scope.scope().name(),
                scope.scope() == Scope.WORKBENCH_TEAM ? scope.scopeId() : null,
                scope.members().size(), 0, 0, 0, false, false, 0,
                scope.scope() == Scope.SELF ? 0 : null, 0, 1D, 0, List.of(), List.of());
    }

    private Set<Long> scannedRepos(CodeStatsModule.ScopeInfo scope, CodeRange range) {
        Set<Long> result = new LinkedHashSet<>();
        for (YearMonth month : range.months()) {
            ScanJobEntity job = jobMapper.selectLatest(scope.codingTeamId(), scope.scope().name(),
                    scope.scopeId(), month.toString());
            if (job == null || job.getTotalRepos() == null || job.getTotalRepos() == 0) {
                job = jobMapper.selectLatestSuccessful(scope.codingTeamId(), scope.scope().name(),
                        scope.scopeId(), month.toString());
            }
            if (job == null) continue;
            repoMapper.selectByJob(job.getId()).stream().filter(repo -> !"FAILED".equals(repo.getStatus()))
                    .map(ScanRepoEntity::getDepotId).forEach(result::add);
        }
        return result;
    }

    private int scannedBranchCount(CodeStatsModule.ScopeInfo scope, CodeRange range) {
        Map<Long, Integer> branchesByDepot = new HashMap<>();
        for (YearMonth month : range.months()) {
            ScanJobEntity job = jobMapper.selectLatest(scope.codingTeamId(), scope.scope().name(),
                    scope.scopeId(), month.toString());
            if (job == null || job.getTotalRepos() == null || job.getTotalRepos() == 0) {
                job = jobMapper.selectLatestSuccessful(scope.codingTeamId(), scope.scope().name(),
                        scope.scopeId(), month.toString());
            }
            if (job == null) continue;
            for (ScanRepoEntity repo : repoMapper.selectByJob(job.getId())) {
                if ("FAILED".equals(repo.getStatus())) continue;
                branchesByDepot.merge(repo.getDepotId(), repo.getBranchCount() == null ? 0 : repo.getBranchCount(),
                        Math::max);
            }
        }
        return branchesByDepot.values().stream().mapToInt(Integer::intValue).sum();
    }

    private boolean scanTruncated(CodeStatsModule.ScopeInfo scope, CodeRange range) {
        for (YearMonth month : range.months()) {
            ScanJobEntity job = jobMapper.selectLatest(scope.codingTeamId(), scope.scope().name(),
                    scope.scopeId(), month.toString());
            if (job != null && Boolean.TRUE.equals(job.getTruncated())) return true;
        }
        return false;
    }

    private List<RepoIssue> repoIssues(CodeStatsModule.ScopeInfo scope, CodeRange range) {
        List<RepoIssue> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (YearMonth month : range.months()) {
            ScanJobEntity job = jobMapper.selectLatest(scope.codingTeamId(), scope.scope().name(),
                    scope.scopeId(), month.toString());
            if (job == null) continue;
            for (ScanRepoEntity repo : repoMapper.selectFailures(job.getId())) {
                String key = repo.getDepotId() + ":" + repo.getErrorCode();
                if (!seen.add(key)) continue;
                result.add(new RepoIssue(repo.getProjectName(), repo.getDepotName(),
                        StringUtils.defaultIfBlank(repo.getErrorCode(), "REQUEST_FAILED"),
                        StringUtils.defaultIfBlank(repo.getErrorMessage(), "仓库读取失败")));
            }
        }
        return result;
    }

    private SelfSummary summary(List<CommitEntity> commits) {
        long additions = 0;
        long deletions = 0;
        Set<LocalDate> days = new HashSet<>();
        Set<Long> depots = new HashSet<>();
        Set<String> branches = new HashSet<>();
        for (CommitEntity commit : commits) {
            if ("OK".equals(commit.getDiffStatus())) {
                additions += commit.getAdditions() == null ? 0 : commit.getAdditions();
                deletions += commit.getDeletions() == null ? 0 : commit.getDeletions();
            }
            days.add(commit.getCommitDate());
            depots.add(commit.getDepotId());
            CodeStatsSupport.fromBranchJson(commit.getBranchNames()).forEach(branches::add);
        }
        return new SelfSummary(commits.size(), additions, deletions, additions - deletions, days.size(),
                depots.size(), branches.size());
    }

    private List<Daily> daily(CodeRange range, List<CommitEntity> commits, long codingTeamId) {
        Map<LocalDate, DayType> dayTypes = dayTypes(range, codingTeamId);
        Map<LocalDate, DayAccumulator> days = new LinkedHashMap<>();
        range.from().datesUntil(range.to().plusDays(1)).forEach(date -> days.put(date, new DayAccumulator()));
        for (CommitEntity commit : commits) {
            DayAccumulator day = days.get(commit.getCommitDate());
            if (day == null) continue;
            day.commitCount++;
            if ("OK".equals(commit.getDiffStatus())) {
                day.additions += commit.getAdditions() == null ? 0 : commit.getAdditions();
                day.deletions += commit.getDeletions() == null ? 0 : commit.getDeletions();
            }
        }
        return days.entrySet().stream().map(entry -> new Daily(entry.getKey().toString(), entry.getValue().commitCount,
                entry.getValue().additions, entry.getValue().deletions,
                entry.getValue().additions - entry.getValue().deletions,
                dayTypes.getOrDefault(entry.getKey(), WorkCalendarDefaults.dayType(entry.getKey())).name())).toList();
    }

    private Map<LocalDate, DayType> dayTypes(CodeRange range, long codingTeamId) {
        Map<LocalDate, DayType> result = new HashMap<>();
        for (YearMonth month : range.months()) {
            try {
                calendar.resolve(codingTeamId, month).days().forEach(day -> result.put(day.date(), day.dayType()));
            } catch (RuntimeException ignored) {
                month.atDay(1).datesUntil(month.plusMonths(1).atDay(1))
                        .forEach(date -> result.put(date, WorkCalendarDefaults.dayType(date)));
            }
        }
        return result;
    }

    private List<Hourly> hourly(List<CommitEntity> commits) {
        int[] counts = new int[24];
        for (CommitEntity commit : commits) {
            LocalDateTime time = commit.getCommitAt();
            if (time != null) counts[time.getHour()]++;
        }
        List<Hourly> result = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) result.add(new Hourly(hour, counts[hour]));
        return result;
    }

    private List<Repository> repositories(List<CommitEntity> commits) {
        Map<Long, RepositoryAccumulator> repos = new LinkedHashMap<>();
        for (CommitEntity commit : commits) {
            RepositoryAccumulator repo = repos.computeIfAbsent(commit.getDepotId(),
                    ignored -> new RepositoryAccumulator(commit));
            repo.add(commit);
        }
        return repos.values().stream()
                .sorted(Comparator.comparingLong((RepositoryAccumulator item) -> item.commitCount).reversed())
                .map(RepositoryAccumulator::view).toList();
    }

    private List<Branch> branches(List<CommitEntity> commits) {
        Map<String, BranchAccumulator> branches = new LinkedHashMap<>();
        for (CommitEntity commit : commits) {
            for (String branch : CodeStatsSupport.fromBranchJson(commit.getBranchNames())) {
                BranchAccumulator accumulator = branches.computeIfAbsent(commit.getDepotId() + "|" + branch,
                        ignored -> new BranchAccumulator(commit.getDepotName(), branch));
                accumulator.add(commit);
            }
        }
        return branches.values().stream()
                .sorted(Comparator.comparingLong((BranchAccumulator item) -> item.commitCount).reversed()
                        .thenComparing(item -> item.branchName, String.CASE_INSENSITIVE_ORDER))
                .map(BranchAccumulator::view).toList();
    }

    private Entry toEntry(CommitEntity commit) {
        List<String> branches = CodeStatsSupport.fromBranchJson(commit.getBranchNames());
        boolean ok = "OK".equals(commit.getDiffStatus());
        return new Entry(commit.getSha(), commit.getShortSha(), CodeStatsSupport.format(commit.getCommitAt()),
                commit.getProjectName(), commit.getProjectDisplayName(), commit.getDepotName(),
                branches.stream().limit(50).toList(), commit.getBranchCount() == null ? branches.size()
                : commit.getBranchCount(), Boolean.TRUE.equals(commit.getBranchTruncated()),
                commit.getMessage(), ok ? commit.getAdditions() : null, ok ? commit.getDeletions() : null,
                ok ? commit.getFilesChanged() : null, commit.getDiffStatus(), commit.getCommitUrl());
    }

    private static final class DayAccumulator {
        private int commitCount;
        private long additions;
        private long deletions;
    }

    private static final class MemberAccumulator {
        private final CodeStatsModule.MemberCredential member;
        private int commitCount;
        private long additions;
        private long deletions;
        private final Set<LocalDate> days = new HashSet<>();
        private final Set<Long> repos = new HashSet<>();
        private final Set<String> branches = new HashSet<>();
        private LocalDateTime lastCommitAt;

        private MemberAccumulator(CodeStatsModule.MemberCredential member) {
            this.member = member;
        }

        private void add(CommitEntity commit) {
            commitCount++;
            if ("OK".equals(commit.getDiffStatus())) {
                additions += commit.getAdditions() == null ? 0 : commit.getAdditions();
                deletions += commit.getDeletions() == null ? 0 : commit.getDeletions();
            }
            days.add(commit.getCommitDate());
            repos.add(commit.getDepotId());
            CodeStatsSupport.fromBranchJson(commit.getBranchNames()).forEach(branches::add);
            if (lastCommitAt == null || commit.getCommitAt().isAfter(lastCommitAt)) lastCommitAt = commit.getCommitAt();
        }

        private MemberMonthly view() {
            return new MemberMonthly(new User(member.userId(), member.userName(), member.avatar()), commitCount,
                    additions, deletions, additions - deletions, days.size(), repos.size(), branches.size(),
                    CodeStatsSupport.format(lastCommitAt));
        }
    }

    private static final class RepositoryAccumulator {
        private final long depotId;
        private final String projectName;
        private final String projectDisplayName;
        private final String depotName;
        private final Set<String> branches = new HashSet<>();
        private long commitCount;
        private long additions;
        private long deletions;
        private LocalDateTime lastCommitAt;

        private RepositoryAccumulator(CommitEntity commit) {
            this.depotId = commit.getDepotId();
            this.projectName = commit.getProjectName();
            this.projectDisplayName = commit.getProjectDisplayName();
            this.depotName = commit.getDepotName();
        }

        private void add(CommitEntity commit) {
            commitCount++;
            if ("OK".equals(commit.getDiffStatus())) {
                additions += commit.getAdditions() == null ? 0 : commit.getAdditions();
                deletions += commit.getDeletions() == null ? 0 : commit.getDeletions();
            }
            CodeStatsSupport.fromBranchJson(commit.getBranchNames()).forEach(branches::add);
            if (lastCommitAt == null || commit.getCommitAt().isAfter(lastCommitAt)) lastCommitAt = commit.getCommitAt();
        }

        private Repository view() {
            return new Repository(depotId, projectName, projectDisplayName, depotName, commitCount, additions,
                    deletions, branches.size(), CodeStatsSupport.format(lastCommitAt), CodeLinePolicy.commitCountOnly(depotName));
        }
    }

    private static final class BranchAccumulator {
        private final String depotName;
        private final String branchName;
        private long commitCount;
        private LocalDateTime lastCommitAt;

        private BranchAccumulator(String depotName, String branchName) {
            this.depotName = depotName;
            this.branchName = branchName;
        }

        private void add(CommitEntity commit) {
            commitCount++;
            if (lastCommitAt == null || commit.getCommitAt().isAfter(lastCommitAt)) lastCommitAt = commit.getCommitAt();
        }

        private Branch view() {
            return new Branch(depotName, branchName, commitCount, CodeStatsSupport.format(lastCommitAt));
        }
    }
}
