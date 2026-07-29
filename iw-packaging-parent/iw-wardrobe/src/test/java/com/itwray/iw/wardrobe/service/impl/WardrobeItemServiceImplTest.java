package com.itwray.iw.wardrobe.service.impl;

import com.itwray.iw.wardrobe.dao.WardrobeItemDao;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemAddDto;
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
}
