package com.itwray.iw.wardrobe.service.impl;

import com.itwray.iw.external.model.dto.AiImageReferenceGenerateDto;
import com.itwray.iw.external.model.enums.AiImageGenerateBusinessTypeEnum;
import com.itwray.iw.external.model.vo.AiImageReferenceGenerateVo;
import com.itwray.iw.web.model.vo.FileRecordVo;
import com.itwray.iw.web.service.FileService;
import com.itwray.iw.web.utils.UserUtils;
import com.itwray.iw.wardrobe.dao.WardrobeImageOptimizationTaskDao;
import com.itwray.iw.wardrobe.dao.WardrobeItemDao;
import com.itwray.iw.wardrobe.model.entity.WardrobeImageOptimizationAttemptEntity;
import com.itwray.iw.wardrobe.model.entity.WardrobeImageOptimizationTaskEntity;
import com.itwray.iw.wardrobe.service.WardrobeAssistantRemoteService;
import com.itwray.iw.wardrobe.service.WardrobeImageFileCleanupService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Set;

@Slf4j
@Component
public class WardrobeImageOptimizationWorker {

    private static final Set<String> FAILURE_STATUSES = Set.of("failed", "cancelled", "incomplete", "expired");

    private final WardrobeImageOptimizationClaimService claimService;
    private final WardrobeImageOptimizationCompletionService completionService;
    private final WardrobeImageOptimizationTaskDao taskDao;
    private final WardrobeItemDao itemDao;
    private final WardrobeAssistantRemoteService remoteService;
    private final FileService fileService;
    private final WardrobeImageFileCleanupService cleanupService;

    public WardrobeImageOptimizationWorker(WardrobeImageOptimizationClaimService claimService,
                                           WardrobeImageOptimizationCompletionService completionService,
                                           WardrobeImageOptimizationTaskDao taskDao,
                                           WardrobeItemDao itemDao,
                                           WardrobeAssistantRemoteService remoteService,
                                           FileService fileService,
                                           WardrobeImageFileCleanupService cleanupService) {
        this.claimService = claimService;
        this.completionService = completionService;
        this.taskDao = taskDao;
        this.itemDao = itemDao;
        this.remoteService = remoteService;
        this.fileService = fileService;
        this.cleanupService = cleanupService;
    }

    @Scheduled(fixedDelayString = "${iw.wardrobe.image-optimization.worker-fixed-delay-ms:1000}")
    public void processNext() {
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
            completionService.fail(claimed, e.getMessage());
        } finally {
            UserUtils.restoreContext(snapshot);
        }
    }

    private void execute(WardrobeImageOptimizationAttemptEntity claimed) {
        if (claimed.getDeadlineTime() != null && !LocalDateTime.now().isBefore(claimed.getDeadlineTime())) {
            completionService.fail(claimed, "图片优化超过15分钟截止时间，请重试");
            return;
        }
        WardrobeImageOptimizationTaskEntity task = taskDao.findByTaskId(claimed.getTaskId(), claimed.getUserId());
        if (task == null) {
            completionService.fail(claimed, "图片优化任务不存在");
            return;
        }
        AiImageReferenceGenerateVo response;
        if (StringUtils.isBlank(claimed.getExternalTaskId())) {
            AiImageReferenceGenerateDto request = new AiImageReferenceGenerateDto();
            request.setPrompt(task.getNormalizedPrompt());
            request.setImageUrl(task.getSourceImageUrl());
            request.setBusinessType(AiImageGenerateBusinessTypeEnum.WARDROBE_ITEM_IMAGE_OPTIMIZE.name());
            request.setBusinessCustomCategory(task.getRuleVersion());
            request.setBusinessId(String.valueOf(task.getItemId()));
            response = remoteService.startReferenceGenerateImage(request);
        } else {
            response = remoteService.getReferenceGenerateImageStatus(claimed.getExternalTaskId());
        }
        this.handleResponse(task, claimed, response);
    }

    private void handleResponse(WardrobeImageOptimizationTaskEntity task,
                                WardrobeImageOptimizationAttemptEntity claimed,
                                AiImageReferenceGenerateVo response) {
        if (response == null) {
            completionService.fail(claimed, "图片优化服务未返回结果");
            return;
        }
        String status = StringUtils.lowerCase(StringUtils.trimToEmpty(response.getStatus()));
        if (FAILURE_STATUSES.contains(status)) {
            completionService.fail(claimed, response.getErrorMessage());
            return;
        }
        if (StringUtils.isNotBlank(response.getImageBase64())) {
            this.uploadAndComplete(task, claimed, response);
            return;
        }
        String externalTaskId = StringUtils.defaultIfBlank(response.getTaskId(), claimed.getExternalTaskId());
        if (StringUtils.equals(status, "completed") || StringUtils.isBlank(externalTaskId)) {
            completionService.fail(claimed,
                    StringUtils.defaultIfBlank(response.getErrorMessage(), "图片优化服务未返回图片"));
            return;
        }
        completionService.defer(claimed, externalTaskId, response.getRevisedPrompt(), "iw-external");
    }

    private void uploadAndComplete(WardrobeImageOptimizationTaskEntity task,
                                   WardrobeImageOptimizationAttemptEntity claimed,
                                   AiImageReferenceGenerateVo response) {
        byte[] content = this.decodeImage(response.getImageBase64());
        String mimeType = StringUtils.defaultIfBlank(response.getMimeType(), "image/png");
        MultipartFile multipartFile = new GeneratedImageMultipartFile(
                "file", "wardrobe-optimized-" + task.getItemId() + this.extension(mimeType), mimeType, content);
        FileRecordVo uploaded = fileService.upload(multipartFile);
        if (uploaded == null || StringUtils.isBlank(uploaded.getFileUrl())) {
            completionService.fail(claimed, "图片优化结果上传失败");
            return;
        }
        try {
            completionService.complete(claimed, uploaded, mimeType, response.getRevisedPrompt(), response.getTaskId());
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

    private byte[] decodeImage(String imageBase64) {
        String value = StringUtils.trimToEmpty(imageBase64);
        int dataIndex = value.indexOf("base64,");
        if (dataIndex >= 0) {
            value = value.substring(dataIndex + "base64,".length());
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("图片优化结果解析失败", e);
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
