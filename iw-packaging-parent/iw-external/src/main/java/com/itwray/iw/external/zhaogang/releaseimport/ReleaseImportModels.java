package com.itwray.iw.external.zhaogang.releaseimport;

import java.util.List;

public final class ReleaseImportModels {

    private ReleaseImportModels() {
    }

    public enum Status {
        READY,
        PROJECT_AMBIGUOUS,
        PLAN_AMBIGUOUS,
        UNMATCHED,
        DUPLICATE_IN_IMAGE,
        ALREADY_ADDED,
        UNBUILDABLE,
        CATALOG_UNAVAILABLE
    }

    public record RecognizedRow(String requirement, String ops, String systemName,
                                String projectHint, String planHint) {
    }

    public record Candidate(long projectId, String projectName, String projectDisplayName,
                            long planId, String planName, boolean quickBuildSupported) {
    }

    public record MatchRow(int rowNo, RecognizedRow recognized, Status status,
                           Long projectId, String projectName, Long planId, String planName,
                           List<Candidate> candidates, String message) {
        public MatchRow {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    public record Preview(List<MatchRow> items) {
        public Preview {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record MatchCommand(List<RecognizedRow> items) {
        public MatchCommand {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record AddItem(int rowNo, long projectId, long planId) {
    }

    public record BatchAddCommand(List<AddItem> items) {
        public BatchAddCommand {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record BatchAddFailure(int rowNo, long projectId, long planId, String reason) {
    }

    public record BatchAddResult(int successCount, int failureCount, List<BatchAddFailure> failures) {
        public BatchAddResult {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
    }
}
