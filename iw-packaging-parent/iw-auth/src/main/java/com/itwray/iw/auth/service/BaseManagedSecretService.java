package com.itwray.iw.auth.service;

import com.itwray.iw.auth.model.dto.ManagedSecretAddDto;
import com.itwray.iw.auth.model.dto.ManagedSecretPageDto;
import com.itwray.iw.auth.model.dto.ManagedSecretRevealDto;
import com.itwray.iw.auth.model.dto.ManagedSecretUpdateDto;
import com.itwray.iw.auth.model.vo.ManagedSecretDetailVo;
import com.itwray.iw.auth.model.vo.ManagedSecretPageVo;
import com.itwray.iw.auth.model.vo.ManagedSecretRevealVo;
import com.itwray.iw.web.model.vo.PageVo;
import com.itwray.iw.web.service.WebService;

public interface BaseManagedSecretService extends WebService<ManagedSecretAddDto, ManagedSecretUpdateDto, ManagedSecretDetailVo, Integer> {

    PageVo<ManagedSecretPageVo> page(ManagedSecretPageDto dto);

    ManagedSecretRevealVo reveal(ManagedSecretRevealDto dto);
}
