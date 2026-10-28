package com.itwray.iw.life;

import jakarta.validation.Valid;
import java.util.Map;
import java.util.List;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/life/bookmark")
public class BookmarkController {
    private final LifeToolsService service;
    public BookmarkController(LifeToolsService service){this.service=service;}
    @PostMapping("/page") public Map<String,Object> page(@Valid @RequestBody LifeRequests.Page input){return service.page(LifeStore.Kind.BOOKMARK,input);}
    @GetMapping("/detail") public Map<String,Object> detail(@RequestParam @Min(1) int id){return service.detail(LifeStore.Kind.BOOKMARK,id);}
    @PostMapping("/add") public Map<String,Object> add(@Valid @RequestBody LifeRequests.Bookmark input){return service.add(LifeStore.Kind.BOOKMARK,input);}
    @PutMapping("/update") public Map<String,Object> update(@Valid @RequestBody LifeRequests.Bookmark input){return service.update(LifeStore.Kind.BOOKMARK,input);}
    @DeleteMapping("/delete") public void delete(@RequestParam @Min(1) int id,@RequestParam @Min(1) int revision){service.delete(LifeStore.Kind.BOOKMARK,id,revision);}
    @PutMapping("/set-pinned") public Map<String,Object> setPinned(@Valid @RequestBody LifeRequests.Pin input){return service.pin(input);}
}
