package com.itwray.iw.auth.controller;

import com.itwray.iw.auth.service.impl.AccountCategoryService;
import com.itwray.iw.web.utils.UserUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/application/account/categories")
public class AccountCategoryController {
    private final AccountCategoryService service;
    public AccountCategoryController(AccountCategoryService service) { this.service=service; }
    public record Create(@NotNull String name,@NotNull String scopeVersion) { }
    public record Rename(@NotNull String name,@NotNull Integer revision,@NotNull String scopeVersion) { }
    public record Status(@NotNull Boolean enabled,@NotNull Integer revision,@NotNull String scopeVersion) { }
    public record Order(@NotNull List<@NotNull Integer> ids,@NotNull String scopeVersion) { }
    @GetMapping public AccountCategoryService.Management items() { return service.items(UserUtils.getUserId()); }
    @PostMapping public AccountCategoryService.Mutation create(@Valid @RequestBody Create dto) {
        return service.create(UserUtils.getUserId(),dto.name(),dto.scopeVersion());
    }
    @PutMapping("/{id}/name") public void rename(@PathVariable int id,@Valid @RequestBody Rename dto) {
        service.rename(UserUtils.getUserId(),id,dto.name(),dto.revision(),dto.scopeVersion());
    }
    @PutMapping("/{id}/status") public void status(@PathVariable int id,@Valid @RequestBody Status dto) {
        service.status(UserUtils.getUserId(),id,dto.enabled(),dto.revision(),dto.scopeVersion());
    }
    @DeleteMapping("/{id}") public void delete(@PathVariable int id,@RequestParam int revision,@RequestParam String scopeVersion) {
        service.delete(UserUtils.getUserId(),id,revision,scopeVersion);
    }
    @PutMapping("/order") public void order(@Valid @RequestBody Order dto) { service.order(UserUtils.getUserId(),dto.ids(),dto.scopeVersion()); }
}
