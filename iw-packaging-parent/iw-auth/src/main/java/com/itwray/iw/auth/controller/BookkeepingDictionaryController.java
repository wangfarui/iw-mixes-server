package com.itwray.iw.auth.controller;

import com.itwray.iw.auth.service.impl.BookkeepingDictionaryService;
import com.itwray.iw.auth.service.impl.BookkeepingDictionaryService.*;
import com.itwray.iw.web.utils.UserUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/bookkeeping/dictionaries")
public class BookkeepingDictionaryController {
    private final BookkeepingDictionaryService service;
    public BookkeepingDictionaryController(BookkeepingDictionaryService service) { this.service=service; }
    public record Create(@NotNull Type type,@NotNull Scope scope,@NotNull String name) { }
    public record Name(@NotNull String name,@NotNull Integer revision) { }
    public record Status(@NotNull Boolean enabled,@NotNull Integer revision) { }
    public record Order(@NotNull Type type,@NotNull Scope scope,@NotNull List<Integer> ids,@NotNull String scopeVersion) { }
    public record Copy(@NotNull List<Integer> sourceIds) { }
    @GetMapping("/items")
    public Management items(@RequestParam Type type,@RequestParam Scope scope) { return service.items(UserUtils.getUserId(),type,scope); }
    @PostMapping("/items")
    public Item create(@Valid @RequestBody Create dto) { return service.create(UserUtils.getUserId(),dto.type,dto.scope,dto.name); }
    @PutMapping("/items/{id}/name")
    public Rename rename(@PathVariable int id,@Valid @RequestBody Name dto) { return service.rename(UserUtils.getUserId(),id,dto.name,dto.revision); }
    @PutMapping("/items/{id}/status")
    public Item status(@PathVariable int id,@Valid @RequestBody Status dto) { return service.status(UserUtils.getUserId(),id,dto.enabled,dto.revision); }
    @DeleteMapping("/items/{id}")
    public void delete(@PathVariable int id,@RequestParam Integer revision) { service.delete(UserUtils.getUserId(),id,revision); }
    @PutMapping("/order")
    public void order(@Valid @RequestBody Order dto) { service.order(UserUtils.getUserId(),dto.type,dto.scope,dto.ids,dto.scopeVersion); }
    @PostMapping("/copy-personal")
    public Copied copy(@Valid @RequestBody Copy dto) { return service.copyPersonal(UserUtils.getUserId(),dto.sourceIds); }
    @GetMapping("/options")
    public List<Option> options(@RequestParam Type type) { return service.options(UserUtils.getUserId(),type.code).stream()
            .map(row->new Option(row.getId(),row.getDictName(),row.getSort())).toList(); }
}
