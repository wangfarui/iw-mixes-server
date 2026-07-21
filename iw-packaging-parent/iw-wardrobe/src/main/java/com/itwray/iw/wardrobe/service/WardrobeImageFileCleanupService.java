package com.itwray.iw.wardrobe.service;

public interface WardrobeImageFileCleanupService {

    void enqueue(String taskId, Integer itemId, Integer attemptNo, String fileUrl, String reason, Integer userId);

    void enqueueRequiresNew(String taskId, Integer itemId, Integer attemptNo, String fileUrl, String reason,
                            Integer userId);

    boolean processNext();
}
