package com.itwray.iw.wardrobe.service.impl;

import com.itwray.iw.wardrobe.dao.WardrobeOutfitDao;
import com.itwray.iw.wardrobe.dao.WardrobeOutfitItemDao;
import com.itwray.iw.wardrobe.dao.WardrobeWearRecordDao;
import com.itwray.iw.wardrobe.dao.WardrobeWearRecordItemDao;
import com.itwray.iw.wardrobe.model.dto.WardrobeMarkWornDto;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import com.itwray.iw.wardrobe.model.entity.WardrobeOutfitEntity;
import com.itwray.iw.wardrobe.model.entity.WardrobeOutfitItemEntity;
import com.itwray.iw.wardrobe.model.vo.WardrobeMarkWornVo;
import com.itwray.iw.wardrobe.service.WardrobeItemService;
import com.itwray.iw.wardrobe.service.WardrobeOutfitService;
import com.itwray.iw.web.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WardrobeWearRecordServiceImplTest {

    @Test
    void deletedHistoricalItemReturnsStructuredConfirmationWithoutSaving() {
        WardrobeWearRecordDao recordDao = mock(WardrobeWearRecordDao.class);
        WardrobeWearRecordItemDao recordItemDao = mock(WardrobeWearRecordItemDao.class);
        WardrobeOutfitDao outfitDao = mock(WardrobeOutfitDao.class);
        WardrobeOutfitItemDao outfitItemDao = mock(WardrobeOutfitItemDao.class);
        WardrobeItemService itemService = mock(WardrobeItemService.class);
        WardrobeOutfitService outfitService = mock(WardrobeOutfitService.class);
        WardrobeOutfitEntity outfit = new WardrobeOutfitEntity();
        outfit.setId(9);
        WardrobeOutfitItemEntity snapshot = new WardrobeOutfitItemEntity();
        snapshot.setOutfitId(9);
        snapshot.setItemId(7);
        snapshot.setItemName("蓝色外套");
        when(outfitDao.queryById(9)).thenReturn(outfit);
        when(outfitItemDao.queryByOutfitId(9)).thenReturn(List.of(snapshot));
        when(itemService.queryActiveItemsByIds(List.of(7))).thenReturn(List.of());
        when(itemService.queryHistoricalActiveOwnerIds(List.of(7))).thenReturn(Map.of());
        WardrobeWearRecordServiceImpl service = new WardrobeWearRecordServiceImpl(
                recordDao, recordItemDao, outfitDao, outfitItemDao, itemService, outfitService);
        WardrobeMarkWornDto request = new WardrobeMarkWornDto();
        request.setOutfitId(9);

        WardrobeMarkWornVo result = service.markWorn(request);

        assertTrue(result.isConfirmationRequired());
        assertEquals("deleted", result.getUnavailableItems().get(0).getAvailability());
        assertEquals("蓝色外套", result.getUnavailableItems().get(0).getItemName());
        verify(recordDao, never()).save(any());
    }

    @Test
    void transferredHistoricalItemReturnsStructuredConfirmationWithoutSaving() {
        WardrobeWearRecordDao recordDao = mock(WardrobeWearRecordDao.class);
        WardrobeWearRecordItemDao recordItemDao = mock(WardrobeWearRecordItemDao.class);
        WardrobeOutfitDao outfitDao = mock(WardrobeOutfitDao.class);
        WardrobeOutfitItemDao outfitItemDao = mock(WardrobeOutfitItemDao.class);
        WardrobeItemService itemService = mock(WardrobeItemService.class);
        WardrobeOutfitService outfitService = mock(WardrobeOutfitService.class);
        WardrobeOutfitEntity outfit = new WardrobeOutfitEntity();
        outfit.setId(9);
        WardrobeOutfitItemEntity snapshot = new WardrobeOutfitItemEntity();
        snapshot.setOutfitId(9);
        snapshot.setItemId(7);
        snapshot.setItemName("蓝色外套");
        when(outfitDao.queryById(9)).thenReturn(outfit);
        when(outfitItemDao.queryByOutfitId(9)).thenReturn(List.of(snapshot));
        when(itemService.queryActiveItemsByIds(List.of(7))).thenReturn(List.of());
        when(itemService.queryHistoricalActiveOwnerIds(List.of(7))).thenReturn(Map.of(7, 13));
        WardrobeWearRecordServiceImpl service = new WardrobeWearRecordServiceImpl(
                recordDao, recordItemDao, outfitDao, outfitItemDao, itemService, outfitService);
        WardrobeMarkWornDto request = new WardrobeMarkWornDto();
        request.setOutfitId(9);

        WardrobeMarkWornVo result = service.markWorn(request);

        assertTrue(result.isConfirmationRequired());
        assertEquals("transferred", result.getUnavailableItems().get(0).getAvailability());
        assertEquals("蓝色外套", result.getUnavailableItems().get(0).getItemName());
        verify(recordDao, never()).save(any());
    }

    @Test
    void confirmedPartialRecordOnlySavesItemsThatRemainAvailable() {
        WardrobeWearRecordDao recordDao = mock(WardrobeWearRecordDao.class);
        WardrobeWearRecordItemDao recordItemDao = mock(WardrobeWearRecordItemDao.class);
        WardrobeOutfitDao outfitDao = mock(WardrobeOutfitDao.class);
        WardrobeOutfitItemDao outfitItemDao = mock(WardrobeOutfitItemDao.class);
        WardrobeItemService itemService = mock(WardrobeItemService.class);
        WardrobeOutfitService outfitService = mock(WardrobeOutfitService.class);
        WardrobeOutfitEntity outfit = new WardrobeOutfitEntity();
        outfit.setId(9);
        outfit.setOutfitName("通勤搭配");
        WardrobeOutfitItemEntity deleted = snapshot(9, 7, "蓝色外套");
        WardrobeOutfitItemEntity available = snapshot(9, 8, "白色衬衫");
        WardrobeItemEntity activeItem = new WardrobeItemEntity();
        activeItem.setId(8);
        activeItem.setItemName("白色衬衫");
        when(outfitDao.queryById(9)).thenReturn(outfit);
        when(outfitItemDao.queryByOutfitId(9)).thenReturn(List.of(deleted, available));
        when(itemService.queryActiveItemsByIds(List.of(7, 8))).thenReturn(List.of(activeItem));
        when(itemService.queryHistoricalActiveOwnerIds(List.of(7, 8))).thenReturn(Map.of());
        WardrobeWearRecordServiceImpl service = new WardrobeWearRecordServiceImpl(
                recordDao, recordItemDao, outfitDao, outfitItemDao, itemService, outfitService);
        WardrobeMarkWornDto request = new WardrobeMarkWornDto();
        request.setOutfitId(9);
        request.setAllowPartial(true);

        WardrobeMarkWornVo result = service.markWorn(request);

        assertFalse(result.isConfirmationRequired());
        verify(recordDao).save(any());
        verify(recordItemDao).saveBatch(any());
        verify(itemService).increaseWearCount(any(), any());
    }

    @Test
    void confirmedPartialRecordIsBlockedWhenAllItemsBecomeUnavailable() {
        WardrobeWearRecordDao recordDao = mock(WardrobeWearRecordDao.class);
        WardrobeWearRecordItemDao recordItemDao = mock(WardrobeWearRecordItemDao.class);
        WardrobeOutfitDao outfitDao = mock(WardrobeOutfitDao.class);
        WardrobeOutfitItemDao outfitItemDao = mock(WardrobeOutfitItemDao.class);
        WardrobeItemService itemService = mock(WardrobeItemService.class);
        WardrobeOutfitService outfitService = mock(WardrobeOutfitService.class);
        WardrobeOutfitEntity outfit = new WardrobeOutfitEntity();
        outfit.setId(9);
        WardrobeOutfitItemEntity snapshot = snapshot(9, 7, "蓝色外套");
        when(outfitDao.queryById(9)).thenReturn(outfit);
        when(outfitItemDao.queryByOutfitId(9)).thenReturn(List.of(snapshot));
        when(itemService.queryActiveItemsByIds(List.of(7))).thenReturn(List.of());
        when(itemService.queryHistoricalActiveOwnerIds(List.of(7))).thenReturn(Map.of());
        WardrobeWearRecordServiceImpl service = new WardrobeWearRecordServiceImpl(
                recordDao, recordItemDao, outfitDao, outfitItemDao, itemService, outfitService);
        WardrobeMarkWornDto request = new WardrobeMarkWornDto();
        request.setOutfitId(9);

        assertTrue(service.markWorn(request).isConfirmationRequired());
        request.setAllowPartial(true);
        assertThrows(BusinessException.class, () -> service.markWorn(request));
        verify(recordDao, never()).save(any());
    }

    private static WardrobeOutfitItemEntity snapshot(Integer outfitId, Integer itemId, String itemName) {
        WardrobeOutfitItemEntity snapshot = new WardrobeOutfitItemEntity();
        snapshot.setOutfitId(outfitId);
        snapshot.setItemId(itemId);
        snapshot.setItemName(itemName);
        return snapshot;
    }
}
