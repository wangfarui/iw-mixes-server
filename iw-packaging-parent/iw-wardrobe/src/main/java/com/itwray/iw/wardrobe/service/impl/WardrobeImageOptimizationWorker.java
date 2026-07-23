package com.itwray.iw.wardrobe.service.impl;

import com.itwray.iw.external.client.ReferenceImageClient;
import com.itwray.iw.external.model.dto.ReferenceImageGenerateDto;
import com.itwray.iw.external.model.vo.ReferenceImageGenerateVo;
import com.itwray.iw.web.model.vo.FileRecordVo;
import com.itwray.iw.web.service.FileService;
import com.itwray.iw.web.utils.UserUtils;
import com.itwray.iw.wardrobe.dao.WardrobeImageOptimizationTaskDao;
import com.itwray.iw.wardrobe.dao.WardrobeItemDao;
import com.itwray.iw.wardrobe.model.entity.WardrobeImageOptimizationAttemptEntity;
import com.itwray.iw.wardrobe.model.entity.WardrobeImageOptimizationTaskEntity;
import com.itwray.iw.wardrobe.service.WardrobeImageFileCleanupService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;

@Slf4j
@Component
public class WardrobeImageOptimizationWorker {

    private final WardrobeImageOptimizationClaimService claimService;
    private final WardrobeImageOptimizationCompletionService completionService;
    private final WardrobeImageOptimizationTaskDao taskDao;
    private final WardrobeItemDao itemDao;
    private final ReferenceImageClient referenceImageClient;
    private final FileService fileService;
    private final WardrobeImageFileCleanupService cleanupService;
    private final TaskExecutor taskExecutor;
    private final int workerConcurrency;

    public WardrobeImageOptimizationWorker(WardrobeImageOptimizationClaimService claimService,
                                           WardrobeImageOptimizationCompletionService completionService,
                                           WardrobeImageOptimizationTaskDao taskDao,
                                           WardrobeItemDao itemDao,
                                           ReferenceImageClient referenceImageClient,
                                           FileService fileService,
                                           WardrobeImageFileCleanupService cleanupService,
                                           @Qualifier("wardrobeImageOptimizationExecutor") TaskExecutor taskExecutor,
                                           @org.springframework.beans.factory.annotation.Value(
                                                   "${iw.wardrobe.image-optimization.worker-concurrency:1}")
                                           int workerConcurrency) {
        this.claimService = claimService;
        this.completionService = completionService;
        this.taskDao = taskDao;
        this.itemDao = itemDao;
        this.referenceImageClient = referenceImageClient;
        this.fileService = fileService;
        this.cleanupService = cleanupService;
        this.taskExecutor = taskExecutor;
        this.workerConcurrency = Math.max(1, workerConcurrency);
    }

    @Scheduled(fixedDelayString = "${iw.wardrobe.image-optimization.worker-fixed-delay-ms:1000}")
    public void scheduleWork() {
        for (int i = 0; i < workerConcurrency; i += 1) {
            try {
                taskExecutor.execute(this::processNext);
            } catch (TaskRejectedException ignored) {
                return;
            }
        }
    }

    private void processNext() {
        WardrobeImageOptimizationAttemptEntity claimed = claimService.claimNext();
        if (claimed == null) {
            return;
        }
        UserUtils.UserContextSnapshot snapshot = UserUtils.snapshotContext();
        UserUtils.setUserId(claimed.getUserId());
        try {
            this.execute(claimed);
        } catch (Exception e) {
            log.error("Wardrobe image optimization failed, taskId={}, attempt={}",
                    claimed.getTaskId(), claimed.getAttemptNo(), e);
            completionService.fail(claimed, "INTEGRATION_ERROR", "图片优化服务异常，请重试", "", "");
        } finally {
            UserUtils.restoreContext(snapshot);
        }
    }

