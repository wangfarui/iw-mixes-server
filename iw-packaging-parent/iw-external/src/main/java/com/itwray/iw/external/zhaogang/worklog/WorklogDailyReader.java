package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.CodingIssueSnapshotLoader;
import com.itwray.iw.external.zhaogang.CodingIssueSnapshotLoader.IssueKey;
import com.itwray.iw.external.zhaogang.CodingIssueSnapshotLoader.Lookup;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Issue;
import com.itwray.iw.external.zhaogang.calendar.PersonalLeaveResolver;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.CodeSyncTeam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/** Token-free daily snapshot. Direct issues are synchronously verified before any target judgement. */
@Component
public class WorklogDailyReader {
    public record Entry(long worklogId, String project, long issueCode, String title,
                        List<String> parents, BigDecimal hours, String projectDisplayName, String issueType) { }
    public record Member(long userId, String name, boolean leave, boolean complete, String warning,
                         List<Entry> entries) {
        public BigDecimal hours() { return entries.stream().map(Entry::hours).reduce(BigDecimal.ZERO, BigDecimal::add); }
    }
    public record Snapshot(String teamName, LocalDate date, String collectedAt, List<Member> members) { }
    private final WorklogQueryService query;
    private final CodingCredentialService credentials;
    private final CodingIssueSnapshotLoader loader;
    private final WorklogDeletedIssues deleted;
    private final PersonalLeaveResolver leaves;
    private final CodingOpenApiPort coding;

    public WorklogDailyReader(WorklogQueryService query, CodingCredentialService credentials,
                              CodingIssueSnapshotLoader loader, WorklogDeletedIssues deleted,
                              PersonalLeaveResolver leaves, CodingOpenApiPort coding) {
        this.query = query; this.credentials = credentials; this.loader = loader; this.deleted = deleted; this.leaves = leaves;
        this.coding = coding;
    }

