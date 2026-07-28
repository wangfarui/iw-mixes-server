package com.itwray.iw.external.controller;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.remoteshare.RemoteShareSessionService;
import com.itwray.iw.external.remoteshare.RemoteShareBinaryStore;
import com.itwray.iw.external.remoteshare.RemoteShareTextStore;
import com.itwray.iw.web.annotation.SkipWrapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Public, anonymous pairing API. Request bodies contain only browser-generated opaque identifiers and ciphertext. */
@SkipWrapper
@Validated
@RestController
@RequestMapping("/external-service/api/remote-share")
@Tag(name = "两设备远程共享公开接口")
public class RemoteShareController {

    private final RemoteShareSessionService sessions;
    private final RemoteShareTextStore texts;
    private final RemoteShareBinaryStore binaries;
    private final Clock clock = Clock.systemUTC();

    public RemoteShareController(RemoteShareSessionService sessions, RemoteShareTextStore texts, RemoteShareBinaryStore binaries) {
        this.sessions = sessions;
        this.texts = texts;
        this.binaries = binaries;
    }

    @PostMapping("/sessions")
    public ResponseEntity<GeneralResponse<RemoteShareSessionService.JoinedDevice>> create(@RequestBody @Valid CreateSessionRequest request) {
        return respond(() -> sessions.create(request.roomId(), request.accessToken()));
    }

    @PostMapping("/sessions/{roomId}/join")
    public ResponseEntity<GeneralResponse<RemoteShareSessionService.JoinedDevice>> join(@PathVariable String roomId,
                                                                                          @RequestBody @Valid JoinSessionRequest request) {
        return respond(() -> sessions.join(roomId, request.accessToken()));
    }

    @GetMapping("/sessions/{roomId}")
    public ResponseEntity<GeneralResponse<RemoteShareSessionService.SessionState>> state(@PathVariable String roomId,
                                                                                           @NotBlank @Size(max = 128) String capability) {
        return respond(() -> sessions.state(roomId, capability));
    }

    @PostMapping("/sessions/{roomId}/reset-second-slot")
    public ResponseEntity<GeneralResponse<Void>> resetSecondSlot(@PathVariable String roomId,
                                                                   @RequestBody @Valid CapabilityRequest request) {
        return respondVoid(() -> sessions.resetSecondSlot(roomId, request.capability()));
    }

    @PostMapping("/sessions/{roomId}/close")
    public ResponseEntity<GeneralResponse<Void>> close(@PathVariable String roomId,
                                                        @RequestBody @Valid CapabilityRequest request) {
        return respondVoid(() -> {
            sessions.close(roomId, request.capability());
            texts.clear(roomId);
            binaries.clearRoom(roomId);
        });
    }

    @PostMapping("/sessions/{roomId}/texts")
    public ResponseEntity<GeneralResponse<Void>> sendText(@PathVariable String roomId,
                                                           @RequestBody @Valid EncryptedTextRequest request) {
        return respondVoid(() -> {
            RemoteShareSessionService.SessionState state = sessions.state(roomId, request.capability());
            texts.enqueue(roomId, state.slot(), request.ciphertext(), state.expiresAt());
        });
    }

    @GetMapping("/sessions/{roomId}/texts")
    public ResponseEntity<GeneralResponse<List<RemoteShareTextStore.PendingText>>> claimTexts(@PathVariable String roomId,
                                                                                                 @NotBlank @Size(max = 128) String capability) {
        return respond(() -> {
            RemoteShareSessionService.SessionState state = sessions.state(roomId, capability);
            return texts.claimFor(roomId, state.slot(), Instant.now(clock));
        });
    }

    @PostMapping("/sessions/{roomId}/binaries")
    public ResponseEntity<GeneralResponse<Void>> beginBinary(@PathVariable String roomId, @RequestBody @Valid BeginBinaryRequest request) {
        return respondVoid(() -> binaries.begin(roomId, request.capability(), request.itemId(), request.totalBytes(), request.chunks(), request.encryptedManifest()));
    }

    @PutMapping("/sessions/{roomId}/binaries/{itemId}/chunks/{index}")
    public ResponseEntity<GeneralResponse<Void>> uploadBinaryChunk(@PathVariable String roomId, @PathVariable String itemId,
                                                                    @PathVariable int index, @NotBlank String capability,
                                                                    HttpServletRequest request) {
        return respondVoid(() -> {
            try {
                binaries.appendChunk(roomId, capability, itemId, index, request.getInputStream(), request.getContentLengthLong());
            } catch (IOException exception) {
                throw new IllegalArgumentException("无法读取上传内容", exception);
            }
        });
    }

