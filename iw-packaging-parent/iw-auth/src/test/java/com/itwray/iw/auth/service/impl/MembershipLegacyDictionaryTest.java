package com.itwray.iw.auth.service.impl;

import com.itwray.iw.auth.model.dto.DictAddDto;
import com.itwray.iw.auth.model.dto.DictUpdateDto;
import com.itwray.iw.web.dao.BaseDictDao;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import com.itwray.iw.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class MembershipLegacyDictionaryTest {
    @Test void oldReadCreateUpdateDeleteAndDetailCannotBypassMigration() {
        BaseDictDao dao=mock(BaseDictDao.class);
        BaseDictServiceImpl service=new BaseDictServiceImpl(dao);
        BaseDictEntity row=new BaseDictEntity();row.setId(4);row.setDictType(4004);
        when(dao.queryById(4)).thenReturn(row);
        DictAddDto add=new DictAddDto();add.setDictType(4004);
        DictUpdateDto update=new DictUpdateDto();update.setId(4);update.setDictType(5001);
        assertThrows(BusinessException.class,()->service.getDictList(4004));
        assertThrows(BusinessException.class,()->service.add(add));
        assertThrows(BusinessException.class,()->service.update(update));
        assertThrows(BusinessException.class,()->service.delete(4));
        assertThrows(BusinessException.class,()->service.detail(4));
        verify(dao,never()).save(any());verify(dao,never()).updateById(any());verify(dao,never()).removeById(anyInt());
    }
}
