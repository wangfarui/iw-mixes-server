package com.itwray.iw.eat.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.itwray.iw.eat.dao.EatFridgeFoodDao;
import com.itwray.iw.eat.dictionary.KitchenSnapshotService;
import com.itwray.iw.eat.mapper.EatFridgeFoodMapper;
import com.itwray.iw.eat.model.dto.EatFridgeFoodAddDto;
import com.itwray.iw.eat.model.dto.EatFridgeFoodPageDto;
import com.itwray.iw.eat.model.dto.EatFridgeFoodUpdateDto;
import com.itwray.iw.eat.model.entity.EatFridgeFoodEntity;
import com.itwray.iw.eat.model.vo.EatFridgeFoodDetailVo;
import com.itwray.iw.eat.model.vo.EatFridgeFoodPageVo;
import com.itwray.iw.eat.service.EatFridgeFoodService;
import com.itwray.iw.web.model.enums.SortTypeEnum;
import com.itwray.iw.web.model.enums.SortWayEnum;
import com.itwray.iw.web.model.vo.PageVo;
import com.itwray.iw.web.service.impl.WebServiceImpl;
import com.itwray.iw.web.utils.UserUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 冰箱食材表 服务实现类
 *
 * @author wray
 * @since 2026-01-20
 */
@Service
public class EatFridgeFoodServiceImpl extends WebServiceImpl<EatFridgeFoodDao, EatFridgeFoodMapper, EatFridgeFoodEntity,
        EatFridgeFoodAddDto, EatFridgeFoodUpdateDto, EatFridgeFoodDetailVo, Integer> implements EatFridgeFoodService {

    private final KitchenSnapshotService snapshots;

    @Autowired
    public EatFridgeFoodServiceImpl(EatFridgeFoodDao baseDao, KitchenSnapshotService snapshots) {
        super(baseDao);
        this.snapshots = snapshots;
    }

    @Override
    @Transactional
    public Integer add(EatFridgeFoodAddDto dto) {
        KitchenSnapshotService.rejectLegacyValues(dto, "category", "section");
        EatFridgeFoodEntity entity = BeanUtil.copyProperties(dto, EatFridgeFoodEntity.class);
        entity.setCategory(null);
        entity.setSection(null);
        entity.setCategorySourceId(null);
        entity.setCategoryNameSnapshot(null);
        entity.setSectionSourceId(null);
        entity.setSectionNameSnapshot(null);
        entity.setUserId(UserUtils.getUserId());
        JsonNodeSelection selection = selectionOf(dto);
        snapshots.applyCreate(entity, selection.selection(), selection.context());
        getBaseDao().save(entity);
        return entity.getId();
    }

    @Override
    @Transactional
    public void update(EatFridgeFoodUpdateDto dto) {
        KitchenSnapshotService.rejectLegacyValues(dto, "category", "section");
        EatFridgeFoodEntity current = getBaseDao().queryById(dto.getId());
        EatFridgeFoodEntity entity = BeanUtil.copyProperties(current, EatFridgeFoodEntity.class);
        entity.setName(dto.getName());
        entity.setEmoji(dto.getEmoji());
        entity.setQuantity(dto.getQuantity());
        entity.setAddDate(dto.getAddDate());
        entity.setExpireDate(dto.getExpireDate());
        JsonNodeSelection selection = selectionOf(dto);
        snapshots.applyUpdate(entity, current, selection.changes(), selection.context());
        getBaseDao().updateById(entity);
    }

    @Override
    public EatFridgeFoodDetailVo detail(Integer id) {
        EatFridgeFoodDetailVo vo = super.detail(id);
        attachSnapshot(vo);
        return vo;
    }

    @Override
    public PageVo<EatFridgeFoodPageVo> page(EatFridgeFoodPageDto dto) {
        LambdaQueryWrapper<EatFridgeFoodEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(StringUtils.isNotBlank(dto.getName()), EatFridgeFoodEntity::getName, dto.getName())
                .eq(StringUtils.isNotBlank(dto.getCategoryNameSnapshot()), EatFridgeFoodEntity::getCategoryNameSnapshot, dto.getCategoryNameSnapshot())
                .eq(StringUtils.isNotBlank(dto.getSectionNameSnapshot()), EatFridgeFoodEntity::getSectionNameSnapshot, dto.getSectionNameSnapshot())
                .isNull(Boolean.TRUE.equals(dto.getEmptyCategory()), EatFridgeFoodEntity::getCategoryNameSnapshot)
                .isNull(Boolean.TRUE.equals(dto.getEmptySection()), EatFridgeFoodEntity::getSectionNameSnapshot)
                .ge(Objects.nonNull(dto.getExpireStartDate()), EatFridgeFoodEntity::getExpireDate, dto.getExpireStartDate())
                .le(Objects.nonNull(dto.getExpireEndDate()), EatFridgeFoodEntity::getExpireDate, dto.getExpireEndDate())
                .orderByAsc(SortWayEnum.isAsc(dto.getSortWay()), SortTypeEnum.getDefaultSortField(dto.getSortType()))
                .orderByDesc(!SortWayEnum.isAsc(dto.getSortWay()), SortTypeEnum.getDefaultSortField(dto.getSortType()));
        // Legacy code filters are intentionally ignored after snapshot migration.
        return getBaseDao().page(dto, queryWrapper, EatFridgeFoodPageVo.class);
    }

    @Override
    public void markAsUsed(Integer id) {
        getBaseDao().removeById(id);
    }

    private void attachSnapshot(EatFridgeFoodDetailVo vo) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("category", source(vo.getCategorySourceId(), vo.getCategoryNameSnapshot()));
        snapshot.put("section", source(vo.getSectionSourceId(), vo.getSectionNameSnapshot()));
        vo.setDictionarySnapshot(snapshot);
    }

    private Map<String, Object> source(Integer sourceId, String nameSnapshot) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceId", sourceId);
        value.put("nameSnapshot", nameSnapshot);
        return value;
    }

    private JsonNodeSelection selectionOf(EatFridgeFoodAddDto dto) {
        return new JsonNodeSelection(dto.getDictionarySelection(), dto.getDictionaryChanges(), dto.getDictionaryContext());
    }

    private record JsonNodeSelection(com.fasterxml.jackson.databind.JsonNode selection,
                                     com.fasterxml.jackson.databind.JsonNode changes,
                                     Map<String, String> context) { }
}
