package com.itwray.iw.external.zhaogang;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Actor;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.BatchAddCommand;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.BatchAddResult;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.MatchCommand;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.Preview;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/external-service/api/zhaogang/iterations/{iterationId}/release-import")
public class ZhaogangReleaseImportController {

    private final ZhaogangSessionManager sessions;
    private final ZhaogangReleaseImportService service;
    private final ZhaogangProperties properties;

    public ZhaogangReleaseImportController(ZhaogangSessionManager sessions, ZhaogangReleaseImportService service,
                                           ZhaogangProperties properties) {
        this.sessions = sessions;
        this.service = service;
        this.properties = properties;
    }

    @PostMapping(value = "/recognize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public GeneralResponse<Preview> recognize(@PathVariable long iterationId, @RequestPart("file") MultipartFile file,
                                              @RequestParam(value = "projectColumnName", required = false) String projectColumnName,
                                              @RequestParam(value = "planColumnName", required = false) String planColumnName,
                                              HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (file == null || file.isEmpty() || file.getSize() > properties.getAiMaxImageBytes()) {
            throw new IllegalArgumentException("截图不能为空且不能超过 10 MB");
        }
        String type = file.getContentType();
        if (!"image/png".equalsIgnoreCase(type) && !"image/jpeg".equalsIgnoreCase(type)
                && !"image/webp".equalsIgnoreCase(type)) {
            throw new IllegalArgumentException("只支持 PNG、JPEG、WebP 截图");
        }
        return GeneralResponse.success(service.recognizeAndMatch(actor(request, response), iterationId,
                file.getBytes(), type, projectColumnName, planColumnName));
    }

    @PostMapping("/match")
    public GeneralResponse<Preview> match(@PathVariable long iterationId, @RequestBody MatchCommand command,
                                          HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(service.match(actor(request, response), iterationId, command));
    }

    @PostMapping("/batch-add")
    public GeneralResponse<BatchAddResult> add(@PathVariable long iterationId, @RequestBody BatchAddCommand command,
                                               HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(service.add(actor(request, response), iterationId, command));
    }

    private Actor actor(HttpServletRequest request, HttpServletResponse response) {
        ZhaogangSession session = sessions.resolve(request, response);
        return new Actor(session.userId(), session.userName(), session.avatar(), session.token(), session.team(),
                session.teamId() == null ? 0 : session.teamId(), properties.configuredTeamHost());
    }
}
