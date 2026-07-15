package com.itwray.iw.auth.controller;

import com.itwray.iw.auth.model.dto.ManagedSecretAddDto;
import com.itwray.iw.auth.model.dto.ManagedSecretPageDto;
import com.itwray.iw.auth.model.dto.ManagedSecretRevealDto;
import com.itwray.iw.auth.model.dto.ManagedSecretUpdateDto;
import com.itwray.iw.auth.model.vo.ManagedSecretDetailVo;
import com.itwray.iw.auth.model.vo.ManagedSecretPageVo;
import com.itwray.iw.auth.model.vo.ManagedSecretRevealVo;
import com.itwray.iw.auth.service.BaseManagedSecretService;
import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.web.controller.WebController;
import com.itwray.iw.web.model.vo.PageVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/managed/secret")
@Validated
@Tag(name = "密钥管理接口")
public class BaseManagedSecretController extends WebController<BaseManagedSecretService,
        ManagedSecretAddDto, ManagedSecretUpdateDto, ManagedSecretDetailVo, Integer> {

    @Autowired
    public BaseManagedSecretController(BaseManagedSecretService webService) {
        super(webService);
    }

    @PostMapping("/page")
    @Operation(summary = "分页查询密钥")
    public PageVo<ManagedSecretPageVo> page(@RequestBody @Valid ManagedSecretPageDto dto) {
        return getWebService().page(dto);
    }

    @PostMapping("/reveal")
    @Operation(summary = "按字段查询密钥明文")
    public GeneralResponse<ManagedSecretRevealVo> reveal(@RequestBody @Valid ManagedSecretRevealDto dto,
                                                           HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        return GeneralResponse.success(getWebService().reveal(dto));
    }
}
