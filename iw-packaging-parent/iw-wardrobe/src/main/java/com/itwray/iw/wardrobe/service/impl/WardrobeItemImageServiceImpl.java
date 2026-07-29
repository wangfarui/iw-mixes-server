package com.itwray.iw.wardrobe.service.impl;

import com.itwray.iw.web.dao.BaseBusinessFileDao;
import com.itwray.iw.web.model.dto.FileDto;
import com.itwray.iw.web.model.enums.BusinessFileTypeEnum;
import com.itwray.iw.web.model.vo.FileRecordVo;
import com.itwray.iw.web.model.vo.FileVo;
import com.itwray.iw.web.utils.UserUtils;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
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
    private final WardrobeImageOptimizationTaskService optimizationTaskService;

    public WardrobeItemImageServiceImpl(BaseBusinessFileDao baseBusinessFileDao,
                                        WardrobeImageOptimizationTaskService optimizationTaskService) {
        this.baseBusinessFileDao = baseBusinessFileDao;
        this.optimizationTaskService = optimizationTaskService;
    }

    @Override
    public Map<Integer, String> getOptimizedImageUrlMap(Collection<Integer> itemIds,
                                                        Collection<Integer> ownerUserIds) {
        if (itemIds == null || itemIds.isEmpty() || ownerUserIds == null || ownerUserIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return baseBusinessFileDao.getLatestBusinessFileMap(
                        itemIds, BusinessFileTypeEnum.WARDROBE_ITEM_OPTIMIZED_IMAGE, ownerUserIds)
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
                itemList.stream().map(WardrobeItemEntity::getId).filter(Objects::nonNull).toList(),
                itemList.stream().map(WardrobeItemEntity::getUserId).filter(Objects::nonNull).distinct().toList());
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
        Integer ownerUserId = currentFiles.isEmpty() ? null : UserUtils.getUserId();
        if (ownerUserId != null) {
            replacedUrls.forEach(url -> optimizationTaskService.markResultDeleted(itemId, ownerUserId, url));
        }
    }

    @Override
    public void transferOwnership(Integer itemId, Integer previousOwnerUserId, Integer nextOwnerUserId) {
        if (Objects.equals(previousOwnerUserId, nextOwnerUserId)) {
            return;
        }
        baseBusinessFileDao.transferBusinessFileOwner(itemId,
                BusinessFileTypeEnum.WARDROBE_ITEM_OPTIMIZED_IMAGE,
                previousOwnerUserId, nextOwnerUserId);
    }

    @Override
    @Transactional
    public void deleteOptimizedImage(Integer itemId, Integer ownerUserId) {
        List<FileVo> currentFiles = baseBusinessFileDao.getBusinessFile(
                itemId,
                BusinessFileTypeEnum.WARDROBE_ITEM_OPTIMIZED_IMAGE,
                ownerUserId
        );
        if (currentFiles.isEmpty()) {
            return;
        }
        List<String> fileUrls = currentFiles.stream()
                .map(FileVo::getFileUrl)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        baseBusinessFileDao.removeBusinessFile(itemId, BusinessFileTypeEnum.WARDROBE_ITEM_OPTIMIZED_IMAGE,
                ownerUserId);
        // 历史搭配和穿着记录保存图片快照，因此关联移除后保留底层文件，不改写也不清理快照 URL。
        fileUrls.forEach(url -> optimizationTaskService.markResultDeleted(itemId, ownerUserId, url));
    }

}
