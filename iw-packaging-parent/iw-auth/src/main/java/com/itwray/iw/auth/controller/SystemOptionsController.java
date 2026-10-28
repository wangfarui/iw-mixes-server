package com.itwray.iw.auth.controller;

import com.itwray.iw.auth.settings.SystemOptionsService;
import com.itwray.iw.web.utils.UserUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/business-settings")
public class SystemOptionsController {
    private final SystemOptionsService service;
    public SystemOptionsController(SystemOptionsService service) { this.service=service; }
    private int currentUser() {
        String token=UserUtils.getToken(false);
        if(token==null||token.isBlank()) throw new com.itwray.iw.web.exception.AuthorizedException("当前未登录，请先登录");
        Integer user=UserUtils.getUserId();
        if(user==null) throw new com.itwray.iw.web.exception.AuthorizedException("登录状态已失效");
        return user;
    }
    public record Sync(@NotBlank String scopeVersion) { }
    @GetMapping("/capabilities") public SystemOptionsService.Capabilities capabilities() { return service.capabilities(currentUser()); }
    @GetMapping("/{domain}/preview") public SystemOptionsService.Preview preview(@PathVariable String domain,@RequestParam(required=false) Integer type) {
        return service.preview(currentUser(),domain,type);
    }
    @PostMapping("/{domain}/sync") public SystemOptionsService.Result sync(@PathVariable String domain,@RequestParam(required=false) Integer type,@Valid @RequestBody Sync body) {
        return service.sync(currentUser(),domain,type,body.scopeVersion());
    }
    @PostMapping("/{domain}/refresh-cache") public boolean refresh(@PathVariable String domain) { return service.refresh(currentUser(),domain); }
    @GetMapping("/legacy-type") public Integer legacy(@RequestParam int id) { return service.legacyType(currentUser(),id); }
}
