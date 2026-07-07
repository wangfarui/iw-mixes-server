package com.itwray.iw.wardrobe.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.itwray.iw.auth.client.BaseDictClient;
import com.itwray.iw.auth.model.vo.DictListVo;
import com.itwray.iw.external.model.dto.AiChatMessageDto;
import com.itwray.iw.external.model.dto.AiImageReferenceGenerateDto;
import com.itwray.iw.external.model.dto.AiStructuredChatDto;
import com.itwray.iw.external.model.enums.AiImageGenerateBusinessTypeEnum;
import com.itwray.iw.external.model.vo.AiImageReferenceGenerateVo;
import com.itwray.iw.external.model.vo.AiStructuredChatVo;
import com.itwray.iw.starter.redis.RedisUtil;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.exception.IwWebException;
import com.itwray.iw.web.model.enums.DictTypeEnum;
import com.itwray.iw.web.model.vo.FileRecordVo;
import com.itwray.iw.web.service.FileService;
import com.itwray.iw.web.utils.UserUtils;
import com.itwray.iw.wardrobe.dao.AiImageGenerateRecordDao;
import com.itwray.iw.wardrobe.dao.WardrobeItemDao;
import com.itwray.iw.wardrobe.model.dto.WardrobeAiSuggestDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemImageOptimizeDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemRecognizeDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeOutfitSuggestDto;
import com.itwray.iw.wardrobe.model.entity.AiImageGenerateRecordEntity;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import com.itwray.iw.wardrobe.model.vo.WardrobeAiSuggestVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeItemDraftVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeItemImageOptimizeTaskVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeOutfitItemVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeOutfitSuggestionVo;
import com.itwray.iw.wardrobe.service.WardrobeAssistantRemoteService;
import com.itwray.iw.wardrobe.service.WardrobeAssistantService;
import com.itwray.iw.wardrobe.service.WardrobeOutfitService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 衣柜 AI 助手服务实现
 *
 * @author codex
 * @since 2026-07-03
 */
@Slf4j
@Service
public class WardrobeAssistantServiceImpl implements WardrobeAssistantService {

