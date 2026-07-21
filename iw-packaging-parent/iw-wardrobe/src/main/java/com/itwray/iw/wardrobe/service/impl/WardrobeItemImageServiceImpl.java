package com.itwray.iw.wardrobe.service.impl;

import com.itwray.iw.web.dao.BaseBusinessFileDao;
import com.itwray.iw.web.model.dto.FileDto;
import com.itwray.iw.web.model.enums.BusinessFileTypeEnum;
import com.itwray.iw.web.model.vo.FileRecordVo;
import com.itwray.iw.web.model.vo.FileVo;
import com.itwray.iw.web.utils.UserUtils;
import com.itwray.iw.wardrobe.dao.WardrobeItemDao;
import com.itwray.iw.wardrobe.dao.WardrobeOutfitDao;
import com.itwray.iw.wardrobe.dao.WardrobeOutfitItemDao;
import com.itwray.iw.wardrobe.dao.WardrobeWearRecordItemDao;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import com.itwray.iw.wardrobe.model.entity.WardrobeOutfitEntity;
import com.itwray.iw.wardrobe.model.entity.WardrobeOutfitItemEntity;
import com.itwray.iw.wardrobe.model.entity.WardrobeWearRecordItemEntity;
import com.itwray.iw.wardrobe.service.WardrobeImageFileCleanupService;
import com.itwray.iw.wardrobe.service.WardrobeImageOptimizationTaskService;
import com.itwray.iw.wardrobe.service.WardrobeItemImageService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 衣物原图与优化图服务实现。
 */
@Service
public class WardrobeItemImageServiceImpl implements WardrobeItemImageService {

    private final BaseBusinessFileDao baseBusinessFileDao;
    private final WardrobeImageFileCleanupService cleanupService;
    private final WardrobeImageOptimizationTaskService optimizationTaskService;
    private final WardrobeItemDao wardrobeItemDao;
    private final WardrobeOutfitDao wardrobeOutfitDao;
    private final WardrobeOutfitItemDao wardrobeOutfitItemDao;
    private final WardrobeWearRecordItemDao wardrobeWearRecordItemDao;

    public WardrobeItemImageServiceImpl(BaseBusinessFileDao baseBusinessFileDao,
                                        WardrobeImageFileCleanupService cleanupService,
                                        WardrobeImageOptimizationTaskService optimizationTaskService,
                                        WardrobeItemDao wardrobeItemDao,
                                        WardrobeOutfitDao wardrobeOutfitDao,
                                        WardrobeOutfitItemDao wardrobeOutfitItemDao,
                                        WardrobeWearRecordItemDao wardrobeWearRecordItemDao) {
        this.baseBusinessFileDao = baseBusinessFileDao;
        this.cleanupService = cleanupService;
        this.optimizationTaskService = optimizationTaskService;
        this.wardrobeItemDao = wardrobeItemDao;
        this.wardrobeOutfitDao = wardrobeOutfitDao;
        this.wardrobeOutfitItemDao = wardrobeOutfitItemDao;
        this.wardrobeWearRecordItemDao = wardrobeWearRecordItemDao;
    }

