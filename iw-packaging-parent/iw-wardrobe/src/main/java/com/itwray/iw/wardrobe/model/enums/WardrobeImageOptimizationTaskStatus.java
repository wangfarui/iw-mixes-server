package com.itwray.iw.wardrobe.model.enums;

import lombok.Getter;

@Getter
public enum WardrobeImageOptimizationTaskStatus {

    QUEUED("queued"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String code;

    WardrobeImageOptimizationTaskStatus(String code) {
        this.code = code;
    }

    public boolean isActive() {
        return this == QUEUED || this == RUNNING;
    }
}
