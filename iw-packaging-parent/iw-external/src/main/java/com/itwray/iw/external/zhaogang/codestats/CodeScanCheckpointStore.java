package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.ZhaogangCodeScanRepoMapper;
import com.itwray.iw.external.zhaogang.codestats.CodeBranchSync.Checkpoint;
import com.itwray.iw.external.zhaogang.codestats.CodeBranchSync.Head;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.ScanJobEntity;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.ScanRepoEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CodeScanCheckpointStore {
    private static final Logger log = LoggerFactory.getLogger(CodeScanCheckpointStore.class);
    private static final String PARTS = "parts2:";
    private static final String RESCAN = "rescan2:";
    private static final String TOO_LARGE = "CHECKPOINT_TOO_LARGE";
    private static final String SAVE_FAILED = "CHECKPOINT_SAVE_FAILED";
    private static final int MAX_PARTS = 32;
    private static final int MAX_PART_CHARS_TOTAL = 32 * 1024 * 1024;

    private final ZhaogangCodeScanRepoMapper repos;
    private final int partLimit;
    private final boolean shared;

    CodeScanCheckpointStore(ZhaogangCodeScanRepoMapper repos) {
        this(repos, CodeSyncCheckpointCodec.MAX_COMPRESSED_BYTES);
    }

    CodeScanCheckpointStore(ZhaogangCodeScanRepoMapper repos, int partLimit) {
        this(repos, partLimit, false);
    }

    CodeScanCheckpointStore(ZhaogangCodeScanRepoMapper repos, int partLimit, boolean shared) {
        this.repos = repos;
        this.partLimit = partLimit;
        this.shared = shared;
    }

    record Previous(Checkpoint checkpoint, boolean partitioned) {}

    Previous read(ScanJobEntity job, long depotId, String signature) {
        String state = null;
        try {
            var stored = shared ? repos.selectRepositoryCheckpoint(job.getCodingTeamId(), depotId,
                    job.getSyncFrom(), job.getSyncTo(), job.getId(), true)
                    : repos.selectSyncCheckpoint(job.getCodingTeamId(), job.getScopeType(), job.getScopeId(),
                    depotId, signature, job.getSyncFrom(), job.getId());
            boolean legacy = shared && stored == null;
            if (legacy) stored = repos.selectRepositoryCheckpoint(job.getCodingTeamId(), depotId,
                    job.getSyncFrom(), job.getSyncTo(), job.getId(), false);
            if (stored == null) return new Previous(null, false);
            state = stored.getSyncState();
            if (state == null || state.startsWith(RESCAN)) return new Previous(null, false);
            boolean partitioned = state.startsWith(PARTS);
            Checkpoint checkpoint = partitioned ? readParts(job, depotId, stored) : CodeSyncCheckpointCodec.decode(state);
            // Scoped scanners discarded unowned commit metadata. Only an explicit, fully represented
            // graph can prove that the old checkpoint covers the same data as the shared scan.
            if (legacy && (checkpoint == null || checkpoint.graph().isEmpty()
                    || checkpoint.graph().values().stream().anyMatch(node -> !node.matched() && !node.boundary()))) {
                checkpoint = null;
            }
            return new Previous(checkpoint, partitioned);
        } catch (IOException | RuntimeException invalid) {
            log.warn("zhaogang code checkpoint unavailable, jobId={}, depotId={}, error={}",
                    job.getId(), depotId, invalid.getClass().getSimpleName());
            return new Previous(null, state != null && state.startsWith(PARTS));
        }
    }

    private Checkpoint readParts(ScanJobEntity job, long depotId, ScanRepoEntity stored) {
        int count = Integer.parseInt(stored.getSyncState().substring(PARTS.length()));
        if (count < 1 || count > MAX_PARTS) throw new IllegalArgumentException("Invalid checkpoint part count");
        Map<String, String> branches = new LinkedHashMap<>();
        Map<String, Head> heads = new LinkedHashMap<>();
        int remainingChars = MAX_PART_CHARS_TOTAL;
        int remainingJson = CodeSyncCheckpointCodec.MAX_JSON_BYTES;
        for (int part = 0; part < count; part++) {
            try {
                String value = repos.selectCheckpointPart(stored.getJobId(), depotId, part);
                if (value == null || value.length() > remainingChars) continue;
                remainingChars -= value.length();
                var decoded = CodeSyncCheckpointCodec.decodePart(value, remainingJson);
                remainingJson -= decoded.jsonBytes();
                Checkpoint checkpoint = decoded.checkpoint();
                // A part without its corresponding head is a cache miss for that branch.
                checkpoint.branches().forEach((branch, sha) -> {
                    if (checkpoint.heads().containsKey(sha)) branches.put(branch, sha);
                });
                heads.putAll(checkpoint.heads());
            } catch (IOException | RuntimeException invalid) {
                log.warn("zhaogang code checkpoint part unavailable, jobId={}, depotId={}, part={}, error={}",
                        job.getId(), depotId, part, invalid.getClass().getSimpleName());
            }
        }
        return heads.isEmpty() ? null : new Checkpoint(branches, heads);
    }

    void save(ScanJobEntity job, long depotId, Checkpoint checkpoint, Previous previous) {
        try {
            String state;
            try {
                state = CodeSyncCheckpointCodec.encode(checkpoint, partLimit);
            } catch (CodeSyncCheckpointCodec.TooLarge oversized) {
                state = compactOrPartition(job, depotId, checkpoint);
            }
            publish(job, depotId, state, previous);
        } catch (IOException | RuntimeException failure) {
            String reason = failure instanceof CodeSyncCheckpointCodec.TooLarge ? TOO_LARGE : SAVE_FAILED;
            log.warn("zhaogang code checkpoint not stored, jobId={}, depotId={}, reason={}, error={}",
                    job.getId(), depotId, reason, failure.getClass().getSimpleName());
            try {
                publish(job, depotId, RESCAN + reason, previous);
            } catch (RuntimeException unavailable) {
                log.warn("zhaogang code checkpoint rescan marker not stored, jobId={}, depotId={}, error={}",
                        job.getId(), depotId, unavailable.getClass().getSimpleName());
            }
        }
    }

    void retireSuperseded(ScanJobEntity job, long depot) {
        if (!shared || Boolean.TRUE.equals(job.getTruncated())) return;
        try {
            repos.retireSupersededRepositoryCheckpoints(job.getCodingTeamId(), depot, job.getSyncFrom(), job.getSyncTo(), job.getId());
            repos.deleteSupersededRepositoryParts(job.getCodingTeamId(), depot, job.getSyncFrom(), job.getSyncTo(), job.getId());
        } catch (RuntimeException failure) {
            log.warn("zhaogang repository checkpoint retirement failed, jobId={}, depotId={}, error={}",
                    job.getId(), depot, failure.getClass().getSimpleName());
        }
    }

    private String compactOrPartition(ScanJobEntity job, long depotId, Checkpoint checkpoint) throws IOException {
        try {
            if (!checkpoint.graph().isEmpty()) {
                return CodeSyncCheckpointCodec.encode(new Checkpoint(checkpoint.branches(), checkpoint.heads()), partLimit);
            }
        } catch (CodeSyncCheckpointCodec.TooLarge oversized) {
            // Retain the verified head closures in separate rows.
        }
        var byHead = new LinkedHashMap<String, List<String>>();
        checkpoint.branches().forEach((branch, sha) ->
                byHead.computeIfAbsent(sha, ignored -> new ArrayList<>()).add(branch));

        var pending = new ArrayDeque<List<String>>();
        pending.add(new ArrayList<>(checkpoint.heads().keySet()));
        int nextPart = 0;
        int storedHeads = 0;
        int remainingChars = MAX_PART_CHARS_TOTAL;
        int remainingJson = CodeSyncCheckpointCodec.MAX_JSON_BYTES;
        String warning = TOO_LARGE;
        while (!pending.isEmpty() && nextPart < MAX_PARTS) {
            List<String> keys = pending.removeFirst();
            if (keys.isEmpty()) continue;
            CodeSyncCheckpointCodec.Encoded encoded;
            try {
                encoded = CodeSyncCheckpointCodec.encodePart(headPartition(checkpoint, byHead, keys), partLimit, remainingJson);
            } catch (CodeSyncCheckpointCodec.TooLarge oversized) {
                if (keys.size() > 1) {
                    int middle = keys.size() / 2;
                    pending.addLast(keys.subList(0, middle));
                    pending.addLast(keys.subList(middle, keys.size()));
                }
                continue;
            }
            if (encoded.value().length() > remainingChars) continue;
            int part = nextPart++;
            try {
                repos.saveCheckpointPart(job.getId(), depotId, part, encoded.value());
                storedHeads += keys.size();
                remainingChars -= encoded.value().length();
                remainingJson -= encoded.jsonBytes();
            } catch (RuntimeException unavailable) {
                warning = SAVE_FAILED;
                log.warn("zhaogang code checkpoint part not stored, jobId={}, depotId={}, part={}, error={}",
                        job.getId(), depotId, part, unavailable.getClass().getSimpleName());
            }
        }
        if (storedHeads < checkpoint.heads().size() || storedHeads == 0) {
            log.warn("zhaogang code checkpoint incomplete, jobId={}, depotId={}, reason={}, savedHeads={}, totalHeads={}",
                    job.getId(), depotId, warning, storedHeads, checkpoint.heads().size());
        }
        return storedHeads == 0 ? RESCAN + warning : PARTS + nextPart;
    }

    private static Checkpoint headPartition(Checkpoint source, Map<String, List<String>> byHead, List<String> keys) {
        Map<String, String> branches = new LinkedHashMap<>();
        Map<String, Head> heads = new LinkedHashMap<>();
        for (String sha : keys) {
            heads.put(sha, source.heads().get(sha));
            byHead.getOrDefault(sha, List.of()).forEach(branch -> branches.put(branch, sha));
        }
        return new Checkpoint(branches, heads);
    }

    private void publish(ScanJobEntity job, long depotId, String state, Previous previous) {
        int written = shared ? repos.saveRepositoryCheckpoint(job.getId(), depotId, state)
                : repos.saveSyncCheckpoint(job.getId(), depotId, state);
        if (written == 0) throw new IllegalStateException("Checkpoint repository row no longer exists");
        if (shared) return;
        try {
            if (previous.partitioned()) {
                repos.deleteOldCheckpointParts(job.getCodingTeamId(), job.getScopeType(), job.getScopeId(), depotId, job.getId());
            }
            repos.retireOldCheckpoints(job.getCodingTeamId(), job.getScopeType(), job.getScopeId(), depotId, job.getId());
        } catch (RuntimeException unavailable) {
            log.warn("zhaogang old code checkpoint cleanup failed, jobId={}, depotId={}, error={}",
                    job.getId(), depotId, unavailable.getClass().getSimpleName());
        }
    }
}
