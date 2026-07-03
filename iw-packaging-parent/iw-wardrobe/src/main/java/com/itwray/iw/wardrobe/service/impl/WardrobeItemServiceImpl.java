package com.itwray.iw.wardrobe.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.itwray.iw.wardrobe.dao.WardrobeItemDao;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemAddDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemBatchAddDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemPageDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemUpdateDto;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import com.itwray.iw.wardrobe.model.vo.WardrobeItemDetailVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeItemPageVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeTagSummaryVo;
import com.itwray.iw.wardrobe.service.WardrobeItemService;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.model.vo.PageVo;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 衣物服务实现
 *
 * @author codex
 * @since 2026-07-02
 */
@Service
public class WardrobeItemServiceImpl implements WardrobeItemService {

    private static final int STATUS_ACTIVE = 1;

    private final WardrobeItemDao wardrobeItemDao;

    public WardrobeItemServiceImpl(WardrobeItemDao wardrobeItemDao) {
        this.wardrobeItemDao = wardrobeItemDao;
    }

    @Override
    @Transactional
    public Integer add(WardrobeItemAddDto dto) {
        WardrobeItemEntity entity = BeanUtil.copyProperties(dto, WardrobeItemEntity.class);
        this.fillItemDefaults(entity);
        wardrobeItemDao.save(entity);
        return entity.getId();
    }

    @Override
    @Transactional
    public List<Integer> batchAdd(WardrobeItemBatchAddDto dto) {
        if (dto.getItemList() == null || dto.getItemList().isEmpty()) {
            throw new BusinessException("衣物列表不能为空");
        }
        if (dto.getItemList().size() > 50) {
            throw new BusinessException("单次最多新增50件衣物");
        }
        List<WardrobeItemEntity> entityList = dto.getItemList()
                .stream()
                .map(item -> {
                    WardrobeItemEntity entity = BeanUtil.copyProperties(item, WardrobeItemEntity.class);
                    this.fillItemDefaults(entity);
                    return entity;
                })
                .toList();
        wardrobeItemDao.saveBatch(entityList);
        return entityList.stream().map(WardrobeItemEntity::getId).toList();
    }

    @Override
    @Transactional
    public void update(WardrobeItemUpdateDto dto) {
        wardrobeItemDao.queryById(dto.getId());
        WardrobeItemEntity entity = BeanUtil.copyProperties(dto, WardrobeItemEntity.class);
        this.fillItemDefaults(entity);
        wardrobeItemDao.updateById(entity);
    }

    @Override
    @Transactional
    public void delete(Integer id) {
        wardrobeItemDao.queryById(id);
        wardrobeItemDao.removeById(id);
    }