    private static final String IMAGE_OPTIMIZE_TASK_KEY_PREFIX = "iw:wardrobe:ai:image-optimize:";
    private static final long IMAGE_OPTIMIZE_TASK_TTL_SECONDS = 30 * 60;
    private static final int IMAGE_OPTIMIZE_POLL_COUNT = 48;
    private static final long IMAGE_OPTIMIZE_POLL_INTERVAL_MILLIS = 5000L;
    private static final String TASK_STATUS_PROCESSING = "processing";
    private static final String TASK_STATUS_SUCCESS = "success";
    private static final String TASK_STATUS_FAILED = "failed";
    private static final String IMAGE_RECORD_RETRY_BLOCK_MESSAGE = "当前图片在该业务规则下已生成失败，请更换图片或调整业务分类后重新生成";
    private static final AtomicInteger IMAGE_OPTIMIZE_THREAD_INDEX = new AtomicInteger(1);
    private static final ExecutorService IMAGE_OPTIMIZE_EXECUTOR = Executors.newFixedThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "wardrobe-image-optimize-" + IMAGE_OPTIMIZE_THREAD_INDEX.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    });

    private static final Map<Integer, String> FALLBACK_CATEGORY_NAMES = Map.of(
            1, "上装",
            2, "下装",
            3, "外套",
            4, "连衣/套装",
            5, "鞋履",
            6, "包袋",
            7, "配饰",
            8, "其他"
    );

    private static final Map<String, String> FALLBACK_COLOR_HEX = Map.ofEntries(
            Map.entry("黑色", "#1f2329"),
            Map.entry("白色", "#f8f8f8"),
            Map.entry("灰色", "#8a8f99"),
            Map.entry("蓝色", "#2f80ed"),
            Map.entry("绿色", "#2e7d32"),
            Map.entry("红色", "#d93026"),
            Map.entry("黄色", "#f6c343"),
            Map.entry("粉色", "#e88aa8"),
            Map.entry("紫色", "#7e57c2"),
            Map.entry("棕色", "#8d6e63"),
            Map.entry("米色", "#d8c7a3"),
            Map.entry("彩色", "#6c8cff")
    );

    private final WardrobeOutfitService wardrobeOutfitService;
    private final WardrobeAssistantRemoteService remoteService;
    private final FileService fileService;
    private final WardrobeItemDao wardrobeItemDao;
    private final AiImageGenerateRecordDao aiImageGenerateRecordDao;
    private final BaseDictClient baseDictClient;

    public WardrobeAssistantServiceImpl(WardrobeOutfitService wardrobeOutfitService,
                                        WardrobeAssistantRemoteService remoteService,
                                        FileService fileService,
                                        WardrobeItemDao wardrobeItemDao,
                                        AiImageGenerateRecordDao aiImageGenerateRecordDao,
                                        BaseDictClient baseDictClient) {
        this.wardrobeOutfitService = wardrobeOutfitService;
        this.remoteService = remoteService;
        this.fileService = fileService;
        this.wardrobeItemDao = wardrobeItemDao;
        this.aiImageGenerateRecordDao = aiImageGenerateRecordDao;
        this.baseDictClient = baseDictClient;
    }

    @Override
    public WardrobeAiSuggestVo suggest(WardrobeAiSuggestDto dto) {
        WardrobeAiSuggestDto request = dto == null ? new WardrobeAiSuggestDto() : dto;
        WardrobeOutfitSuggestDto ruleDto = this.buildRuleDto(request);
        List<WardrobeOutfitSuggestionVo> suggestions = wardrobeOutfitService.suggest(ruleDto);

        WardrobeAiSuggestVo vo = new WardrobeAiSuggestVo();
        vo.setSource("rule");
        vo.setSuggestions(suggestions);
        vo.setSummary(this.buildRuleSummary(request, suggestions));
        if (Boolean.FALSE.equals(request.getUseAi()) || suggestions.isEmpty()) {
            return vo;
        }

        try {
            AiStructuredChatVo aiResponse = remoteService.structuredChat(this.buildAiRequest(request, suggestions));
            String content = aiResponse == null ? "" : StringUtils.trimToEmpty(aiResponse.getContent());
            if (StringUtils.isBlank(content) || StringUtils.containsAny(content, "服务请求失败", "服务请求超时")) {
                return vo;
            }
            vo.setSource("ai");
            vo.setRawAiContent(content);
            vo.setSummary(content);
        } catch (Exception e) {
            log.warn("WardrobeAssistantService#suggest AI unavailable, fallback to rules", e);
        }
        return vo;
    }

    @Override
    public WardrobeItemDraftVo recognizeItemDraft(WardrobeItemRecognizeDto dto) {
        WardrobeItemRecognizeDto request = dto == null ? new WardrobeItemRecognizeDto() : dto;
        String prompt = this.buildItemDraftPrompt(request);
        WardrobeItemDraftVo fallback = this.buildRuleItemDraft(request, prompt);
        if (StringUtils.isBlank(request.getImageUrl()) || Boolean.FALSE.equals(request.getUseAi())) {
            return fallback;
        }

        try {
            AiStructuredChatVo aiResponse = remoteService.structuredChat(this.buildItemDraftAiRequest(request, prompt));
            String content = aiResponse == null ? "" : StringUtils.trimToEmpty(aiResponse.getContent());
            if (StringUtils.isBlank(content) || StringUtils.containsAny(content, "服务请求失败", "服务请求超时")) {
                return fallback;
            }
            WardrobeItemDraftVo draft = this.parseItemDraft(content, request, prompt, fallback);
            if (draft == null) {
                fallback.setRawAiContent(content);
                return fallback;
            }
            draft.setSource("ai");
            draft.setRawAiContent(content);
            return draft;
        } catch (Exception e) {
            log.warn("WardrobeAssistantService#recognizeItemDraft AI unavailable, fallback to rules", e);
            return fallback;
        }
    }

    @Override
    public WardrobeItemImageOptimizeTaskVo startOptimizeItemImage(WardrobeItemImageOptimizeDto dto) {
        WardrobeItemImageOptimizeDto request = dto == null ? new WardrobeItemImageOptimizeDto() : dto;
        if (request.getItemId() == null) {
            throw new BusinessException("衣物ID不能为空");
        }
        if (StringUtils.isBlank(request.getImageUrl())) {
            throw new BusinessException("请先上传衣物图片");
        }

        WardrobeItemEntity itemEntity = wardrobeItemDao.queryById(request.getItemId());
        this.fillOptimizeRequestDefaults(request, itemEntity);

        OptimizeImageRecordContext recordContext = this.buildOptimizeImageRecordContext(request);
        AiImageGenerateRecordEntity existingRecord = this.getImageGenerateRecord(recordContext.dedupeKey());
        if (existingRecord != null) {
            return this.handleExistingOptimizeImageRecord(existingRecord, request);
        }

        String taskId = IdUtil.fastSimpleUUID();
        WardrobeItemImageOptimizeTaskVo taskVo = new WardrobeItemImageOptimizeTaskVo();
        taskVo.setTaskId(taskId);
        taskVo.setItemId(request.getItemId());
        taskVo.setUserId(UserUtils.getUserId());
        taskVo.setStatus(TASK_STATUS_PROCESSING);
        AiImageGenerateRecordEntity recordEntity = this.createOptimizeImageRecord(recordContext, taskId);
        if (!Objects.equals(recordContext.dedupeKey(), recordEntity.getDedupeKey())
                || !StringUtils.equals(recordEntity.getStatus(), TASK_STATUS_PROCESSING)
                || !StringUtils.equals(recordEntity.getTaskId(), taskId)) {
            return this.handleExistingOptimizeImageRecord(recordEntity, request);
        }
        this.saveOptimizeTask(taskVo);

        UserUtils.UserContextSnapshot userContextSnapshot = UserUtils.snapshotContext();
        IMAGE_OPTIMIZE_EXECUTOR.execute(() -> this.runOptimizeItemImageTask(taskId, request, recordEntity.getId(), recordContext.prompt(), userContextSnapshot));
        return taskVo;
    }

    @Override
    public WardrobeItemImageOptimizeTaskVo getOptimizeItemImageStatus(String taskId) {
        WardrobeItemImageOptimizeTaskVo taskVo = this.getOptimizeTask(taskId);
        if (taskVo == null || !Objects.equals(taskVo.getUserId(), UserUtils.getUserId())) {
            throw new BusinessException("任务不存在或已过期");
        }
        return taskVo;
    }

    private void runOptimizeItemImageTask(String taskId,
                                          WardrobeItemImageOptimizeDto request,
                                          Integer recordId,
                                          String prompt,
                                          UserUtils.UserContextSnapshot userContextSnapshot) {
        UserUtils.restoreContext(userContextSnapshot);
        try {
            OptimizeImageResult optimizeImageResult = this.executeOptimizeItemImage(request, prompt);
            String itemImage = optimizeImageResult.itemImage();
            WardrobeItemImageOptimizeTaskVo taskVo = this.getOptimizeTask(taskId);
            if (taskVo == null) {
                taskVo = new WardrobeItemImageOptimizeTaskVo();
                taskVo.setTaskId(taskId);
                taskVo.setItemId(request.getItemId());
                taskVo.setUserId(UserUtils.getUserId(false));
            }
            taskVo.setStatus(TASK_STATUS_SUCCESS);
            taskVo.setItemImage(itemImage);
            taskVo.setErrorMessage("");
            this.updateImageGenerateRecordSuccess(recordId, optimizeImageResult);
            this.saveOptimizeTask(taskVo);
        } catch (Exception e) {
            log.error("WardrobeAssistantService#runOptimizeItemImageTask failed, taskId: {}, itemId: {}", taskId, request.getItemId(), e);
            WardrobeItemImageOptimizeTaskVo taskVo = this.getOptimizeTask(taskId);
            if (taskVo == null) {
                taskVo = new WardrobeItemImageOptimizeTaskVo();
                taskVo.setTaskId(taskId);
                taskVo.setItemId(request.getItemId());
                taskVo.setUserId(UserUtils.getUserId(false));
            }
            taskVo.setStatus(TASK_STATUS_FAILED);
            taskVo.setErrorMessage(StringUtils.defaultIfBlank(e.getMessage(), "图片优化失败，请稍后重试"));
            this.updateImageGenerateRecordFailed(recordId, taskVo.getErrorMessage());
            this.saveOptimizeTask(taskVo);
        } finally {
            UserUtils.clearContext();
        }
    }

    private OptimizeImageResult executeOptimizeItemImage(WardrobeItemImageOptimizeDto request, String prompt) {
        wardrobeItemDao.queryById(request.getItemId());
        AiImageReferenceGenerateDto aiRequest = new AiImageReferenceGenerateDto();
        aiRequest.setImageUrl(request.getImageUrl());
        aiRequest.setPrompt(prompt);
        aiRequest.setBusinessType(AiImageGenerateBusinessTypeEnum.WARDROBE_ITEM_IMAGE_OPTIMIZE.name());
        aiRequest.setBusinessCustomCategory(StringUtils.defaultString(request.getCategoryName()));
        aiRequest.setBusinessId(String.valueOf(request.getItemId()));

        long startMillis = System.currentTimeMillis();
        AiImageReferenceGenerateVo aiResponse;
        try {
            aiResponse = remoteService.startReferenceGenerateImage(aiRequest);
            log.info("WardrobeAssistantService#executeOptimizeItemImage 调用AI图片生成完成, itemId: {}, elapsedMillis: {}, status: {}, taskId: {}, hasImage: {}",
                    request.getItemId(),
                    System.currentTimeMillis() - startMillis,
                    aiResponse == null ? null : aiResponse.getStatus(),
                    aiResponse == null ? null : aiResponse.getTaskId(),
                    this.hasGeneratedImage(aiResponse));
        } catch (Exception e) {
            log.error("WardrobeAssistantService#executeOptimizeItemImage 调用AI图片生成异常, itemId: {}, elapsedMillis: {}",
                    request.getItemId(), System.currentTimeMillis() - startMillis, e);
            throw e;
        }
        String externalTaskId = aiResponse == null ? "" : StringUtils.defaultString(aiResponse.getTaskId());
        aiResponse = this.waitForGeneratedImage(aiResponse);
        if (aiResponse == null || StringUtils.isBlank(aiResponse.getImageBase64())) {
            throw new BusinessException(StringUtils.defaultIfBlank(
                    aiResponse == null ? "" : aiResponse.getErrorMessage(),
                    "图片优化失败，请稍后重试"
            ));
        }

        String mimeType = StringUtils.defaultIfBlank(aiResponse.getMimeType(), "image/png");
        byte[] imageBytes = this.decodeImageBase64(aiResponse.getImageBase64());
        FileRecordVo fileRecord = fileService.upload(new ByteArrayMultipartFile(
                "file",
                this.optimizedImageFilename(mimeType),
                mimeType,
                imageBytes
        ));

        this.updateItemImage(request.getItemId(), fileRecord.getFileUrl());
        return new OptimizeImageResult(
                fileRecord.getFileUrl(),
                mimeType,
                externalTaskId,
                StringUtils.defaultString(aiResponse.getRevisedPrompt())
        );
    }

    private OptimizeImageRecordContext buildOptimizeImageRecordContext(WardrobeItemImageOptimizeDto request) {
        String businessType = AiImageGenerateBusinessTypeEnum.WARDROBE_ITEM_IMAGE_OPTIMIZE.name();
        String businessCustomCategory = StringUtils.defaultString(request.getCategoryName());
        String businessCategory = this.buildBusinessCategory(businessType, businessCustomCategory);
        String businessId = String.valueOf(request.getItemId());
        String sourceImageUrl = StringUtils.defaultString(request.getImageUrl());
        String dedupeKey = this.buildImageGenerateDedupeKey(sourceImageUrl, businessCategory, businessId);
        String prompt = this.buildItemImageOptimizePrompt(request);
        return new OptimizeImageRecordContext(
                dedupeKey,
                businessType,
                businessCustomCategory,
                businessCategory,
                businessId,
                sourceImageUrl,
                prompt
        );
    }

    private String buildBusinessCategory(String businessType, String businessCustomCategory) {
        return StringUtils.defaultString(businessType) + ":" + StringUtils.defaultString(businessCustomCategory);
    }

    private String buildImageGenerateDedupeKey(String sourceImageUrl, String businessCategory, String businessId) {
        return DigestUtil.sha256Hex(String.join("|",
                StringUtils.defaultString(sourceImageUrl),
                StringUtils.defaultString(businessCategory),
                StringUtils.defaultString(businessId)
        ));
    }

    private AiImageGenerateRecordEntity getImageGenerateRecord(String dedupeKey) {
        if (StringUtils.isBlank(dedupeKey)) {
            return null;
        }
        return aiImageGenerateRecordDao.lambdaQuery()
                .eq(AiImageGenerateRecordEntity::getDedupeKey, dedupeKey)
                .last("limit 1")
                .one();
    }

    private AiImageGenerateRecordEntity createOptimizeImageRecord(OptimizeImageRecordContext recordContext, String taskId) {
        AiImageGenerateRecordEntity recordEntity = new AiImageGenerateRecordEntity();
        recordEntity.setDedupeKey(recordContext.dedupeKey());
        recordEntity.setBusinessType(recordContext.businessType());
        recordEntity.setBusinessCustomCategory(recordContext.businessCustomCategory());
        recordEntity.setBusinessCategory(recordContext.businessCategory());
        recordEntity.setBusinessId(recordContext.businessId());
        recordEntity.setSourceImageUrl(recordContext.sourceImageUrl());
        recordEntity.setPrompt(recordContext.prompt());
        recordEntity.setTaskId(taskId);
        recordEntity.setStatus(TASK_STATUS_PROCESSING);
        recordEntity.setHitCount(1);
        recordEntity.setLastHitTime(LocalDateTime.now());
        try {
            aiImageGenerateRecordDao.save(recordEntity);
            return recordEntity;
        } catch (DuplicateKeyException e) {
            AiImageGenerateRecordEntity existingRecord = this.getImageGenerateRecord(recordContext.dedupeKey());
            if (existingRecord == null) {
                throw e;
            }
            return existingRecord;
        }
    }

    private WardrobeItemImageOptimizeTaskVo handleExistingOptimizeImageRecord(AiImageGenerateRecordEntity recordEntity,
                                                                              WardrobeItemImageOptimizeDto request) {
        this.touchImageGenerateRecord(recordEntity.getId());
        if (StringUtils.equals(recordEntity.getStatus(), TASK_STATUS_SUCCESS)
                && StringUtils.isNotBlank(recordEntity.getResultImageUrl())) {
            this.updateItemImage(request.getItemId(), recordEntity.getResultImageUrl());
            WardrobeItemImageOptimizeTaskVo taskVo = this.buildOptimizeTaskVo(
                    IdUtil.fastSimpleUUID(),
                    request.getItemId(),
                    TASK_STATUS_SUCCESS,
                    recordEntity.getResultImageUrl(),
                    ""
            );
            this.saveOptimizeTask(taskVo);
            return taskVo;
        }

        if (StringUtils.equals(recordEntity.getStatus(), TASK_STATUS_PROCESSING)) {
            WardrobeItemImageOptimizeTaskVo processingTask = this.getOptimizeTask(recordEntity.getTaskId());
            if (processingTask != null) {
                return processingTask;
            }
            String errorMessage = "AI图片生成任务状态已过期，请更换图片或调整业务分类后重新生成";
            this.markImageGenerateRecordFailed(recordEntity.getId(), errorMessage);
            WardrobeItemImageOptimizeTaskVo taskVo = this.buildOptimizeTaskVo(
                    IdUtil.fastSimpleUUID(),
                    request.getItemId(),
                    TASK_STATUS_FAILED,
                    "",
                    errorMessage
            );
            this.saveOptimizeTask(taskVo);
            return taskVo;
        }

        WardrobeItemImageOptimizeTaskVo taskVo = this.buildOptimizeTaskVo(
                IdUtil.fastSimpleUUID(),
                request.getItemId(),
                TASK_STATUS_FAILED,
                "",
                StringUtils.defaultIfBlank(recordEntity.getErrorMessage(), IMAGE_RECORD_RETRY_BLOCK_MESSAGE)
        );
        this.saveOptimizeTask(taskVo);
        return taskVo;
    }

    private WardrobeItemImageOptimizeTaskVo buildOptimizeTaskVo(String taskId,
                                                                Integer itemId,
                                                                String status,
                                                                String itemImage,
                                                                String errorMessage) {
        WardrobeItemImageOptimizeTaskVo taskVo = new WardrobeItemImageOptimizeTaskVo();
        taskVo.setTaskId(taskId);
        taskVo.setItemId(itemId);
        taskVo.setUserId(UserUtils.getUserId(false));
        taskVo.setStatus(status);
        taskVo.setItemImage(StringUtils.defaultString(itemImage));
        taskVo.setErrorMessage(StringUtils.defaultString(errorMessage));
        return taskVo;
    }

    private void touchImageGenerateRecord(Integer recordId) {
        if (recordId == null) {
            return;
        }
        aiImageGenerateRecordDao.lambdaUpdate()
                .eq(AiImageGenerateRecordEntity::getId, recordId)
                .set(AiImageGenerateRecordEntity::getLastHitTime, LocalDateTime.now())
                .setSql("hit_count = hit_count + 1")
                .update();
    }

    private void updateImageGenerateRecordSuccess(Integer recordId, OptimizeImageResult optimizeImageResult) {
        if (recordId == null || optimizeImageResult == null) {
            return;
        }
        aiImageGenerateRecordDao.lambdaUpdate()
                .eq(AiImageGenerateRecordEntity::getId, recordId)
                .set(AiImageGenerateRecordEntity::getStatus, TASK_STATUS_SUCCESS)
                .set(AiImageGenerateRecordEntity::getResultImageUrl, optimizeImageResult.itemImage())
                .set(AiImageGenerateRecordEntity::getResultMimeType, optimizeImageResult.mimeType())
                .set(AiImageGenerateRecordEntity::getExternalTaskId, optimizeImageResult.externalTaskId())
                .set(AiImageGenerateRecordEntity::getRevisedPrompt, optimizeImageResult.revisedPrompt())
                .set(AiImageGenerateRecordEntity::getErrorMessage, "")
                .update();
    }

    private void updateImageGenerateRecordFailed(Integer recordId, String errorMessage) {
        if (recordId == null) {
            return;
        }
        this.markImageGenerateRecordFailed(recordId, errorMessage);
    }

    private void markImageGenerateRecordFailed(Integer recordId, String errorMessage) {
        aiImageGenerateRecordDao.lambdaUpdate()
                .eq(AiImageGenerateRecordEntity::getId, recordId)
                .set(AiImageGenerateRecordEntity::getStatus, TASK_STATUS_FAILED)
                .set(AiImageGenerateRecordEntity::getErrorMessage, StringUtils.defaultIfBlank(errorMessage, IMAGE_RECORD_RETRY_BLOCK_MESSAGE))
                .update();
    }

    private void updateItemImage(Integer itemId, String itemImage) {
        boolean updated = wardrobeItemDao.lambdaUpdate()
                .eq(WardrobeItemEntity::getId, itemId)
                .set(WardrobeItemEntity::getItemImage, itemImage)
                .update();
        if (!updated) {
            throw new BusinessException("衣物图片更新失败");
        }
    }

    private String optimizedImageFilename(String mimeType) {
        if (StringUtils.equalsIgnoreCase(mimeType, "image/jpeg") || StringUtils.equalsIgnoreCase(mimeType, "image/jpg")) {
            return "wardrobe-ai-optimized.jpg";
        }
        if (StringUtils.equalsIgnoreCase(mimeType, "image/webp")) {
            return "wardrobe-ai-optimized.webp";
        }
        return "wardrobe-ai-optimized.png";
    }

    private AiImageReferenceGenerateVo waitForGeneratedImage(AiImageReferenceGenerateVo aiResponse) {
        if (this.hasGeneratedImage(aiResponse)) {
            return aiResponse;
        }
        if (aiResponse == null) {
            throw new BusinessException("AI图片生成任务启动失败");
        }
        if (this.isAiFailed(aiResponse)) {
            throw new BusinessException(StringUtils.defaultIfBlank(aiResponse.getErrorMessage(), "AI图片生成失败"));
        }
        if (StringUtils.isBlank(aiResponse.getTaskId())) {
            throw new BusinessException("AI图片生成任务启动失败");
        }

        for (int index = 0; index < IMAGE_OPTIMIZE_POLL_COUNT; index++) {
            this.sleepBeforePoll();
            AiImageReferenceGenerateVo current = remoteService.getReferenceGenerateImageStatus(aiResponse.getTaskId());
            if (this.hasGeneratedImage(current)) {
                return current;
            }
            if (this.isAiFailed(current)) {
                throw new BusinessException(StringUtils.defaultIfBlank(current.getErrorMessage(), "AI图片生成失败"));
            }
        }
        throw new BusinessException("AI图片生成超时，请稍后重试");
    }

    private boolean hasGeneratedImage(AiImageReferenceGenerateVo aiResponse) {
        return aiResponse != null && StringUtils.isNotBlank(aiResponse.getImageBase64());
    }

    private boolean isAiFailed(AiImageReferenceGenerateVo aiResponse) {
        if (aiResponse == null) {
            return true;
        }
        return StringUtils.equalsAnyIgnoreCase(aiResponse.getStatus(), "failed", "cancelled", "canceled", "incomplete");
    }

    private void sleepBeforePoll() {
        try {
            Thread.sleep(IMAGE_OPTIMIZE_POLL_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("AI图片生成任务已中断");
        }
    }

    private void fillOptimizeRequestDefaults(WardrobeItemImageOptimizeDto request, WardrobeItemEntity itemEntity) {
        if (StringUtils.isBlank(request.getImageUrl())) request.setImageUrl(itemEntity.getItemImage());
        if (StringUtils.isBlank(request.getItemName())) request.setItemName(itemEntity.getItemName());
        if (request.getCategory() == null) request.setCategory(itemEntity.getCategory());
        if (StringUtils.isBlank(request.getCategoryName())) request.setCategoryName(this.categoryName(request.getCategory()));
        if (StringUtils.isBlank(request.getColorName())) request.setColorName(itemEntity.getColorName());
        if (StringUtils.isBlank(request.getSeasonTags())) request.setSeasonTags(itemEntity.getSeasonTags());
        if (StringUtils.isBlank(request.getSceneTags())) request.setSceneTags(itemEntity.getSceneTags());
        if (StringUtils.isBlank(request.getStyleTags())) request.setStyleTags(itemEntity.getStyleTags());
        if (StringUtils.isBlank(request.getBrand())) request.setBrand(itemEntity.getBrand());
        if (StringUtils.isBlank(request.getMaterial())) request.setMaterial(itemEntity.getMaterial());
        if (StringUtils.isBlank(request.getCustomTags())) request.setCustomTags(itemEntity.getCustomTags());
        if (StringUtils.isBlank(request.getRemark())) request.setRemark(itemEntity.getRemark());
    }

    private void saveOptimizeTask(WardrobeItemImageOptimizeTaskVo taskVo) {
        RedisUtil.set(this.optimizeTaskKey(taskVo.getTaskId()), JSONUtil.toJsonStr(taskVo), IMAGE_OPTIMIZE_TASK_TTL_SECONDS);
    }

    private WardrobeItemImageOptimizeTaskVo getOptimizeTask(String taskId) {
        if (StringUtils.isBlank(taskId)) {
            return null;
        }
        Object value = RedisUtil.get(this.optimizeTaskKey(taskId));
        if (value == null) {
            return null;
        }
        return JSONUtil.toBean(String.valueOf(value), WardrobeItemImageOptimizeTaskVo.class);
    }

    private String optimizeTaskKey(String taskId) {
        return IMAGE_OPTIMIZE_TASK_KEY_PREFIX + taskId;
    }

    private WardrobeOutfitSuggestDto buildRuleDto(WardrobeAiSuggestDto request) {
        String prompt = StringUtils.defaultString(request.getPrompt());
        WardrobeOutfitSuggestDto dto = new WardrobeOutfitSuggestDto();
        dto.setLockedItemId(request.getLockedItemId());
        dto.setSeason(StringUtils.defaultIfBlank(request.getSeason(), this.inferSeason(prompt)));
        dto.setScene(StringUtils.defaultIfBlank(request.getScene(), this.inferScene(prompt)));
        dto.setStyle(StringUtils.defaultIfBlank(request.getStyle(), this.inferStyle(prompt)));
        dto.setWeatherText(StringUtils.defaultIfBlank(request.getWeatherText(), this.inferWeather(prompt)));
        dto.setLimit(request.getLimit());
        dto.setPreferIdle(Boolean.TRUE);
        dto.setAvoidRecentDays(3);
        List<Integer> excludeIds = new ArrayList<>();
        if (request.getExcludeItemIds() != null) {
            excludeIds.addAll(request.getExcludeItemIds().stream().filter(Objects::nonNull).toList());
        }
        if (request.getReplaceItemId() != null) {
            excludeIds.add(request.getReplaceItemId());
        }
        dto.setExcludeItemIds(excludeIds);
        return dto;
    }

    private AiStructuredChatDto buildAiRequest(WardrobeAiSuggestDto request, List<WardrobeOutfitSuggestionVo> suggestions) {
        AiChatMessageDto system = new AiChatMessageDto();
        system.setRole("system");
        system.setContent("你是私人穿搭助手。只能基于用户已有衣物候选给建议，回答要简短、可执行，不要编造不存在的单品。");

        AiChatMessageDto user = new AiChatMessageDto();
        user.setRole("user");
        user.setContent("""
                用户需求：%s
                天气：%s
                候选搭配：
                %s
                请给出一句总体建议，并说明最推荐哪一套、可以换哪一件。
                """.formatted(
                StringUtils.defaultIfBlank(request.getPrompt(), "日常穿搭"),
                StringUtils.defaultIfBlank(request.getWeatherText(), "未提供"),
                this.formatSuggestions(suggestions)
        ));

        AiStructuredChatDto dto = new AiStructuredChatDto();
        dto.setMessages(List.of(system, user));
        dto.setMaxTokens(500);
        dto.setTemperature(0.4D);
        return dto;
    }

    private AiStructuredChatDto buildItemDraftAiRequest(WardrobeItemRecognizeDto request, String prompt) {
        AiChatMessageDto system = new AiChatMessageDto();
        system.setRole("system");
        system.setContent("你是衣柜建档助手，只返回合法JSON，不输出额外说明。");

        AiChatMessageDto user = new AiChatMessageDto();
        user.setRole("user");
        user.setContent(List.of(
                Map.of("type", "text", "text", prompt),
                Map.of("type", "image_url", "image_url", Map.of("url", request.getImageUrl()))
        ));

        AiStructuredChatDto dto = new AiStructuredChatDto();
        dto.setMessages(List.of(system, user));
        dto.setMaxTokens(800);
        dto.setTemperature(0.2D);
        return dto;
    }

    private String buildItemDraftPrompt(WardrobeItemRecognizeDto request) {
        return """
                请观察用户提供的衣物图片，为新增衣物表单生成草稿。
                只能返回JSON对象，字段如下：
                itemName、category、categoryName、colorName、colorHex、seasonTags、sceneTags、styleTags、brand、size、material、purchaseChannel、storageLocation、purchaseDate、price、customTags、status、remark。
                category只能使用用户衣物分类字典编码：%s。
                colorName只能使用用户衣物颜色字典名称：%s。
                seasonTags只能使用spring,summer,autumn,winter。
                sceneTags只能使用用户衣物场景字典编码，多个用英文逗号拼接：%s。
                styleTags只能使用用户衣物风格字典编码，多个用英文逗号拼接：%s。
                无法从图片判断的字段返回空字符串，status默认1，price默认0。名称要短，优先使用颜色+品类。
                用户补充提示：%s
                """.formatted(
                this.formatCodeOptions(DictTypeEnum.WARDROBE_ITEM_CATEGORY),
                this.formatNameOptions(DictTypeEnum.WARDROBE_ITEM_COLOR),
                this.formatCodeOptions(DictTypeEnum.WARDROBE_ITEM_SCENE),
                this.formatCodeOptions(DictTypeEnum.WARDROBE_ITEM_STYLE),
                StringUtils.defaultIfBlank(request.getPrompt(), "无")
        );
    }

    private String buildItemImageOptimizePrompt(WardrobeItemImageOptimizeDto request) {
        String categoryName = StringUtils.defaultIfBlank(request.getCategoryName(), this.categoryName(request.getCategory()));
        return """
                参考用户提供的衣物图片，为衣柜应用生成一张新的单品图片。
                目标：只保留并重绘“%s”这一件衣物，不要生成真人、模特、全身穿搭、其它衣物或配饰。
                如果参考图是全身图、多人图或整套搭配，请根据当前衣物信息提取目标单品并生成单独的商品图。
                当前衣物信息：
                名称：%s
                分类：%s
                颜色：%s
                品牌：%s
                材质：%s
                季节：%s
                场景：%s
                风格：%s
                标签：%s
                备注：%s
                用户补充要求：%s
                生成要求：白色或浅灰纯净背景，衣物完整居中，保留原图可见的颜色、材质、版型、领型、袖长、图案和装饰细节，适合衣柜列表缩略图。不要添加文字、水印、价格、吊牌或不存在的品牌标识。
                """.formatted(
                categoryName,
                StringUtils.defaultIfBlank(request.getItemName(), "未命名衣物"),
                categoryName,
                StringUtils.defaultIfBlank(request.getColorName(), "按参考图判断"),
                StringUtils.defaultIfBlank(request.getBrand(), "无"),
                StringUtils.defaultIfBlank(request.getMaterial(), "按参考图判断"),
                StringUtils.defaultIfBlank(request.getSeasonTags(), "未设置"),
                this.dictTagNames(DictTypeEnum.WARDROBE_ITEM_SCENE, request.getSceneTags()),
                this.dictTagNames(DictTypeEnum.WARDROBE_ITEM_STYLE, request.getStyleTags()),
                StringUtils.defaultIfBlank(request.getCustomTags(), "无"),
                StringUtils.defaultIfBlank(request.getRemark(), "无"),
                StringUtils.defaultIfBlank(request.getPrompt(), "无")
        );
    }

    private WardrobeItemDraftVo buildRuleItemDraft(WardrobeItemRecognizeDto request, String prompt) {
        String userPrompt = request == null ? "" : StringUtils.defaultString(request.getPrompt());
        WardrobeItemDraftVo vo = new WardrobeItemDraftVo();
        vo.setItemImage(request == null ? "" : StringUtils.defaultString(request.getImageUrl()));
        vo.setCategory(this.inferItemCategory(userPrompt));
        vo.setCategoryName(this.categoryName(vo.getCategory()));
        vo.setColorName(this.inferColorName(userPrompt));
        vo.setColorHex(StringUtils.defaultString(FALLBACK_COLOR_HEX.get(vo.getColorName())));
        vo.setSeasonTags(this.inferSeason(userPrompt));
        vo.setSceneTags(this.inferScene(userPrompt));
        vo.setStyleTags(this.inferStyle(userPrompt));
        vo.setItemName(this.defaultItemName("", vo.getColorName(), vo.getCategoryName()));
        vo.setStatus(1);
        vo.setPrice(BigDecimal.ZERO);
        vo.setPrompt(prompt);
        vo.setSource("rule");
        vo.setRemark("图片已上传，可核对名称、分类、颜色后保存。");
        return vo;
    }

    private WardrobeItemDraftVo parseItemDraft(String content,
                                               WardrobeItemRecognizeDto request,
                                               String prompt,
                                               WardrobeItemDraftVo fallback) {
        try {
            JSONObject json = JSONUtil.parseObj(this.extractJson(content));
            WardrobeItemDraftVo vo = new WardrobeItemDraftVo();
            vo.setItemImage(StringUtils.defaultIfBlank(this.cleanText(json.get("itemImage")), request.getImageUrl()));

            Integer category = this.normalizeCategory(this.firstNonBlank(
                    this.cleanText(json.get("category")),
                    this.cleanText(json.get("categoryName"))
            ));
            vo.setCategory(category == null ? fallback.getCategory() : category);
            vo.setCategoryName(this.categoryName(vo.getCategory()));

            String colorName = this.normalizeColorName(json.get("colorName"), json.get("color"));
            vo.setColorName(StringUtils.defaultIfBlank(colorName, fallback.getColorName()));
            vo.setColorHex(this.normalizeColorHex(vo.getColorName(), json.get("colorHex")));

            vo.setSeasonTags(StringUtils.defaultIfBlank(this.normalizeTags(json.get("seasonTags"), "season"), fallback.getSeasonTags()));
            vo.setSceneTags(StringUtils.defaultIfBlank(this.normalizeTags(json.get("sceneTags"), "scene"), fallback.getSceneTags()));
            vo.setStyleTags(StringUtils.defaultIfBlank(this.normalizeTags(json.get("styleTags"), "style"), fallback.getStyleTags()));
            vo.setItemName(this.defaultItemName(this.cleanText(json.get("itemName")), vo.getColorName(), vo.getCategoryName()));
            vo.setBrand(this.cleanText(json.get("brand")));
            vo.setSize(this.cleanText(json.get("size")));
            vo.setMaterial(this.cleanText(json.get("material")));
            vo.setPurchaseChannel(this.cleanText(json.get("purchaseChannel")));
            vo.setStorageLocation(this.cleanText(json.get("storageLocation")));
            vo.setPurchaseDate(this.cleanText(json.get("purchaseDate")));
            vo.setPrice(this.normalizePrice(json.get("price")));
            vo.setCustomTags(this.normalizeCustomTags(json.get("customTags")));
            vo.setStatus(this.normalizeStatus(json.get("status")));
            vo.setRemark(StringUtils.defaultIfBlank(this.cleanText(json.get("remark")), fallback.getRemark()));
            vo.setPrompt(prompt);
            return vo;
        } catch (Exception e) {
            log.warn("WardrobeAssistantService#parseItemDraft invalid AI response: {}", content, e);
            return null;
        }
    }

    private String extractJson(String content) {
        String text = StringUtils.trimToEmpty(content);
        int jsonStart = text.indexOf('{');
        int jsonEnd = text.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return text.substring(jsonStart, jsonEnd + 1);
        }
        return text;
    }

    private String cleanText(Object value) {
        if (value == null) {
            return "";
        }
        String text = StringUtils.trimToEmpty(String.valueOf(value));
        if (StringUtils.equalsAnyIgnoreCase(text, "null", "undefined", "未知", "无法判断", "无", "none", "unknown")) {
            return "";
        }
        return text;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private Integer normalizeCategory(String value) {
        String text = StringUtils.trimToEmpty(value);
        if (StringUtils.isBlank(text)) {
            return null;
        }
        Integer dictCode = this.matchDictCode(DictTypeEnum.WARDROBE_ITEM_CATEGORY, text);
        if (dictCode != null) {
            return dictCode;
        }
        if (StringUtils.containsAny(text, "鞋", "靴", "拖鞋")) return 5;
        if (StringUtils.containsAny(text, "包", "背包", "手袋")) return 6;
        if (StringUtils.containsAny(text, "帽", "围巾", "项链", "耳环", "腰带", "配饰", "饰品")) return 7;
        if (StringUtils.containsAny(text, "外套", "大衣", "风衣", "羽绒服", "夹克", "西装")) return 3;
        if (StringUtils.containsAny(text, "连衣", "套装", "裙")) return 4;
        if (StringUtils.containsAny(text, "裤", "半裙")) return 2;
        if (StringUtils.containsAny(text, "上装", "上衣", "衬衫", "T恤", "短袖", "卫衣", "毛衣", "背心")) return 1;
        if (StringUtils.contains(text, "其他")) return 8;
        return null;
    }

    private Integer inferItemCategory(String prompt) {
        return Objects.requireNonNullElse(this.normalizeCategory(prompt), 8);
    }

    private String categoryName(Integer category) {
        if (category == null) {
            return "其他";
        }
        return this.getDictList(DictTypeEnum.WARDROBE_ITEM_CATEGORY).stream()
                .filter(dict -> Objects.equals(dict.getDictCode(), category))
                .map(DictListVo::getDictName)
                .findFirst()
                .orElse(FALLBACK_CATEGORY_NAMES.getOrDefault(category, "其他"));
    }

    private String normalizeColorName(Object... values) {
        List<DictListVo> colorDictList = this.getDictList(DictTypeEnum.WARDROBE_ITEM_COLOR);
        for (Object value : values) {
            String text = this.cleanText(value);
            if (StringUtils.isBlank(text)) {
                continue;
            }
            for (DictListVo colorDict : colorDictList) {
                String color = colorDict.getDictName();
                if (StringUtils.equals(text, color) || StringUtils.contains(text, color)) {
                    return color;
                }
            }
            for (String color : FALLBACK_COLOR_HEX.keySet()) {
                if (StringUtils.equals(text, color) || StringUtils.contains(text, color)) {
                    return color;
                }
            }
            if (StringUtils.containsAny(text, "黑", "玄")) return "黑色";
            if (StringUtils.containsAny(text, "白", "乳白")) return "白色";
            if (StringUtils.containsAny(text, "灰", "银")) return "灰色";
            if (StringUtils.containsAny(text, "蓝", "牛仔", "藏青")) return "蓝色";
            if (StringUtils.containsAny(text, "绿", "军绿")) return "绿色";
            if (StringUtils.containsAny(text, "红", "酒红")) return "红色";
            if (StringUtils.containsAny(text, "黄", "金")) return "黄色";
            if (StringUtils.containsAny(text, "粉", "玫")) return "粉色";
            if (StringUtils.containsAny(text, "紫")) return "紫色";
            if (StringUtils.containsAny(text, "棕", "咖", "褐")) return "棕色";
            if (StringUtils.containsAny(text, "米", "杏", "卡其")) return "米色";
            if (StringUtils.containsAny(text, "彩", "拼色", "多色")) return "彩色";
        }
        return "";
    }

    private String inferColorName(String prompt) {
        return this.normalizeColorName(prompt);
    }

    private String normalizeColorHex(String colorName, Object value) {
        String text = this.cleanText(value);
        if (StringUtils.isNotBlank(text) && text.matches("^#?[0-9a-fA-F]{6}$")) {
            return text.startsWith("#") ? text : "#" + text;
        }
        return StringUtils.defaultString(FALLBACK_COLOR_HEX.get(colorName));
    }

    private String normalizeTags(Object value, String type) {
        Set<String> tags = new LinkedHashSet<>();
        for (String token : this.toTextList(value)) {
            String tag = this.normalizeTagValue(token, type);
            if (StringUtils.isNotBlank(tag)) {
                tags.add(tag);
            }
        }
        return String.join(",", tags);
    }

    private List<String> toTextList(Object value) {
        List<String> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        if (value instanceof JSONArray jsonArray) {
            for (Object item : jsonArray) {
                this.addSplitTokens(result, this.cleanText(item));
            }
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                this.addSplitTokens(result, this.cleanText(item));
            }
            return result;
        }
        String text = this.cleanText(value);
        if (StringUtils.startsWith(text, "[") && StringUtils.endsWith(text, "]")) {
            try {
                JSONArray jsonArray = JSONUtil.parseArray(text);
                for (Object item : jsonArray) {
                    this.addSplitTokens(result, this.cleanText(item));
                }
                return result;
            } catch (Exception ignored) {
                // Continue with plain text splitting.
            }
        }
        this.addSplitTokens(result, text);
        return result;
    }

    private void addSplitTokens(List<String> result, String text) {
        if (StringUtils.isBlank(text)) {
            return;
        }
        for (String token : text.split("[,，、/\\s]+")) {
            if (StringUtils.isNotBlank(token)) {
                result.add(StringUtils.trim(token));
            }
        }
    }

    private String normalizeTagValue(String value, String type) {
        String text = StringUtils.trimToEmpty(value);
        String lower = StringUtils.lowerCase(text);
        return switch (type) {
            case "season" -> this.normalizeSeasonTag(text, lower);
            case "scene" -> this.normalizeSceneTag(text, lower);
            case "style" -> this.normalizeStyleTag(text, lower);
            default -> "";
        };
    }

    private String normalizeSeasonTag(String text, String lower) {
        if (StringUtils.equals(lower, "spring") || StringUtils.contains(text, "春")) return "spring";
        if (StringUtils.equals(lower, "summer") || StringUtils.containsAny(text, "夏", "热")) return "summer";
        if (StringUtils.equals(lower, "autumn") || StringUtils.contains(text, "秋")) return "autumn";
        if (StringUtils.equals(lower, "winter") || StringUtils.containsAny(text, "冬", "冷")) return "winter";
        return "";
    }

    private String normalizeSceneTag(String text, String lower) {
        Integer dictCode = this.matchDictCode(DictTypeEnum.WARDROBE_ITEM_SCENE, text);
        if (dictCode != null) return String.valueOf(dictCode);
        if (StringUtils.equals(lower, "daily") || StringUtils.containsAny(text, "日常", "休闲")) return "1";
        if (StringUtils.equals(lower, "work") || StringUtils.containsAny(text, "通勤", "上班", "办公")) return "2";
        if (StringUtils.equals(lower, "date") || StringUtils.contains(text, "约会")) return "3";
        if (StringUtils.equals(lower, "sport") || StringUtils.containsAny(text, "运动", "健身")) return "4";
        if (StringUtils.equals(lower, "travel") || StringUtils.containsAny(text, "旅行", "出差")) return "5";
        if (StringUtils.equals(lower, "formal") || StringUtils.containsAny(text, "正式", "会议", "面试")) return "6";
        if (StringUtils.equals(lower, "home") || StringUtils.containsAny(text, "居家", "在家")) return "7";
        return "";
    }

    private String normalizeStyleTag(String text, String lower) {
        Integer dictCode = this.matchDictCode(DictTypeEnum.WARDROBE_ITEM_STYLE, text);
        if (dictCode != null) return String.valueOf(dictCode);
        if (StringUtils.equals(lower, "casual") || StringUtils.contains(text, "休闲")) return "1";
        if (StringUtils.equals(lower, "simple") || StringUtils.containsAny(text, "简约", "基础")) return "2";
        if (StringUtils.equals(lower, "smart") || StringUtils.containsAny(text, "利落", "干练")) return "3";
        if (StringUtils.equals(lower, "sweet") || StringUtils.contains(text, "甜美")) return "4";
        if (StringUtils.equals(lower, "street") || StringUtils.contains(text, "街头")) return "5";
        if (StringUtils.equals(lower, "retro") || StringUtils.contains(text, "复古")) return "6";
        if (StringUtils.equals(lower, "outdoor") || StringUtils.containsAny(text, "户外", "露营")) return "7";
        return "";
    }

    private String defaultItemName(String itemName, String colorName, String categoryName) {
        if (StringUtils.isNotBlank(itemName)) {
            return itemName;
        }
        if (StringUtils.isNotBlank(colorName) && StringUtils.isNotBlank(categoryName) && !"其他".equals(categoryName)) {
            return colorName + categoryName;
        }
        if (StringUtils.isNotBlank(categoryName) && !"其他".equals(categoryName)) {
            return categoryName;
        }
        return "待识别衣物";
    }

    private BigDecimal normalizePrice(Object value) {
        String text = this.cleanText(value)
                .replace(",", "")
                .replace("￥", "")
                .replace("¥", "")
                .replace("元", "");
        if (StringUtils.isBlank(text)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(text);
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }

    private String normalizeCustomTags(Object value) {
        return this.toTextList(value).stream()
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.joining(","));
    }

    private Integer normalizeStatus(Object value) {
        String text = this.cleanText(value);
        if (StringUtils.isBlank(text)) {
            return 1;
        }
        try {
            int status = new BigDecimal(text).intValue();
            if (status >= 1 && status <= 5) {
                return status;
            }
        } catch (Exception ignored) {
            // Continue with semantic matching.
        }
        if (StringUtils.contains(text, "闲置")) return 2;
        if (StringUtils.contains(text, "清洗")) return 3;
        if (StringUtils.containsAny(text, "维修", "修补")) return 4;
        if (StringUtils.containsAny(text, "淘汰", "捐赠", "丢弃")) return 5;
        return 1;
    }

    private String formatSuggestions(List<WardrobeOutfitSuggestionVo> suggestions) {
        return suggestions.stream()
                .map(suggestion -> suggestion.getSuggestionName() + "：" + suggestion.getItemList().stream()
                        .map(WardrobeOutfitItemVo::getItemName)
                        .collect(Collectors.joining("、")))
                .collect(Collectors.joining("\n"));
    }

    private String buildRuleSummary(WardrobeAiSuggestDto request, List<WardrobeOutfitSuggestionVo> suggestions) {
        if (suggestions.isEmpty()) {
            return "当前衣物不足，先补充不同分类衣物后再生成搭配。";
        }
        String prompt = StringUtils.defaultIfBlank(request.getPrompt(), "你的衣橱");
        return "已按「" + prompt + "」生成 " + suggestions.size() + " 套候选，优先使用较少穿和最近未穿的衣物。";
    }

    private String inferSeason(String prompt) {
        if (StringUtils.contains(prompt, "春")) return "spring";
        if (StringUtils.containsAny(prompt, "夏", "热", "高温")) return "summer";
        if (StringUtils.contains(prompt, "秋")) return "autumn";
        if (StringUtils.containsAny(prompt, "冬", "冷", "降温")) return "winter";
        return "";
    }

    private String inferScene(String prompt) {
        return this.normalizeSceneTag(StringUtils.defaultString(prompt), StringUtils.lowerCase(prompt));
    }

    private String inferStyle(String prompt) {
        return this.normalizeStyleTag(StringUtils.defaultString(prompt), StringUtils.lowerCase(prompt));
    }

    private String inferWeather(String prompt) {
        if (StringUtils.contains(prompt, "雨")) return "雨天";
        if (StringUtils.containsAny(prompt, "冷", "降温")) return "偏冷";
        if (StringUtils.containsAny(prompt, "热", "高温")) return "偏热";
        if (StringUtils.containsAny(prompt, "风", "大风")) return "有风";
        return "";
    }

    private List<DictListVo> getDictList(DictTypeEnum dictTypeEnum) {
        try {
            List<DictListVo> dictList = baseDictClient.getDictListByType(dictTypeEnum.getCode());
            if (dictList != null && !dictList.isEmpty()) {
                return dictList;
            }
        } catch (Exception e) {
            log.warn("WardrobeAssistantService#getDictList fallback, dictType: {}", dictTypeEnum.getCode(), e);
        }
        return this.fallbackDictList(dictTypeEnum);
    }

    private Integer matchDictCode(DictTypeEnum dictTypeEnum, String value) {
        String text = StringUtils.trimToEmpty(value);
        if (StringUtils.isBlank(text)) {
            return null;
        }
        List<DictListVo> dictList = this.getDictList(dictTypeEnum);
        try {
            int number = new BigDecimal(text).intValue();
            boolean exists = dictList.stream().anyMatch(dict -> Objects.equals(dict.getDictCode(), number));
            if (exists) {
                return number;
            }
        } catch (Exception ignored) {
            // Continue with name matching.
        }
        return dictList.stream()
                .filter(dict -> StringUtils.isNotBlank(dict.getDictName()))
                .filter(dict -> StringUtils.equals(text, dict.getDictName()) || StringUtils.contains(text, dict.getDictName()))
                .map(DictListVo::getDictCode)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private String formatCodeOptions(DictTypeEnum dictTypeEnum) {
        return this.getDictList(dictTypeEnum).stream()
                .map(dict -> dict.getDictCode() + dict.getDictName())
                .collect(Collectors.joining("、"));
    }

    private String formatNameOptions(DictTypeEnum dictTypeEnum) {
        return this.getDictList(dictTypeEnum).stream()
                .map(DictListVo::getDictName)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining("、"));
    }

    private String dictTagNames(DictTypeEnum dictTypeEnum, String tags) {
        List<String> values = this.toTextList(tags);
        if (values.isEmpty()) {
            return "未设置";
        }
        List<DictListVo> dictList = this.getDictList(dictTypeEnum);
        return values.stream()
                .map(value -> {
                    Integer code = this.matchDictCode(dictTypeEnum, value);
                    if (code == null) {
                        return value;
                    }
                    return dictList.stream()
                            .filter(dict -> Objects.equals(dict.getDictCode(), code))
                            .map(DictListVo::getDictName)
                            .findFirst()
                            .orElse(value);
                })
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining("、"));
    }

    private List<DictListVo> fallbackDictList(DictTypeEnum dictTypeEnum) {
        return switch (dictTypeEnum) {
            case WARDROBE_ITEM_CATEGORY -> List.of(
                    this.dict(1, "上装"),
                    this.dict(2, "下装"),
                    this.dict(3, "外套"),
                    this.dict(4, "连衣/套装"),
                    this.dict(5, "鞋履"),
                    this.dict(6, "包袋"),
                    this.dict(7, "配饰"),
                    this.dict(8, "其他")
            );
            case WARDROBE_ITEM_COLOR -> List.of(
                    this.dict(1, "黑色"),
                    this.dict(2, "白色"),
                    this.dict(3, "灰色"),
                    this.dict(4, "蓝色"),
                    this.dict(5, "绿色"),
                    this.dict(6, "红色"),
                    this.dict(7, "黄色"),
                    this.dict(8, "粉色"),
                    this.dict(9, "紫色"),
                    this.dict(10, "棕色"),
                    this.dict(11, "米色"),
                    this.dict(12, "彩色")
            );
            case WARDROBE_ITEM_SCENE -> List.of(
                    this.dict(1, "日常"),
                    this.dict(2, "通勤"),
                    this.dict(3, "约会"),
                    this.dict(4, "运动"),
                    this.dict(5, "旅行"),
                    this.dict(6, "正式"),
                    this.dict(7, "居家")
            );
            case WARDROBE_ITEM_STYLE -> List.of(
                    this.dict(1, "休闲"),
                    this.dict(2, "简约"),
                    this.dict(3, "利落"),
                    this.dict(4, "甜美"),
                    this.dict(5, "街头"),
                    this.dict(6, "复古"),
                    this.dict(7, "户外")
            );
            default -> List.of();
        };
    }

    private DictListVo dict(Integer code, String name) {
        DictListVo vo = new DictListVo();
        vo.setDictCode(code);
        vo.setDictName(name);
        return vo;
    }

    private byte[] decodeImageBase64(String imageBase64) {
        String value = StringUtils.trimToEmpty(imageBase64);
        int dataIndex = value.indexOf("base64,");
        if (dataIndex >= 0) {
            value = value.substring(dataIndex + "base64,".length());
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (Exception e) {
            throw new IwWebException("图片优化结果解析失败");
        }
    }

    private record OptimizeImageRecordContext(String dedupeKey,
                                              String businessType,
                                              String businessCustomCategory,
                                              String businessCategory,
                                              String businessId,
                                              String sourceImageUrl,
                                              String prompt) {
    }

    private record OptimizeImageResult(String itemImage,
                                       String mimeType,
                                       String externalTaskId,
                                       String revisedPrompt) {
    }

    private static class ByteArrayMultipartFile implements MultipartFile {

        private final String name;
        private final String originalFilename;
        private final String contentType;
        private final byte[] content;

        private ByteArrayMultipartFile(String name, String originalFilename, String contentType, byte[] content) {
            this.name = name;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.content = content == null ? new byte[0] : content;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content.clone();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
            throw new UnsupportedOperationException("transferTo is not supported");
        }
    }
}
