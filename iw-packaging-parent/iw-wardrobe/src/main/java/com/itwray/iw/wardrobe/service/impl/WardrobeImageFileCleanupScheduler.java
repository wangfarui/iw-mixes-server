package com.itwray.iw.wardrobe.service.impl;

import com.itwray.iw.wardrobe.service.WardrobeImageFileCleanupService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WardrobeImageFileCleanupScheduler {

    private final WardrobeImageFileCleanupService cleanupService;

    public WardrobeImageFileCleanupScheduler(WardrobeImageFileCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @Scheduled(fixedDelayString = "${iw.wardrobe.image-cleanup.fixed-delay-ms:30000}")
    public void cleanNextFile() {
        cleanupService.processNext();
    }
}