    @Override
    public PageVo<WardrobeItemPageVo> page(WardrobeItemPageDto dto) {
        LambdaQueryWrapper<WardrobeItemEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(StringUtils.isNotBlank(dto.getItemName()), WardrobeItemEntity::getItemName, dto.getItemName())
                .eq(Objects.nonNull(dto.getCategory()), WardrobeItemEntity::getCategory, dto.getCategory())
                .like(StringUtils.isNotBlank(dto.getColorName()), WardrobeItemEntity::getColorName, dto.getColorName())
                .like(StringUtils.isNotBlank(dto.getSeason()), WardrobeItemEntity::getSeasonTags, dto.getSeason())
                .like(StringUtils.isNotBlank(dto.getScene()), WardrobeItemEntity::getSceneTags, dto.getScene())
                .like(StringUtils.isNotBlank(dto.getStyle()), WardrobeItemEntity::getStyleTags, dto.getStyle())
                .like(StringUtils.isNotBlank(dto.getBrand()), WardrobeItemEntity::getBrand, dto.getBrand())
                .like(StringUtils.isNotBlank(dto.getSize()), WardrobeItemEntity::getSize, dto.getSize())
                .like(StringUtils.isNotBlank(dto.getMaterial()), WardrobeItemEntity::getMaterial, dto.getMaterial())
                .like(StringUtils.isNotBlank(dto.getPurchaseChannel()), WardrobeItemEntity::getPurchaseChannel, dto.getPurchaseChannel())
                .like(StringUtils.isNotBlank(dto.getStorageLocation()), WardrobeItemEntity::getStorageLocation, dto.getStorageLocation())
                .like(StringUtils.isNotBlank(dto.getCustomTag()), WardrobeItemEntity::getCustomTags, dto.getCustomTag())
                .eq(Objects.nonNull(dto.getStatus()), WardrobeItemEntity::getStatus, dto.getStatus())
                .ge(Objects.nonNull(dto.getMinPrice()), WardrobeItemEntity::getPrice, dto.getMinPrice())
                .le(Objects.nonNull(dto.getMaxPrice()), WardrobeItemEntity::getPrice, dto.getMaxPrice())
                .ge(Objects.nonNull(dto.getPurchaseStartDate()), WardrobeItemEntity::getPurchaseDate, dto.getPurchaseStartDate())
                .le(Objects.nonNull(dto.getPurchaseEndDate()), WardrobeItemEntity::getPurchaseDate, dto.getPurchaseEndDate());
        if (dto.getIdleDays() != null && dto.getIdleDays() > 0) {
            LocalDate idleBefore = LocalDate.now().minusDays(dto.getIdleDays());
            queryWrapper.and(wrapper -> wrapper.isNull(WardrobeItemEntity::getLastWearDate)
                    .or()
                    .le(WardrobeItemEntity::getLastWearDate, idleBefore));
        }
        if (Objects.equals(dto.getWearState(), 1)) {
            queryWrapper.eq(WardrobeItemEntity::getWearCount, 0).orderByDesc(WardrobeItemEntity::getId);
        } else if (Objects.equals(dto.getWearState(), 2)) {
            queryWrapper.orderByDesc(WardrobeItemEntity::getLastWearDate).orderByDesc(WardrobeItemEntity::getId);
        } else if (Objects.equals(dto.getWearState(), 3)) {
            queryWrapper.orderByAsc(WardrobeItemEntity::getWearCount).orderByDesc(WardrobeItemEntity::getId);
        } else {
            this.applySort(queryWrapper, dto.getSortType());
        }
        return wardrobeItemDao.page(dto, queryWrapper, WardrobeItemPageVo.class);
    }

    @Override
    public WardrobeItemDetailVo detail(Integer id) {
        return BeanUtil.copyProperties(wardrobeItemDao.queryById(id), WardrobeItemDetailVo.class);
    }

    @Override
    public WardrobeTagSummaryVo tagSummary() {
        List<WardrobeItemEntity> itemList = wardrobeItemDao.lambdaQuery()
                .eq(WardrobeItemEntity::getStatus, STATUS_ACTIVE)
                .list();
        WardrobeTagSummaryVo vo = new WardrobeTagSummaryVo();
        vo.setBrands(this.collectValues(itemList, WardrobeItemEntity::getBrand));
        vo.setColors(this.collectValues(itemList, WardrobeItemEntity::getColorName));
        vo.setMaterials(this.collectValues(itemList, WardrobeItemEntity::getMaterial));
        vo.setPurchaseChannels(this.collectValues(itemList, WardrobeItemEntity::getPurchaseChannel));
        vo.setStorageLocations(this.collectValues(itemList, WardrobeItemEntity::getStorageLocation));
        vo.setCustomTags(this.collectTags(itemList));
        return vo;
    }