    public Snapshot read(long codingTeamId, CodeSyncTeam team, LocalDate date) {
        var ids = team.members().stream().map(m -> m.userId()).toList();
        var tokens = credentials.tokens(codingTeamId, ids);
        var leaveDates = leaves.resolve(codingTeamId, ids, YearMonth.from(date));
        List<Member> members = new ArrayList<>();
        Map<Long, WorklogQueryService.DailyRows> rowsByMember = new LinkedHashMap<>();
        Map<String, Map<IssueKey, Set<Long>>> candidates = new LinkedHashMap<>();
        Set<Long> allIds = new HashSet<>();
        for (var member : team.members()) {
            String token = tokens.getOrDefault(member.userId(), "");
            var rows = query.dailyRows(new WorklogModule.MemberCredential(member.userId(), member.userName(), member.avatar(), token), date);
            rowsByMember.put(member.userId(), rows);
            for (var row : rows.rows()) {
                if (row.issueId() > 0) allIds.add(row.issueId());
                if (row.issueCode() > 0 && row.projectName() != null && !row.projectName().isBlank()) {
                    var internalIds = candidates.computeIfAbsent(token, ignored -> new LinkedHashMap<>())
                            .computeIfAbsent(new IssueKey(row.projectName(), row.issueCode()), ignored -> new HashSet<>());
                    if (row.issueId() > 0) internalIds.add(row.issueId());
                }
            }
        }
        Set<Long> knownDeleted = deleted.find(codingTeamId, allIds);
        Map<String, Collection<IssueKey>> requests = new LinkedHashMap<>();
        candidates.forEach((token, issues) -> {
            var keys = issues.entrySet().stream().filter(e -> e.getValue().isEmpty() || !knownDeleted.containsAll(e.getValue())).map(Map.Entry::getKey).toList();
            if (!keys.isEmpty()) requests.put(token, keys);
        });
        var checkedByToken = loader.loadForWorklogs(requests, true);
        Map<Long, IssueKey> confirmed = new LinkedHashMap<>();
        Set<Long> existing = new HashSet<>();
        checkedByToken.forEach((token, checks) -> checks.forEach((key, check) -> {
            var internalIds = candidates.get(token).get(key);
            if (check.lookup().missing() && internalIds.size() == 1) confirmed.put(internalIds.iterator().next(), key);
            if (check.lookup().issue() != null && check.lookup().issue().id() > 0) existing.add(check.lookup().issue().id());
        }));
        // Evidence across all members is considered before persisting a permanent deletion.
        confirmed.keySet().removeAll(existing);
        deleted.record(codingTeamId, confirmed);
        Set<Long> excluded = new HashSet<>(knownDeleted); excluded.addAll(confirmed.keySet());
        for (var member : team.members()) {
            String token = tokens.getOrDefault(member.userId(), "");
            var rows = rowsByMember.get(member.userId());
            boolean complete = rows.complete();
            String warning = rows.warning();
            List<Entry> entries = new ArrayList<>();
            var checked = checkedByToken.getOrDefault(token, Map.of());
            Map<String, String> projectNames = null;
            for (var row : rows.rows()) {
                if (excluded.contains(row.issueId())) continue;
                var check = checked.get(new IssueKey(row.projectName(), row.issueCode()));
                Lookup lookup = check == null ? null : check.lookup();
                Issue issue = lookup == null ? null : lookup.issue();
                if (issue == null || row.issueId() > 0 && issue.id() != row.issueId()) {
                    complete = false;
                    if (warning.isBlank()) warning = "部分工时事项尚未核验，数据可能不完整";
                }
                String title = issue == null ? "事项信息暂未获取" : issue.title();
                String projectName = issue == null ? null : issue.projectDisplayName();
                if (projectName == null || projectName.isBlank() || projectName.equals(row.projectName())) {
                    if (projectNames == null) {
                        try { projectNames = token.isBlank() ? Map.of() : coding.projectDisplayNames(token, member.userId()); }
                        catch (RuntimeException e) { projectNames = Map.of(); }
                    }
                    projectName = projectNames.getOrDefault(row.projectName(), "项目名称暂未获取");
                }
                BigDecimal hours = row.hours() == null || row.hours().signum() < 0 ? BigDecimal.ZERO : row.hours();
                entries.add(new Entry(row.id(), row.projectName(), row.issueCode(), title,
                        parents(token, row.projectName(), issue), hours, projectName, issueType(issue)));
            }
            members.add(new Member(member.userId(), member.userName(),
                    leaveDates.getOrDefault(member.userId(), Set.of()).contains(date), complete, warning, List.copyOf(entries)));
        }
        return new Snapshot(team.name(), date, java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), List.copyOf(members));
    }

    private List<String> parents(String token, String project, Issue issue) {
        if (issue == null) return List.of("上级信息暂未获取");
        if (issue.parentCode() == null || issue.parentCode() <= 0) return List.of("未关联上级需求/任务");
        List<String> chain = new ArrayList<>();
        Set<IssueKey> visited = new HashSet<>();
        visited.add(new IssueKey(project, issue.code()));
        for (int depth = 0; issue.parentCode() != null && issue.parentCode() > 0; depth++) {
            if (depth >= 16) { chain.add("上级信息暂未获取（层级过深）"); break; }
            project = issue.parentProjectName() == null || issue.parentProjectName().isBlank() ? project : issue.parentProjectName();
            IssueKey parent = new IssueKey(project, issue.parentCode());
            if (!visited.add(parent)) { chain.add("上级信息暂未获取（循环关联）"); break; }
            Lookup lookup = loader.load(token, List.of(parent)).get(parent);
            if (lookup == null || lookup.issue() == null) { chain.add("上级 #" + parent.issueCode() + " 信息暂未获取"); break; }
            issue = lookup.issue();
            chain.add(issueType(issue) + "：#" + issue.code() + " " + issue.title());
        }
        Collections.reverse(chain);
        return List.copyOf(chain);
    }

    static String issueType(Issue issue) {
        if (issue == null) return "事项";
        String name = issue.typeName() == null ? "" : issue.typeName().trim();
        if (name.contains("子工作项") || name.contains("子任务")) return "子工作项";
        if (!name.isBlank()) return name;
        String type = issue.type() == null ? "" : issue.type().replace('-', '_').toUpperCase(Locale.ROOT);
        return switch (type) {
            case "REQUIREMENT", "STORY" -> "需求";
            case "USER_STORY", "USERSTORY" -> "用户故事";
            case "SUB_TASK", "SUBTASK", "WORK_ITEM" -> "子工作项";
            case "TASK", "MISSION", "ASSIGNMENT" -> "任务";
            case "DEFECT", "BUG" -> "缺陷";
            default -> "事项";
        };
    }
}
