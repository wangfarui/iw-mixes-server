package com.itwray.iw.external.zhaogang;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationException;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamException;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/** 找钢工作台统一异常契约，避免不同页面丢失 CODING 权限上下文。 */
@RestControllerAdvice(assignableTypes = {
        ZhaogangController.class,
        ZhaogangCalendarController.class,
        ZhaogangTeamController.class,
        ZhaogangIterationController.class,
        ZhaogangReleaseReceiptController.class
})
@Order(-1)
class ZhaogangExceptionHandler {

    private final ZhaogangSessionManager sessionManager;

    ZhaogangExceptionHandler(ZhaogangSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @ExceptionHandler(CodingOpenApiException.class)
    GeneralResponse<?> handleCodingException(CodingOpenApiException error) {
        return codingResponse(error);
    }

    @ExceptionHandler(TeamIterationException.class)
    GeneralResponse<?> handleIterationException(TeamIterationException error) {
        CodingOpenApiException permissionError = permissionCause(error);
        return permissionError == null ? GeneralResponse.fail(error.getMessage()) : codingResponse(permissionError);
    }

    @ExceptionHandler(WorkbenchTeamException.class)
    GeneralResponse<?> handleTeamException(WorkbenchTeamException error) {
        return GeneralResponse.fail(error.getMessage());
    }

    @ExceptionHandler(WorkCalendarException.class)
    GeneralResponse<?> handleCalendarException(WorkCalendarException error) {
        return GeneralResponse.fail(error.getMessage());
    }

    @ExceptionHandler(ZhaogangSessionException.class)
    GeneralResponse<?> handleSessionException(ZhaogangSessionException error, HttpServletResponse response) {
        sessionManager.clear(response);
        return new GeneralResponse<>(401, error.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    GeneralResponse<?> handleIllegalArgumentException(IllegalArgumentException error) {
        return GeneralResponse.fail(error.getMessage());
    }

    private GeneralResponse<?> codingResponse(CodingOpenApiException error) {
        if (!error.isPermissionDenied()) {
            return GeneralResponse.fail(error.getMessage());
        }
        return new GeneralResponse<>(403, error.permissionMessage(), new PermissionError(
                "CODING_PERMISSION_DENIED", error.requiredPermissions(), error.action(), error.code()));
    }

    private CodingOpenApiException permissionCause(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CodingOpenApiException codingError && codingError.isPermissionDenied()) {
                return codingError;
            }
            current = current.getCause();
        }
        return null;
    }

    record PermissionError(String type, List<String> missingPermissions, String action, String codingErrorCode) {
    }
}