    @Override
    public Map<Integer, String> getOptimizedImageUrlMap(Collection<Integer> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return baseBusinessFileDao.getLatestBusinessFileMap(
                        itemIds,
                        BusinessFileTypeEnum.WARDROBE_ITEM_OPTIMIZED_IMAGE
                )
                .entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getFileUrl()));
    }

    @Override
    public void applyCoverImages(List<WardrobeItemEntity> itemList) {
        if (itemList == null || itemList.isEmpty()) {
            return;
        }
        Map<Integer, String> optimizedImageMap = this.getOptimizedImageUrlMap(
                itemList.stream().map(WardrobeItemEntity::getId).filter(Objects::nonNull).toList()
        );
        itemList.forEach(item -> {
            String optimizedImage = optimizedImageMap.get(item.getId());
            if (StringUtils.isNotBlank(optimizedImage)) {
                item.setItemImage(optimizedImage);
            }
        });
    }

    @Override
    @Transactional
    public void replaceOptimizedImage(Integer itemId, FileRecordVo fileRecord) {
        if (itemId == null || fileRecord == null || StringUtils.isBlank(fileRecord.getFileUrl())) {
            return;
        }
        List<FileVo> currentFiles = baseBusinessFileDao.getBusinessFile(
                itemId,
                BusinessFileTypeEnum.WARDROBE_ITEM_OPTIMIZED_IMAGE
        );
        if (currentFiles.size() == 1 && StringUtils.equals(currentFiles.get(0).getFileUrl(), fileRecord.getFileUrl())) {
            return;
        }

        FileDto fileDto = new FileDto();
        fileDto.setFileName(StringUtils.defaultIfBlank(fileRecord.getFileName(), "wardrobe-ai-optimized"));
        fileDto.setFileUrl(fileRecord.getFileUrl());
        baseBusinessFileDao.saveBusinessFile(
                itemId,
                BusinessFileTypeEnum.WARDROBE_ITEM_OPTIMIZED_IMAGE,
                List.of(fileDto)
        );

        List<String> replacedUrls = currentFiles.stream()
                .map(FileVo::getFileUrl)
                .filter(StringUtils::isNotBlank)
                .filter(url -> !StringUtils.equals(url, fileRecord.getFileUrl()))
                .distinct()
                .toList();
        this.replaceImageReferences(itemId, replacedUrls, fileRecord.getFileUrl());
        Integer userId = UserUtils.getUserId();
        replacedUrls.forEach(url -> {
            optimizationTaskService.markResultDeleted(itemId, url);
            cleanupService.enqueue("", itemId, null, url, "optimized_image_replaced", userId);
        });
    }

    @Override
    @Transactional
    public void deleteOptimizedImage(Integer itemId) {
        List<FileVo> currentFiles = baseBusinessFileDao.getBusinessFile(
                itemId,
                BusinessFileTypeEnum.WARDROBE_ITEM_OPTIMIZED_IMAGE
        );
        if (currentFiles.isEmpty()) {
            return;
        }
        List<String> fileUrls = currentFiles.stream()
                .map(FileVo::getFileUrl)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        String originalImage = wardrobeItemDao.queryById(itemId).getItemImage();
        baseBusinessFileDao.removeBusinessFile(itemId, BusinessFileTypeEnum.WARDROBE_ITEM_OPTIMIZED_IMAGE);
        this.replaceImageReferences(itemId, fileUrls, StringUtils.defaultString(originalImage));
        Integer userId = UserUtils.getUserId();
        fileUrls.forEach(url -> {
            optimizationTaskService.markResultDeleted(itemId, url);
            cleanupService.enqueue("", itemId, null, url, "optimized_image_deleted", userId);
        });
    }

    private void replaceImageReferences(Integer itemId, List<String> oldUrls, String replacementUrl) {
        if (itemId == null || oldUrls == null || oldUrls.isEmpty()) {
            return;
        }
        wardrobeOutfitItemDao.lambdaUpdate()
                .eq(WardrobeOutfitItemEntity::getItemId, itemId)
                .in(WardrobeOutfitItemEntity::getItemImage, oldUrls)
                .set(WardrobeOutfitItemEntity::getItemImage, StringUtils.defaultString(replacementUrl))
                .update();
        wardrobeWearRecordItemDao.lambdaUpdate()
                .eq(WardrobeWearRecordItemEntity::getItemId, itemId)
                .in(WardrobeWearRecordItemEntity::getItemImage, oldUrls)
                .set(WardrobeWearRecordItemEntity::getItemImage, StringUtils.defaultString(replacementUrl))
                .update();
        wardrobeOutfitDao.lambdaUpdate()
                .in(WardrobeOutfitEntity::getCoverImage, oldUrls)
                .set(WardrobeOutfitEntity::getCoverImage, StringUtils.defaultString(replacementUrl))
                .update();
    }

}
