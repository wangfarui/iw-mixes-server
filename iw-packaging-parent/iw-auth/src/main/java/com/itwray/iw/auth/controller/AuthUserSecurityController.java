package com.itwray.iw.auth.controller;

import com.itwray.iw.auth.model.dto.*;
import com.itwray.iw.auth.model.vo.UserInfoVo;
import com.itwray.iw.auth.model.vo.UserSecurityTicketVo;
import com.itwray.iw.auth.service.AuthUserSecurityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/security")
@AllArgsConstructor
@Validated
@Tag(name = "用户账号安全接口")
public class AuthUserSecurityController {

    private final AuthUserSecurityService authUserSecurityService;

    @PostMapping("/verificationCode")
    @Operation(summary = "发送账号敏感操作验证码")
    public void sendSecurityVerificationCode(@RequestBody @Valid UserSecurityCodeSendDto dto) {
        authUserSecurityService.sendSecurityVerificationCode(dto);
    }

    @PostMapping("/verify")
    @Operation(summary = "验证账号敏感操作身份")
    public UserSecurityTicketVo verifySecurityIdentity(@RequestBody @Valid UserSecurityVerifyDto dto) {
        return authUserSecurityService.verifySecurityIdentity(dto);
    }

    @PostMapping("/contact/verificationCode")
    @Operation(summary = "发送新联系方式验证码")
    public void sendContactVerificationCode(@RequestBody @Valid UserContactCodeSendDto dto) {
        authUserSecurityService.sendContactVerificationCode(dto);
    }

    @PutMapping("/contact")
    @Operation(summary = "绑定或更换联系方式")
    public UserInfoVo updateContact(@RequestBody @Valid UserContactUpdateDto dto) {
        return authUserSecurityService.updateContact(dto);
    }

    @PostMapping("/contact/unbind")
    @Operation(summary = "解绑联系方式")
    public UserInfoVo unbindContact(@RequestBody @Valid UserContactUnbindDto dto) {
        return authUserSecurityService.unbindContact(dto);
    }

    @PutMapping("/password")
    @Operation(summary = "安全验证后设置或修改密码")
    public UserInfoVo editPassword(@RequestBody @Valid UserPasswordSecurityEditDto dto) {
        return authUserSecurityService.editPassword(dto);
    }
}
