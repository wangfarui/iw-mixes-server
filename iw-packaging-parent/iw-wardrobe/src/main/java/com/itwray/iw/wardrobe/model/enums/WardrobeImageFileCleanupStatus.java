package com.itwray.iw.wardrobe.model.enums;

import lombok.Getter;

@Getter
public enum WardrobeImageFileCleanupStatus {
    PENDING("pending"),
    RETRYING("retrying"),
    SUCCEEDED("succeeded"),
    MANUAL_REQUIRED("manual_required");

    private final String code;

    WardrobeImageFileCleanupStatus(String code) {
        this.code = code;
    }
}