    @PostMapping("/sessions/{roomId}/binaries/{itemId}/complete")
    public ResponseEntity<GeneralResponse<Void>> completeBinary(@PathVariable String roomId, @PathVariable String itemId,
                                                                 @RequestBody @Valid CapabilityRequest request) {
        return respondVoid(() -> binaries.complete(roomId, request.capability(), itemId));
    }

    @GetMapping("/sessions/{roomId}/binaries")
    public ResponseEntity<GeneralResponse<List<RemoteShareBinaryStore.PendingBinary>>> pendingBinaries(@PathVariable String roomId,
                                                                                                           @NotBlank String capability) {
        return respond(() -> binaries.pendingFor(roomId, capability));
    }

    @GetMapping("/sessions/{roomId}/binaries/{itemId}/chunks/{index}")
    public ResponseEntity<StreamingResponseBody> downloadBinaryChunk(@PathVariable String roomId, @PathVariable String itemId,
                                                                      @PathVariable int index, @NotBlank String capability) {
        try {
            return ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                    .body(output -> Files.copy(binaries.receiverChunk(roomId, capability, itemId, index), output));
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @PostMapping("/sessions/{roomId}/binaries/{itemId}/receipt")
    public ResponseEntity<GeneralResponse<Void>> receiptBinary(@PathVariable String roomId, @PathVariable String itemId,
                                                                @RequestBody @Valid CapabilityRequest request) {
        return respondVoid(() -> binaries.receipt(roomId, request.capability(), itemId));
    }

    private <T> ResponseEntity<GeneralResponse<T>> respond(ThrowingSupplier<T> supplier) {
        try {
            return ResponseEntity.ok(GeneralResponse.success(supplier.get()));
        } catch (RuntimeException exception) {
            return error(exception);
        }
    }

    private ResponseEntity<GeneralResponse<Void>> respondVoid(ThrowingRunnable runnable) {
        try {
            runnable.run();
            return ResponseEntity.ok(GeneralResponse.success());
        } catch (RuntimeException exception) {
            return error(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> ResponseEntity<GeneralResponse<T>> error(RuntimeException exception) {
        if (exception instanceof RemoteShareSessionService.SessionFullException
                || exception instanceof RemoteShareSessionService.SessionAlreadyExistsException
                || exception instanceof RemoteShareTextStore.TextQuotaExceededException) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new GeneralResponse<>(409, messageFor(exception)));
        }
        if (exception instanceof RemoteShareSessionService.ForbiddenException) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new GeneralResponse<>(403, "会话凭证无效"));
        }
        if (exception instanceof RemoteShareSessionService.SessionExpiredException) {
            return ResponseEntity.status(HttpStatus.GONE).body(new GeneralResponse<>(410, "会话已过期"));
        }
        if (exception instanceof RemoteShareSessionService.SessionNotFoundException) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new GeneralResponse<>(404, "会话不存在"));
        }
        return ResponseEntity.badRequest().body(new GeneralResponse<>(400, "请求参数无效"));
    }

    private String messageFor(RuntimeException exception) {
        if (exception instanceof RemoteShareSessionService.SessionFullException) {
            return "会话已有两台设备";
        }
        if (exception instanceof RemoteShareTextStore.TextQuotaExceededException) {
            return "临时文本额度不足";
        }
        return "会话已存在";
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> { T get(); }
    @FunctionalInterface
    private interface ThrowingRunnable { void run(); }

    public record CreateSessionRequest(@NotBlank @Size(max = 128) String roomId,
                                       @NotBlank @Size(max = 256) String accessToken) { }
    public record JoinSessionRequest(@NotBlank @Size(max = 256) String accessToken) { }
    public record CapabilityRequest(@NotBlank @Size(max = 128) String capability) { }
    public record EncryptedTextRequest(@NotBlank @Size(max = 128) String capability,
                                       @NotBlank @Size(max = 350000) String ciphertext) { }
    public record BeginBinaryRequest(@NotBlank @Size(max = 128) String capability,
                                     @NotBlank @Size(max = 80) String itemId,
                                     long totalBytes,
                                     int chunks,
                                     @NotBlank @Size(max = 350000) String encryptedManifest) { }
}
