package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.ZhaogangCodeCommitMapper;
import com.itwray.iw.external.zhaogang.team.OrganizationStatisticsDirectory.Membership;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class CodeStatisticsSource {
    public record Key(long member, LocalDate date) {}
    public record Totals(long commits, long additions, long deletions, boolean linesIncomplete) {
        Totals plus(Totals other) {
            return new Totals(commits + other.commits, additions + other.additions,
                    deletions + other.deletions, linesIncomplete || other.linesIncomplete);
        }
    }
    public record Month(Map<Key, Totals> data, Set<LocalDate> ambiguousDates, Set<Long> unmappedMembers,
                        String reason, Long syncedAt, boolean repositoriesPartial) {}

    private final ZhaogangCodeCommitMapper commits;
    private final CodeIdentityResolver identities;
    private final CodeRepositoryAccess access;

    CodeStatisticsSource(ZhaogangCodeCommitMapper commits, CodeIdentityResolver identities,
                         CodeRepositoryAccess access) {
        this.commits = commits;
        this.identities = identities;
        this.access = access;
    }

    public Month read(long org, YearMonth month, LocalDate today, LocalDate retentionStart,
                      List<Membership> memberships) {
        if (month.atEndOfMonth().isBefore(retentionStart)) {
            return new Month(Map.of(), Set.of(), Set.of(), "CODE_OUTSIDE_COVERAGE", null, false);
        }
        var developers = memberships.stream().filter(CodeStatisticsSource::developer).toList();
        if (developers.isEmpty()) return new Month(Map.of(), Set.of(), Set.of(), null, null, false);

        Set<Long> teams = developers.stream().map(Membership::teamId).collect(Collectors.toSet());
        Map<Long, CodeRepositoryAccess.Catalog> catalogs = new HashMap<>();
        for (long team : teams) {
            var members = memberships.stream().filter(member -> member.teamId() == team).toList();
            Set<String> fingerprints = members.stream()
                    .map(member -> member.managed() ? member.managementFingerprint() : member.personalFingerprint())
                    .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
            var catalog = access.visible(org, fingerprints);
            catalogs.put(team, new CodeRepositoryAccess.Catalog(catalog.depots(), catalog.complete()
                    && members.stream().allMatch(Membership::codeCredentialAvailable), catalog.checkedAt()));
        }
        Set<Long> depots = new HashSet<>();
        catalogs.values().forEach(catalog -> depots.addAll(catalog.depots()));
        boolean complete = catalogs.values().stream().allMatch(CodeRepositoryAccess.Catalog::complete);
        LocalDate coveredThrough = today.isBefore(month.atEndOfMonth()) ? today : month.atEndOfMonth();
        var coverage = access.coverage(org, new CodeRepositoryAccess.Catalog(depots, complete, 0), month.atDay(1), coveredThrough);
        Long syncedAt = coverage.syncedAt() == null ? null
                : coverage.syncedAt().atZone(CodeStatsSupport.CHINA_ZONE).toInstant().toEpochMilli();
        Map<Long, Set<Long>> memberDepots = new HashMap<>();
        developers.forEach(member -> memberDepots.computeIfAbsent(member.userId(), ignored -> new HashSet<>())
                .addAll(catalogs.get(member.teamId()).depots()));

        var index = identities.mappingIndex(identities.organizationMappings(org));
        Set<Long> mapped = index.values().stream().map(entity -> entity.getCodingUserId())
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Set<Long> unmapped = new HashSet<>(memberDepots.keySet());
        unmapped.removeAll(mapped);
        if (complete && depots.isEmpty()) unmapped.clear();
        Map<Key, Totals> data = new HashMap<>();
        Set<LocalDate> ambiguous = new HashSet<>();
        if (!depots.isEmpty()) {
            for (var row : commits.selectVisible(org, depots, month.atDay(1), month.atEndOfMonth(), false)) {
                var owner = identities.resolve(index, row);
                if (owner.memberId() == null) {
                    ambiguous.add(row.getCommitDate());
                    continue;
                }
                if (!memberDepots.getOrDefault(owner.memberId(), Set.of()).contains(row.getDepotId())) continue;
                boolean countOnly = CodeLinePolicy.commitCountOnly(row.getDepotName());
                boolean known = "OK".equals(row.getDiffStatus()) && row.getAdditions() != null && row.getDeletions() != null;
                var contribution = new Totals(1, known && !countOnly ? row.getAdditions() : 0,
                        known && !countOnly ? row.getDeletions() : 0, !known && !countOnly);
                data.merge(new Key(owner.memberId(), row.getCommitDate()), contribution, Totals::plus);
            }
        }
        boolean noCoverage = !coverage.ready() && data.isEmpty();
        return new Month(data, ambiguous, unmapped, noCoverage ? "CODE_COVERAGE" : null, syncedAt, coverage.partial());
    }

    private static boolean developer(Membership member) {
        return !member.ignored() && ("FRONTEND".equals(member.role()) || "BACKEND".equals(member.role()));
    }
}
