package com.itwray.iw.external.zhaogang.statistics;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.zhaogang.ZhaogangSessionManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

import static com.itwray.iw.external.zhaogang.statistics.StatisticsModels.*;

@RestController
@RequestMapping("/external-service/api/zhaogang/statistics")
@Validated
public class StatisticsController {
    private final StatisticsService service;
    private final ZhaogangSessionManager sessions;

    StatisticsController(StatisticsService service, ZhaogangSessionManager sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping("/report")
    public GeneralResponse<Report> report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) RangeKind rangeKind,
            @RequestParam(defaultValue = "UTILIZATION_RATE") Metric worklogMetric,
            @RequestParam(defaultValue = "COMMIT_COUNT") Metric codeMetric,
            @RequestParam(defaultValue = "UTILIZATION_RATE") Metric sortMetric,
            @RequestParam(defaultValue = "DESC") SortOrder sortOrder,
            @RequestParam(required = false) @Size(max = 80) String keyword,
            @RequestParam(required = false) @Size(max = 16) String role,
            @RequestParam(defaultValue = "1") @Min(1) int pageNumber,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int pageSize,
            @RequestParam(defaultValue = "true") boolean compare,
            @RequestParam(required = false) @Size(max = 36) String snapshotId,
            @RequestParam(defaultValue = "false") boolean refresh,
            HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        return GeneralResponse.success(service.report(sessions.resolve(request, response),
                new StatisticsService.Query(from, to, rangeKind, worklogMetric, codeMetric, sortMetric, sortOrder,
                        keyword, role, pageNumber, pageSize, compare, snapshotId, refresh)));
    }
}