    @Override
    public List<WardrobeItemEntity> queryActiveItemsByIds(List<Integer> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return Collections.emptyList();
        }
        return wardrobeItemDao.lambdaQuery()
                .in(WardrobeItemEntity::getId, itemIds)
                .eq(WardrobeItemEntity::getStatus, STATUS_ACTIVE)
                .list();
    }

    @Override
    @Transactional
    public void increaseWearCount(List<Integer> itemIds, LocalDate wearDate) {
        if (itemIds == null || itemIds.isEmpty()) {
            return;
        }
        wardrobeItemDao.lambdaUpdate()
                .in(WardrobeItemEntity::getId, itemIds)
                .set(WardrobeItemEntity::getLastWearDate, wearDate)
                .setSql("wear_count = wear_count + 1")
                .update();
    }

    private void fillItemDefaults(WardrobeItemEntity entity) {
        if (StringUtils.isBlank(entity.getItemName())) {
            throw new BusinessException("衣物名称不能为空");
        }
        entity.setItemName(entity.getItemName().trim());
        entity.setCategory(Objects.requireNonNullElse(entity.getCategory(), 0));
        entity.setColorName(StringUtils.defaultString(entity.getColorName()));
        entity.setColorHex(StringUtils.defaultString(entity.getColorHex()));
        entity.setSeasonTags(StringUtils.defaultString(entity.getSeasonTags()));
        entity.setSceneTags(StringUtils.defaultString(entity.getSceneTags()));
        entity.setStyleTags(StringUtils.defaultString(entity.getStyleTags()));
        entity.setBrand(StringUtils.defaultString(entity.getBrand()));
        entity.setSize(StringUtils.defaultString(entity.getSize()));
        entity.setMaterial(StringUtils.defaultString(entity.getMaterial()));
        entity.setPurchaseChannel(StringUtils.defaultString(entity.getPurchaseChannel()));
        entity.setStorageLocation(StringUtils.defaultString(entity.getStorageLocation()));
        entity.setCustomTags(StringUtils.defaultString(entity.getCustomTags()));
        entity.setPrice(entity.getPrice() == null ? BigDecimal.ZERO : entity.getPrice());
        entity.setStatus(Objects.requireNonNullElse(entity.getStatus(), STATUS_ACTIVE));
        entity.setRemark(StringUtils.defaultString(entity.getRemark()));
    }

    private void applySort(LambdaQueryWrapper<WardrobeItemEntity> queryWrapper, String sortType) {
        if (StringUtils.equals(sortType, "recentWear")) {
            queryWrapper.orderByDesc(WardrobeItemEntity::getLastWearDate).orderByDesc(WardrobeItemEntity::getId);
        } else if (StringUtils.equals(sortType, "leastWear")) {
            queryWrapper.orderByAsc(WardrobeItemEntity::getWearCount).orderByDesc(WardrobeItemEntity::getId);
        } else if (StringUtils.equals(sortType, "mostWear")) {
            queryWrapper.orderByDesc(WardrobeItemEntity::getWearCount).orderByDesc(WardrobeItemEntity::getId);
        } else if (StringUtils.equals(sortType, "priceDesc")) {
            queryWrapper.orderByDesc(WardrobeItemEntity::getPrice).orderByDesc(WardrobeItemEntity::getId);
        } else if (StringUtils.equals(sortType, "priceAsc")) {
            queryWrapper.orderByAsc(WardrobeItemEntity::getPrice).orderByDesc(WardrobeItemEntity::getId);
        } else if (StringUtils.equals(sortType, "purchaseDate")) {
            queryWrapper.orderByDesc(WardrobeItemEntity::getPurchaseDate).orderByDesc(WardrobeItemEntity::getId);
        } else if (StringUtils.equals(sortType, "idleDays")) {
            queryWrapper.orderByAsc(WardrobeItemEntity::getLastWearDate).orderByDesc(WardrobeItemEntity::getId);
        } else {
            queryWrapper.orderByDesc(WardrobeItemEntity::getId);
        }
    }

    private List<String> collectValues(List<WardrobeItemEntity> itemList, java.util.function.Function<WardrobeItemEntity, String> mapper) {
        Set<String> values = itemList.stream()
                .map(mapper)
                .filter(StringUtils::isNotBlank)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        return values.stream()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private List<String> collectTags(List<WardrobeItemEntity> itemList) {
        List<String> tags = new ArrayList<>();
        itemList.stream()
                .map(WardrobeItemEntity::getCustomTags)
                .filter(StringUtils::isNotBlank)
                .forEach(value -> {
                    for (String tag : value.split(",")) {
                        if (StringUtils.isNotBlank(tag)) {
                            tags.add(tag.trim());
                        }
                    }
                });
        Set<String> values = tags.stream()
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        return values.stream()
                .sorted(Comparator.naturalOrder())
                .toList();
    }
}
