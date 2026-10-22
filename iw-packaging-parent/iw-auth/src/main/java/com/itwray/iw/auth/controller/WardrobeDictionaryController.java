package com.itwray.iw.auth.controller;

import com.itwray.iw.auth.service.impl.WardrobeDictionaryService;
import com.itwray.iw.auth.service.impl.WardrobeDictionaryService.*;
import com.itwray.iw.web.utils.UserUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/wardrobe/dictionaries")
public class WardrobeDictionaryController {
    private final WardrobeDictionaryService service;
    public WardrobeDictionaryController(WardrobeDictionaryService service) { this.service = service; }
    public record Create(@NotNull Type type, @NotNull Scope scope, @NotNull String name,
                         Integer parentId, Integer parentRevision, Integer matchKind,
                         @NotNull String scopeVersion, Integer expectedFamilyGroupId) { }
    public record Name(@NotNull String name, @NotNull Integer revision, @NotNull String scopeVersion, Integer expectedFamilyGroupId) { }
    public record Status(@NotNull Boolean enabled, @NotNull Integer revision, @NotNull String scopeVersion, Integer expectedFamilyGroupId) { }
    public record Order(@NotNull Type type, @NotNull Scope scope, Integer categoryLineageId, @NotNull List<Integer> ids,
                        @NotNull String scopeVersion, Integer expectedFamilyGroupId) { }
    public record Copy(@NotNull List<Integer> sourceIds, @NotNull String personalScopeVersion,
                       @NotNull String familyScopeVersion, @NotNull Integer expectedFamilyGroupId) { }
    public record Initialize(@NotNull Scope scope, Integer expectedFamilyGroupId) { }
    @GetMapping("/items")
    public Management items(@RequestParam Type type, @RequestParam Scope scope, @RequestParam(required = false) Integer categoryLineageId) {
        return service.items(UserUtils.getUserId(), type, scope, categoryLineageId);
    }
    @PostMapping("/items")
    public Mutation create(@Valid @RequestBody Create dto) {
        return service.create(UserUtils.getUserId(), dto.type, dto.scope, dto.name, dto.parentId, dto.parentRevision,
                dto.matchKind, dto.scopeVersion, dto.expectedFamilyGroupId);
    }
    @PutMapping("/items/{id}/name")
    public Rename rename(@PathVariable int id, @Valid @RequestBody Name dto) {
        return service.rename(UserUtils.getUserId(), id, dto.name, dto.revision, dto.scopeVersion, dto.expectedFamilyGroupId);
    }
    @PutMapping("/items/{id}/status")
    public Mutation status(@PathVariable int id, @Valid @RequestBody Status dto) {
        return service.status(UserUtils.getUserId(), id, dto.enabled, dto.revision, dto.scopeVersion, dto.expectedFamilyGroupId);
    }
    @DeleteMapping("/items/{id}")
    public Map<String,String> delete(@PathVariable int id, @RequestParam Integer revision, @RequestParam String scopeVersion,
                                     @RequestParam(required = false) Integer expectedFamilyGroupId) {
        return Map.of("scopeVersion", service.delete(UserUtils.getUserId(), id, revision, scopeVersion, expectedFamilyGroupId));
    }
    @PutMapping("/order")
    public Map<String,String> order(@Valid @RequestBody Order dto) {
        return Map.of("scopeVersion", service.order(UserUtils.getUserId(), dto.type, dto.scope, dto.categoryLineageId,
                dto.ids, dto.scopeVersion, dto.expectedFamilyGroupId));
    }
    @PostMapping("/copy-personal")
    public Copied copy(@Valid @RequestBody Copy dto) {
        return service.copyPersonal(UserUtils.getUserId(), dto.sourceIds, dto.personalScopeVersion, dto.familyScopeVersion, dto.expectedFamilyGroupId);
    }
    @PostMapping("/initialize")
    public Map<String,String> initialize(@Valid @RequestBody Initialize dto) {
        return Map.of("scopeVersion", service.initializeManaged(UserUtils.getUserId(), dto.scope, dto.expectedFamilyGroupId));
    }
}