    private void execute(WardrobeImageOptimizationAttemptEntity claimed) {
        if (!claimService.renewClaim(claimed)) {
            return;
        }
        if (claimed.getDeadlineTime() != null && !LocalDateTime.now().isBefore(claimed.getDeadlineTime())) {
            completionService.fail(claimed, "图片优化超过15分钟截止时间，请重试");
            return;
        }
        WardrobeImageOptimizationTaskEntity task = taskDao.findByTaskId(claimed.getTaskId(), claimed.getUserId());
        if (task == null) {
            completionService.fail(claimed, "图片优化任务不存在");
            return;
        }
        ReferenceImageGenerateDto request = new ReferenceImageGenerateDto();
        request.setPrompt(task.getNormalizedPrompt());
        request.setSourceImageUrl(task.getSourceImageUrl());
        ReferenceImageGenerateVo response = referenceImageClient.generate(request);
        if (!claimService.renewClaim(claimed)) {
            return;
        }
        this.handleResponse(task, claimed, response);
    }

    private void handleResponse(WardrobeImageOptimizationTaskEntity task,
                                WardrobeImageOptimizationAttemptEntity claimed,
                                ReferenceImageGenerateVo response) {
        if (response == null) {
            completionService.fail(claimed, "INTEGRATION_ERROR", "图片优化服务未返回结果", "", "");
            return;
        }
        if (!response.succeeded()) {
            completionService.fail(claimed,
                    response.getErrorCode() == null ? "INTEGRATION_ERROR" : response.getErrorCode().name(),
                    response.getMessage(), response.getProvider(), response.getModel());
            return;
        }
        if (response.getImageContent() != null && response.getImageContent().length > 0) {
            this.uploadAndComplete(task, claimed, response);
            return;
        }
        completionService.fail(claimed, "INTEGRATION_ERROR", "图片优化服务未返回图片",
                response.getProvider(), response.getModel());
    }

    private void uploadAndComplete(WardrobeImageOptimizationTaskEntity task,
                                   WardrobeImageOptimizationAttemptEntity claimed,
                                   ReferenceImageGenerateVo response) {
        if (!claimService.renewClaim(claimed)) {
            return;
        }
        byte[] content = response.getImageContent();
        String mimeType = StringUtils.defaultIfBlank(response.getMimeType(), "image/png");
        MultipartFile multipartFile = new GeneratedImageMultipartFile(
                "file", "wardrobe-optimized-" + task.getItemId() + this.extension(mimeType), mimeType, content);
        FileRecordVo uploaded = fileService.upload(multipartFile);
        if (uploaded == null || StringUtils.isBlank(uploaded.getFileUrl())) {
            completionService.fail(claimed, "INTEGRATION_ERROR", "图片优化结果上传失败",
                    response.getProvider(), response.getModel());
            return;
        }
        try {
            completionService.complete(claimed, uploaded, mimeType, response.getRevisedPrompt(),
                    response.getProvider(), response.getModel());
        } catch (Exception e) {
            boolean itemDeleted = itemDao.lambdaQuery()
                    .eq(com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity::getId, task.getItemId())
                    .last("limit 1")
                    .one() == null;
            String reason = itemDeleted
                    ? "item_deleted_late_result" : "uninstalled_generated_result";
            cleanupService.enqueueRequiresNew(claimed.getTaskId(), task.getItemId(), claimed.getAttemptNo(),
                    uploaded.getFileUrl(),
                    reason, claimed.getUserId());
            throw e;
        }
    }

    private String extension(String mimeType) {
        if (StringUtils.containsIgnoreCase(mimeType, "jpeg") || StringUtils.containsIgnoreCase(mimeType, "jpg")) {
            return ".jpg";
        }
        if (StringUtils.containsIgnoreCase(mimeType, "webp")) {
            return ".webp";
        }
        return ".png";
    }

    private static class GeneratedImageMultipartFile implements MultipartFile {

        private final String name;
        private final String originalFilename;
        private final String contentType;
        private final byte[] content;

        private GeneratedImageMultipartFile(String name, String originalFilename, String contentType, byte[] content) {
            this.name = name;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.content = content == null ? new byte[0] : content;
        }

        @Override public String getName() { return name; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return content.length == 0; }
        @Override public long getSize() { return content.length; }
        @Override public byte[] getBytes() { return content.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
        @Override public void transferTo(File dest) throws IOException, IllegalStateException {
            throw new UnsupportedOperationException("transferTo is not supported");
        }
    }
}
