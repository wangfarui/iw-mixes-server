package com.itwray.iw.auth.controller;

import com.itwray.iw.auth.model.dto.RegisterInviteConfigUpdateDto;
import com.itwray.iw.auth.model.vo.RegisterInviteStatusVo;
import com.itwray.iw.auth.service.AuthRegisterInviteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 新用户注册邀请码控制层
 *
 * @author wray
 * @since 2026/7/2
 */
@RestController
@RequestMapping("/register/invite")
@AllArgsConstructor
@Validated
@Tag(name = "新用户注册邀请码接口")
public class AuthRegisterInviteController {

    private final AuthRegisterInviteService authRegisterInviteService;

    @GetMapping("/status")
    @Operation(summary = "查询新用户注册邀请码状态")
    public RegisterInviteStatusVo getStatus() {
        return authRegisterInviteService.getStatus();
    }

    @PutMapping("/config")
    @Operation(summary = "更新新用户注册邀请码配置")
    public void updateConfig(@RequestBody @Valid RegisterInviteConfigUpdateDto dto) {
        authRegisterInviteService.updateConfig(dto);
    }

    @PostMapping("/generate")
    @Operation(summary = "生成新用户注册邀请码")
    public RegisterInviteStatusVo generateInvite() {
        return authRegisterInviteService.generateInvite();
    }

    @DeleteMapping("/current")
    @Operation(summary = "删除当前新用户注册邀请码")
    public void deleteInvite() {
        authRegisterInviteService.deleteInvite();
    }
}
