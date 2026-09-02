package com.itwray.iw.external.zhaogang;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.zhaogang.release.ReleaseReceiptModule;
import com.itwray.iw.external.zhaogang.release.ReleaseReceiptModels.Context;
import com.itwray.iw.external.zhaogang.release.ReleaseReceiptModels.Receipt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/external-service/api/zhaogang/release-notes")
@Validated
@Tag(name = "找钢工作台版本通知")
public class ZhaogangReleaseReceiptController {

    private final ZhaogangSessionManager sessionManager;
    private final ReleaseReceiptModule module;

    public ZhaogangReleaseReceiptController(ZhaogangSessionManager sessionManager, ReleaseReceiptModule module) {
        this.sessionManager = sessionManager;
        this.module = module;
    }

    @GetMapping("/{releaseId}/receipt")
    @Operation(summary = "查询找钢工作台版本已读状态")
    public GeneralResponse<Receipt> receipt(@PathVariable String releaseId,
                                             HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        return GeneralResponse.success(module.receipt(context(sessionManager.resolve(request, response)), releaseId));
    }

    @PutMapping("/{releaseId}/receipt")
    @Operation(summary = "确认找钢工作台版本已读")
    public GeneralResponse<Receipt> acknowledge(@PathVariable String releaseId,
                                                 HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        return GeneralResponse.success(module.acknowledge(context(sessionManager.resolve(request, response)), releaseId));
    }

    private void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }

    private Context context(ZhaogangSession session) {
        return new Context(session.teamId() == null ? 0 : session.teamId(), session.userId() == null ? 0 : session.userId());
    }
}
