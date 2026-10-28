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
@RequestMapping("/life/important-date")
public class ImportantDateController {
    private final LifeToolsService service;
    private final LifeDates dates;
    public ImportantDateController(LifeToolsService service,LifeDates dates){this.service=service;this.dates=dates;}
    @PostMapping("/page") public Map<String,Object> page(@Valid @RequestBody LifeRequests.Page input){return service.page(LifeStore.Kind.DATE,input);}
    @GetMapping("/detail") public Map<String,Object> detail(@RequestParam @Min(1) int id){return service.detail(LifeStore.Kind.DATE,id);}
    @PostMapping("/add") public Map<String,Object> add(@Valid @RequestBody LifeRequests.ImportantDate input){return service.add(LifeStore.Kind.DATE,input);}
    @PutMapping("/update") public Map<String,Object> update(@Valid @RequestBody LifeRequests.ImportantDate input){return service.update(LifeStore.Kind.DATE,input);}
    @DeleteMapping("/delete") public void delete(@RequestParam @Min(1) int id,@RequestParam @Min(1) int revision){service.delete(LifeStore.Kind.DATE,id,revision);}
    @GetMapping("/reminder-summary") public Map<String,Object> reminders(){return service.reminders();}
    @GetMapping("/calendar") public List<LifeDates.Month> calendar(@RequestParam @Min(1901) @Max(2099) int year){return dates.months(year);}
}
