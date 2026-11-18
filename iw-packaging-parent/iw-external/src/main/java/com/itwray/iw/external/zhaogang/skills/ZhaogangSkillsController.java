package com.itwray.iw.external.zhaogang.skills;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.zhaogang.ZhaogangSession;
import com.itwray.iw.external.zhaogang.ZhaogangSessionManager;
import com.itwray.iw.external.zhaogang.ZhaogangSessionException;
import com.itwray.iw.web.annotation.SkipWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/external-service/api/zhaogang/skills")
@SkipWrapper
public class ZhaogangSkillsController {
    private final SkillLibrary library;
    private final SkillsDevices devices;
    private final ZhaogangSessionManager sessions;
    private final SkillPackageStore packages;

    ZhaogangSkillsController(SkillLibrary library, SkillsDevices devices, ZhaogangSessionManager sessions, SkillPackageStore packages) {
        this.library = library;
        this.devices = devices;
        this.sessions = sessions;
        this.packages = packages;
    }

    record PairStart(String label) {}
    record PairCode(String code) {}
    record PairClaim(String secret) {}

    @GetMapping
    GeneralResponse<List<SkillLibrary.Entry>> list(@RequestParam(defaultValue = "") String q,
                                                    HttpServletRequest request, HttpServletResponse response) {
        var actor = actor(request, response);
        return GeneralResponse.success(library.list(actor, q.substring(0, Math.min(120, q.length()))));
    }

    @GetMapping("/{id}")
    GeneralResponse<SkillLibrary.Detail> detail(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(library.detail(actor(request, response), id));
    }

    @GetMapping("/{id}/compare")
    GeneralResponse<SkillLibrary.Difference> compare(@PathVariable String id, @RequestParam String from, @RequestParam String to,
                                                      HttpServletRequest request, HttpServletResponse response) {
        actor(request, response);
        return GeneralResponse.success(library.compare(id, from, to));
    }

    @GetMapping("/{id}/versions/{version}/download")
    ResponseEntity<byte[]> download(@PathVariable String id, @PathVariable String version,
                                    HttpServletRequest request, HttpServletResponse response) {
        actor(request, response);
        var file = library.download(id, version);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.fileName() + "\"")
                .header("X-Skill-SHA256", file.sha256()).body(file.bytes());
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    GeneralResponse<SkillLibrary.Preview> preview(@RequestParam MultipartFile file, @RequestParam(required = false) String skillId,
                                                   @RequestParam(required = false) Long revision, HttpServletRequest request,
                                                   HttpServletResponse response) throws IOException {
        var actor = actor(request, response);
        if (file.getSize() > SkillPackage.MAX_ZIP) throw SkillProblem.bad("ZIP 须小于或等于 8 MiB");
        return GeneralResponse.success(library.preview(actor, skillId, revision, file.getBytes()));
    }

    @PostMapping("/publish")
    GeneralResponse<SkillLibrary.Published> publish(@RequestBody SkillLibrary.Publish command,
                                                      HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(library.publish(actor(request, response), command));
    }

    @PostMapping("/{id}/versions/{version}/rollback")
    GeneralResponse<Void> rollback(@PathVariable String id, @PathVariable String version, @RequestBody SkillLibrary.Mutation command,
                                   HttpServletRequest request, HttpServletResponse response) {
        library.rollback(browser(request, response), id, version, command);
        return GeneralResponse.success();
    }

    @DeleteMapping("/{id}/versions/{version}")
    GeneralResponse<Void> deleteVersion(@PathVariable String id, @PathVariable String version, @RequestBody SkillLibrary.Mutation command,
                                        HttpServletRequest request, HttpServletResponse response) {
        library.deleteVersion(browser(request, response), id, version, command);
        return GeneralResponse.success();
    }

    @DeleteMapping("/{id}")
    GeneralResponse<Void> deleteSkill(@PathVariable String id, @RequestBody SkillLibrary.Mutation command,
                                      HttpServletRequest request, HttpServletResponse response) {
        library.deleteSkill(browser(request, response), id, command);
        return GeneralResponse.success();
    }

    @PostMapping("/devices/pair/start")
    GeneralResponse<SkillsDevices.Pairing> start(@RequestBody PairStart command, HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        return GeneralResponse.success(devices.start(command.label(), request.getRemoteAddr()));
    }

    @PostMapping("/devices/pair/claim")
    GeneralResponse<SkillsDevices.Approved> claim(@RequestBody PairClaim command, HttpServletResponse response) {
        noStore(response);
        return GeneralResponse.success(devices.claim(command.secret()));
    }

    @PostMapping("/devices/pair/preview")
    GeneralResponse<SkillsDevices.Device> candidate(@RequestBody PairCode command,
                                                     HttpServletRequest request, HttpServletResponse response) {
        browser(request, response);
        return GeneralResponse.success(devices.candidate(command.code()));
    }

    @PostMapping("/devices/pair/approve")
    GeneralResponse<Void> approve(@RequestBody PairCode command, HttpServletRequest request, HttpServletResponse response) {
        devices.approve(browser(request, response), command.code());
        return GeneralResponse.success();
    }

    @GetMapping("/devices")
    GeneralResponse<List<SkillsDevices.Device>> devices(HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(devices.list(browser(request, response)));
    }

    @DeleteMapping("/devices/{id}")
    GeneralResponse<Void> revoke(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) {
        devices.revoke(browser(request, response), id);
        return GeneralResponse.success();
    }

    @ExceptionHandler(SkillProblem.class)
    GeneralResponse<?> problem(SkillProblem problem, HttpServletResponse response) {
        noStore(response);
        response.setStatus(problem.status);
        return new GeneralResponse<>(problem.status, problem.getMessage());
    }

    private SkillLibrary.Actor actor(HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null) {
            ready();
            return devices.bearer(authorization);
        }
        return browser(request, response);
    }

    private SkillLibrary.Actor browser(HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        if (!request.getMethod().equals("GET") && !"1".equals(request.getHeader("X-ZG-Skills-Request"))) {
            throw new SkillProblem(403, "请从 Dev Hub 发起操作");
        }
        ZhaogangSession session;
        try {
            session = sessions.resolve(request, response);
        } catch (ZhaogangSessionException error) {
            throw SkillsDevices.unauthorized();
        }
        if (session.teamId() == null || session.userId() == null) throw SkillsDevices.unauthorized();
        ready();
        String name = session.userName() == null || session.userName().isBlank()
                ? session.userId().toString() : session.userName().strip();
        return new SkillLibrary.Actor(session.teamId(), session.userId(), name.substring(0, Math.min(120, name.length())), "WEB");
    }

    private void ready() {
        if (!packages.enabled()) throw new SkillProblem(503, "Skills 制品存储未配置");
    }

    private void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
