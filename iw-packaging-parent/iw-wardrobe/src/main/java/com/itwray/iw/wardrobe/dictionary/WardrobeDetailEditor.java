package com.itwray.iw.wardrobe.dictionary;

import com.itwray.iw.wardrobe.mapper.WardrobeDetailMapper;

import cn.hutool.core.bean.BeanUtil;
import com.itwray.iw.wardrobe.dao.WardrobeItemDao;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import com.itwray.iw.wardrobe.model.entity.WardrobeOutfitItemEntity;
import com.itwray.iw.wardrobe.model.enums.WardrobeItemStatusEnum;
import com.itwray.iw.wardrobe.model.vo.WardrobeOutfitItemVo;
import com.itwray.iw.wardrobe.service.WardrobeItemAccessService;
import com.itwray.iw.web.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static com.itwray.iw.wardrobe.mapper.WardrobeDetailMapper.Kind;

/** Head rows are authorized by the caller. Preserved rows never reread current item classifications. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class WardrobeDetailEditor {
    public record Selection(Integer detailId, Integer itemId) { }
    private final WardrobeDetailMapper mapper;
    private final WardrobeItemDao items;
    private final WardrobeItemAccessService access;
    public WardrobeDetailEditor(WardrobeDetailMapper mapper, WardrobeItemDao items, WardrobeItemAccessService access) {
        this.mapper = mapper; this.items = items; this.access = access;
    }
    public int replace(Kind kind, Integer head, Integer owner, List<Selection> selections, String expectedVersion) {
        List<WardrobeOutfitItemEntity> current = mapper.lockRows(kind, head, owner);
        if (selections == null) return current.size();
        if (selections.isEmpty() || selections.size() > 100 || !versionRows(current).equals(expectedVersion)) throw error("明细已变化或为空，请刷新后核对");
        Map<Integer,WardrobeOutfitItemEntity> byId = new HashMap<>(); current.forEach(r -> byId.put(r.getId(), r));
        Set<Integer> kept = new HashSet<>(), usedItems = new HashSet<>(); List<WardrobeOutfitItemEntity> result = new ArrayList<>();
        for (Selection selection : selections) {
            if (selection == null || (selection.detailId == null) == (selection.itemId == null)) throw error("请选择保留明细或新衣物");
            WardrobeOutfitItemEntity row;
            if (selection.detailId != null) {
                row = byId.get(selection.detailId);
                if (row == null || !kept.add(row.getId())) throw error("明细不属于此记录或重复");
            } else {
                WardrobeItemEntity item = selected(selection.itemId, owner, kind == Kind.OUTFIT);
                row = BeanUtil.copyProperties(item, WardrobeOutfitItemEntity.class); row.setId(null); row.setItemId(item.getId());
            }
            if (!usedItems.add(row.getItemId())) throw error("不能重复选择同一衣物"); result.add(row);
        }
        for (WardrobeOutfitItemEntity old : current) if (!kept.contains(old.getId())) mapper.remove(kind, head, owner, old.getId());
        for (int i = 0; i < result.size(); i++) {
            var row = result.get(i);
            if (row.getId() == null) mapper.insert(kind, head, owner, row, i + 1); else mapper.sort(kind, head, owner, row.getId(), i + 1);
        }
        return result.size();
    }
    public int copy(Kind kind, Integer sourceHead, Integer targetHead, Integer owner) {
        List<WardrobeOutfitItemEntity> rows = mapper.lockRows(kind, sourceHead, owner);
        if (rows.isEmpty()) throw error("原记录没有衣物明细");
        for (int i = 0; i < rows.size(); i++) {
            selected(rows.get(i).getItemId(), owner, kind == Kind.OUTFIT);
            mapper.insert(kind, targetHead, owner, rows.get(i), i + 1);
        }
        return rows.size();
    }
    private WardrobeItemEntity selected(Integer id, Integer owner, boolean family) {
        List<Integer> owners = family ? access.resolveVisibleOwnerIds(false) : List.of(owner);
        WardrobeItemEntity item = items.lambdaQuery().eq(WardrobeItemEntity::getId, id)
                .in(WardrobeItemEntity::getUserId, owners).in(WardrobeItemEntity::getStatus, WardrobeItemStatusEnum.availableCodes()).one();
        if (item == null) throw error("衣物已删除、转交或不可用，请刷新核对");
        if (!Objects.equals(item.getDictionarySnapshotStatus(), 1)) throw error("衣物名称快照未就绪"); return item;
    }
    public static String version(List<WardrobeOutfitItemVo> rows) {
        return hash(rows.stream().map(r -> r.getDetailId() + ":" + r.getSort()).toList());
    }
    private static String versionRows(List<WardrobeOutfitItemEntity> rows) { return hash(rows.stream().map(r -> r.getId() + ":" + r.getSort()).toList()); }
    private static String hash(List<String> rows) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join(";", rows).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private BusinessException error(String message) { return new BusinessException(message); }
}
