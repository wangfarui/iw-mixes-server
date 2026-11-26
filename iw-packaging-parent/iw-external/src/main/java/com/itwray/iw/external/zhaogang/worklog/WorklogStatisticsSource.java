package com.itwray.iw.external.zhaogang.worklog;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class WorklogStatisticsSource {
    public record Selection(long managementTeamId, String fingerprint) {
        @Override
        public String toString() {
            return "Selection[managementTeamId=" + managementTeamId + "]";
        }
    }

    public record Month(Map<Long, Map<LocalDate, BigDecimal>> hours, Map<Long, String> reasons, Long syncedAt,
                        long validUntil) {}

    private final WorklogMirrorRepository repository;
    private final WorklogMirror mirror;

    WorklogStatisticsSource(WorklogMirrorRepository repository, WorklogMirror mirror) {
        this.repository = repository;
        this.mirror = mirror;
    }

    public Month read(long org, YearMonth month, Map<Long, Selection> selections) {
        Map<String, Long> idsByKey = new HashMap<>();
        Map<Long, String> reasons = new HashMap<>();
        selections.forEach((id, selection) -> {
            reasons.put(id, "TOKEN_MISSING");
            if (selection != null) idsByKey.put(WorklogMirror.memberKey(org, id), id);
        });
        long validUntil = Long.MAX_VALUE;
        long syncedAt = 0;
        var keys = new java.util.ArrayList<String>();
        LocalDate from = month.atDay(1);
        LocalDate end = month.plusMonths(1).atDay(1);
        for (var source : repository.sources(idsByKey.keySet().stream().toList())) {
            long id = idsByKey.get(source.key());
            Selection choice = selections.get(id);
            if (source.teamId() != org || source.userId() != id || source.managementTeamId() != choice.managementTeamId()
                    || !Objects.equals(source.fingerprint(), choice.fingerprint())) {
                reasons.put(id, "NOT_SYNCED");
                continue;
            }
            var problem = mirror.problem(source, from, end);
            if (problem == null) {
                reasons.remove(id);
                keys.add(source.key());
                validUntil = Math.min(validUntil, source.incrementAt() + Duration.ofMinutes(30).toMillis() + 1);
            } else {
                reasons.put(id, problem.code);
            }
            if (!source.blocked()) syncedAt = Math.max(syncedAt, source.incrementAt());
        }
        return new Month(repository.dailyHours(keys, millis(from), millis(end)), reasons,
                syncedAt == 0 ? null : syncedAt, validUntil);
    }

    private long millis(LocalDate date) {
        return date.atStartOfDay(WorklogMirror.ZONE).toInstant().toEpochMilli();
    }
}
