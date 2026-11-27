package com.itwray.iw.external.zhaogang.codestats;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingDepot;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
class CodeRepositoryAccess {
    private static final long RECHECK_MS = Duration.ofMinutes(15).toMillis();
    private static final TypeReference<Set<Long>> DEPOT_IDS = new TypeReference<>() {};

    record Catalog(Set<Long> depots, boolean complete, long checkedAt) {}
    record Discovery(List<CodingDepot> depots, RuntimeException error) {}
    record Coverage(boolean ready, boolean partial, LocalDateTime syncedAt) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final CodingOpenApiPort coding;
    private final ExecutorService verifier = Executors.newFixedThreadPool(2, task -> {
        var thread = new Thread(task, "devhub-code-access");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> retryAfter = new ConcurrentHashMap<>();

    CodeRepositoryAccess(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions,
                         ObjectMapper json, CodingOpenApiPort coding) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
        this.coding = coding;
    }

    static Set<String> fingerprints(CodeStatsModule.ScopeInfo scope) {
        Set<String> result = new HashSet<>();
        for (var member : scope.members()) {
            if (scope.hasRepositoryToken(member)) {
                result.add(CodingCredentialService.fingerprint(scope.repositoryToken(member)));
            }
        }
        return result;
    }

    private Catalog catalog(long org, String fingerprint) {
        return jdbc.query("""
                SELECT depot_ids,complete,checked_at FROM external_zhaogang_code_access
                WHERE coding_team_id=:org AND credential_fingerprint=:fingerprint
                """, Map.of("org", org, "fingerprint", fingerprint),
                (rs, n) -> new Catalog(decode(rs.getString(1)), rs.getBoolean(2), rs.getLong(3)))
                .stream().findFirst().orElse(new Catalog(Set.of(), false, 0));
    }

    Catalog visible(long org, Set<String> fingerprints) {
        if (fingerprints.isEmpty()) return new Catalog(Set.of(), false, 0);
        var rows = jdbc.query("""
                SELECT depot_ids,complete,checked_at FROM external_zhaogang_code_access
                WHERE coding_team_id=:org AND credential_fingerprint IN (:fingerprints)
                """, Map.of("org", org, "fingerprints", fingerprints),
                (rs, n) -> new Catalog(decode(rs.getString(1)), rs.getBoolean(2), rs.getLong(3)));
        Set<Long> depots = new TreeSet<>();
        rows.forEach(row -> depots.addAll(row.depots()));
        return new Catalog(Set.copyOf(depots), rows.size() == fingerprints.size() && rows.stream().allMatch(Catalog::complete),
                rows.stream().mapToLong(Catalog::checkedAt).max().orElse(0));
    }

    Catalog visible(CodeStatsModule.ScopeInfo scope) {
        return visible(scope.codingTeamId(), fingerprints(scope));
    }

    boolean canRead(long org, long depotId, String token) {
        return catalog(org, CodingCredentialService.fingerprint(token)).depots().contains(depotId);
    }

    Coverage coverage(long org, Catalog catalog, LocalDate from, LocalDate to) {
        return coverage(org, catalog, from, to, latestSyncedAt(org, catalog));
    }

    LocalDateTime latestSyncedAt(long org, Catalog catalog) {
        if (catalog.depots().isEmpty()) return null;
        // Legacy completed repositories still contribute to the latest sync time.
        return jdbc.query("""
                SELECT j.finished_at
                FROM external_zhaogang_code_scan_job j FORCE INDEX (idx_code_coverage_finished)
                WHERE j.coding_team_id=:org AND j.finished_at IS NOT NULL
                  AND j.scan_mode IN ('SYNC','SYNC_V2','SNAPSHOT_V1')
                  AND (SELECT 1 FROM external_zhaogang_code_scan_repo r
                       WHERE r.job_id=j.id AND r.status='COMPLETED' AND r.depot_id IN (:depots)
                       LIMIT 1) = 1
                ORDER BY j.finished_at DESC
                LIMIT 1
                """, Map.of("org", org, "depots", catalog.depots()),
                (rs, n) -> rs.getObject("finished_at", LocalDateTime.class)).stream().findFirst().orElse(null);
    }

    Coverage coverage(long org, Catalog catalog, LocalDate from, LocalDate to, LocalDateTime syncedAt) {
        if (catalog.depots().isEmpty()) return new Coverage(catalog.complete(), !catalog.complete(), null);
        if (syncedAt == null) return new Coverage(false, true, null);
        // Stop at the first missing repository. Each repository needs one checkpoint covering the entire range.
        boolean complete = catalog.complete() && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT NOT EXISTS (
                    SELECT 1
                    FROM JSON_TABLE(:depots, '$[*]' COLUMNS (depot_id BIGINT UNSIGNED PATH '$')) d
                    WHERE (SELECT 1
                           FROM external_zhaogang_code_scan_job j FORCE INDEX (idx_code_coverage_range)
                           STRAIGHT_JOIN external_zhaogang_code_scan_repo r ON r.job_id=j.id AND r.depot_id=d.depot_id
                           WHERE j.coding_team_id=:org AND j.sync_from<=:from AND j.sync_to>=:to
                             AND j.finished_at<=:syncedAt AND j.scan_mode IN ('SYNC','SYNC_V2','SNAPSHOT_V1')
                             AND r.status='COMPLETED' AND r.shared_checkpoint=1
                           ORDER BY j.sync_to DESC
                           LIMIT 1) IS NULL
                )
                """, Map.of("org", org, "depots", encode(catalog.depots()), "from", from, "to", to,
                "syncedAt", syncedAt), Boolean.class));
        return new Coverage(true, !complete, syncedAt);
    }

    void requestVerification(CodeStatsModule.ScopeInfo scope) {
        Set<String> requested = new HashSet<>();
        for (var member : scope.members()) {
            String token = scope.repositoryToken(member);
            if (token != null && !token.isBlank() && requested.add(token)) {
                requestVerification(scope.codingTeamId(), token);
            }
        }
    }

    void requestVerification(long org, String token) {
        // Bound the number of pending credential checks when many scopes are opened together.
        if (pending.size() >= 64) return;
        String fingerprint = CodingCredentialService.fingerprint(token);
        String key = org + ":" + fingerprint;
        long now = System.currentTimeMillis();
        if (retryAfter.getOrDefault(key, 0L) > now || !pending.add(key)) return;
        if (catalog(org, fingerprint).checkedAt() + RECHECK_MS > now) {
            pending.remove(key);
            return;
        }
        verifier.execute(() -> {
            try {
                discover(org, token);
            } finally {
                retryAfter.entrySet().removeIf(entry -> entry.getValue() <= now);
                retryAfter.put(key, now + Duration.ofMinutes(1).toMillis());
                pending.remove(key);
            }
        });
    }

    Discovery discover(long org, String token) {
        long started = System.currentTimeMillis();
        String fingerprint = CodingCredentialService.fingerprint(token);
        List<CodingDepot> depots = new ArrayList<>();
        try {
            int seen = 0;
            for (int page = 1; page <= 100; page++) {
                var response = coding.myDepots(token, page, 100);
                response.items().stream().filter(CodeRepositoryAccess::isGit).forEach(depots::add);
                seen += response.items().size();
                if (response.items().size() < 100 || (response.totalCount() > 0 && seen >= response.totalCount())) {
                    save(org, fingerprint, started, depots.stream().map(CodingDepot::id).collect(
                            java.util.stream.Collectors.toCollection(TreeSet::new)), true);
                    return new Discovery(depots, null);
                }
            }
            return new Discovery(depots, new CodingOpenApiException("仓库目录达到分页上限"));
        } catch (RuntimeException failure) {
            if (failure instanceof CodingOpenApiException error && (error.isPermissionDenied()
                    || Integer.valueOf(401).equals(error.httpStatus()) || Integer.valueOf(403).equals(error.httpStatus()))) {
                save(org, fingerprint, started, Set.of(), false);
            }
            return new Discovery(depots, failure);
        }
    }

    void revoke(long org, String token, long depot) {
        String fingerprint = CodingCredentialService.fingerprint(token);
        transactions.executeWithoutResult(status -> {
            var current = lockCatalog(org, fingerprint);
            if (current == null || !current.depots().contains(depot)) return;
            Set<Long> depots = new TreeSet<>(current.depots());
            depots.remove(depot);
            saveLocked(org, fingerprint, System.currentTimeMillis(), depots, false, current);
        });
    }

    private void save(long org, String fingerprint, long started, Set<Long> depots, boolean complete) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT IGNORE INTO external_zhaogang_code_access
                    (coding_team_id,credential_fingerprint,depot_ids,checked_at,complete)
                    VALUES (:org,:fingerprint,JSON_ARRAY(),0,0)
                    """, Map.of("org", org, "fingerprint", fingerprint));
            var current = lockCatalog(org, fingerprint);
            if (current != null && current.checkedAt() <= started) {
                saveLocked(org, fingerprint, started, depots, complete, current);
            }
        });
    }

    private Catalog lockCatalog(long org, String fingerprint) {
        return jdbc.query("""
                SELECT depot_ids,complete,checked_at FROM external_zhaogang_code_access
                WHERE coding_team_id=:org AND credential_fingerprint=:fingerprint FOR UPDATE
                """, Map.of("org", org, "fingerprint", fingerprint),
                (rs, n) -> new Catalog(decode(rs.getString(1)), rs.getBoolean(2), rs.getLong(3)))
                .stream().findFirst().orElse(null);
    }

    private void saveLocked(long org, String fingerprint, long started, Set<Long> depots, boolean complete, Catalog current) {
        jdbc.update("""
                UPDATE external_zhaogang_code_access SET depot_ids=:depots,complete=:complete,checked_at=:started
                WHERE coding_team_id=:org AND credential_fingerprint=:fingerprint
                """, Map.of("org", org, "fingerprint", fingerprint, "depots", encode(depots),
                "complete", complete, "started", started));
        if (!current.depots().equals(depots) || current.complete() != complete) {
            boolean narrowed = !depots.containsAll(current.depots());
            jdbc.update("""
                    INSERT INTO external_zhaogang_statistics_revision
                        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
                    VALUES (:org,'*',1,:visibility,0)
                    ON DUPLICATE KEY UPDATE version_no=version_no+1,visibility_version=visibility_version+:visibility
                    """, Map.of("org", org, "visibility", narrowed ? 1 : 0));
        }
    }

    interface RepositoryLock extends AutoCloseable {
        @Override
        void close();
    }

    RepositoryLock lockRepository(long org, long depot) {
        String key = "devhub-code:" + org + ":" + depot;
        javax.sql.DataSource dataSource = java.util.Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource());
        java.sql.Connection connection = null;
        try {
            connection = dataSource.getConnection();
            try (var statement = connection.prepareStatement("SELECT GET_LOCK(?, 30)")) {
                statement.setString(1, key);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getInt(1) != 1) {
                        connection.close();
                        throw new CodingOpenApiException("REPOSITORY_BUSY", "仓库正在同步，将在下一轮接续");
                    }
                }
            }
        } catch (java.sql.SQLException failure) {
            if (connection != null) {
                try { connection.close(); } catch (java.sql.SQLException close) { failure.addSuppressed(close); }
            }
            throw new IllegalStateException("Cannot reserve repository", failure);
        }
        var held = connection;
        return () -> {
            try (held; var statement = held.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                statement.setString(1, key);
                statement.execute();
            } catch (java.sql.SQLException failure) {
                try { held.abort(Runnable::run); } catch (java.sql.SQLException abort) { failure.addSuppressed(abort); }
                throw new IllegalStateException("Cannot release repository", failure);
            }
        };
    }

    private static boolean isGit(CodingDepot depot) {
        String vcs = org.apache.commons.lang3.StringUtils.trimToEmpty(depot.vcsType()).toLowerCase(java.util.Locale.ROOT);
        String type = org.apache.commons.lang3.StringUtils.trimToEmpty(depot.depotType()).toUpperCase(java.util.Locale.ROOT);
        return (vcs.isEmpty() || vcs.contains("git")) && (type.isEmpty() || type.contains("GIT") || type.equals("CODING"));
    }

    private Set<Long> decode(String value) {
        try {
            return json.readValue(value, DEPOT_IDS);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Cannot decode CODING repository access", invalid);
        }
    }

    private String encode(Set<Long> depots) {
        try {
            return json.writeValueAsString(depots);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Cannot encode CODING repository access", invalid);
        }
    }

    @PreDestroy
    void shutdown() {
        verifier.shutdownNow();
    }
}
