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
@RequestMapping("/life/wish")
public class WishController {
    private final LifeToolsService service;
    public WishController(LifeToolsService service){this.service=service;}
    @PostMapping("/page") public Map<String,Object> page(@Valid @RequestBody LifeRequests.Page input){return service.page(LifeStore.Kind.WISH,input);}
    @GetMapping("/detail") public Map<String,Object> detail(@RequestParam @Min(1) int id){return service.detail(LifeStore.Kind.WISH,id);}
    @PostMapping("/add") public Map<String,Object> add(@Valid @RequestBody LifeRequests.Wish input){return service.add(LifeStore.Kind.WISH,input);}
    @PutMapping("/update") public Map<String,Object> update(@Valid @RequestBody LifeRequests.Wish input){return service.update(LifeStore.Kind.WISH,input);}
    @DeleteMapping("/delete") public void delete(@RequestParam @Min(1) int id,@RequestParam @Min(1) int revision){service.delete(LifeStore.Kind.WISH,id,revision);}
    @PutMapping("/change-status") public Map<String,Object> changeStatus(@Valid @RequestBody LifeRequests.State input){return service.changeStatus(input);}
}
