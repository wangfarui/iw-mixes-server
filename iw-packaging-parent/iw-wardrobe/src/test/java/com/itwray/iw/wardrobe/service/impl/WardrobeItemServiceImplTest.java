package com.itwray.iw.wardrobe.service.impl;

import com.itwray.iw.wardrobe.dao.WardrobeItemDao;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemAddDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemUpdateDto;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import com.itwray.iw.wardrobe.service.WardrobeImageOptimizationTaskService;
import com.itwray.iw.wardrobe.service.WardrobeItemAccessService;
import com.itwray.iw.wardrobe.service.WardrobeItemImageService;
import com.itwray.iw.web.utils.UserUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;

class WardrobeItemServiceImplTest {

    @AfterEach
    void clearUserContext() {
        UserUtils.clearContext();
    }

    @Test
    void addPersistsTheOwnerResolvedByTheAccessPolicy() {
        UserUtils.setUserId(12);
        WardrobeItemDao itemDao = mock(WardrobeItemDao.class);
        WardrobeItemAccessService accessService = mock(WardrobeItemAccessService.class);
        when(accessService.resolveOwnerForSave(13)).thenReturn(13);
        WardrobeItemServiceImpl service = new WardrobeItemServiceImpl(
                itemDao,
                mock(WardrobeItemImageService.class),
                mock(WardrobeImageOptimizationTaskService.class),
                accessService
        );
        WardrobeItemAddDto request = new WardrobeItemAddDto();
        request.setItemName("蓝色外套");
        request.setOwnerUserId(13);

        service.add(request);

        ArgumentCaptor<WardrobeItemEntity> captor = ArgumentCaptor.forClass(WardrobeItemEntity.class);
        verify(itemDao).save(captor.capture());
        assertEquals(13, captor.getValue().getUserId());
    }

    @Test
    void ownerTransferUsesThePreviousOwnerAsTheWriteCondition() {
        UserUtils.setUserId(12);
        WardrobeItemDao itemDao = mock(WardrobeItemDao.class);
        WardrobeItemAccessService accessService = mock(WardrobeItemAccessService.class);
        WardrobeImageOptimizationTaskService taskService = mock(WardrobeImageOptimizationTaskService.class);
        WardrobeItemImageService imageService = mock(WardrobeItemImageService.class);
        WardrobeItemEntity current = new WardrobeItemEntity();
        current.setId(7);
        current.setUserId(13);
        current.setItemName("蓝色外套");
        current.setItemImage("");
        when(accessService.resolveFamilyOwnerIds()).thenReturn(java.util.List.of(12, 13, 14));
        when(accessService.resolveOwnerForSave(14)).thenReturn(14);
        when(itemDao.queryByIdInOwnerIds(7, java.util.List.of(12, 13, 14))).thenReturn(current);
        when(itemDao.updateByIdAndOwner(any(WardrobeItemEntity.class), any(Integer.class))).thenReturn(true);
        WardrobeItemServiceImpl service = new WardrobeItemServiceImpl(
                itemDao, imageService, taskService, accessService);
        WardrobeItemUpdateDto request = new WardrobeItemUpdateDto();
        request.setId(7);
        request.setOwnerUserId(14);
        request.setItemName("蓝色外套");
        request.setItemImage("");

        service.update(request);

        verify(taskService).assertOwnerChangeAllowed(7, 13);
        verify(itemDao).updateByIdAndOwner(any(WardrobeItemEntity.class), org.mockito.ArgumentMatchers.eq(13));
        verify(imageService).transferOwnership(7, 13, 14);
        verify(taskService).transferOwnership(7, 13, 14);
    }
}
