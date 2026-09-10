package com.itwray.iw.external.zhaogang;

import java.util.List;

/** 可安全返回给 Web 的 CODING 权限失败结构，不包含令牌或其它凭证。 */
public record CodingPermissionError(String type, String message, List<String> missingPermissions,
                                    String action, String codingErrorCode) {

    public CodingPermissionError {
        type = type == null ? "" : type;
        message = message == null ? "" : message;
        missingPermissions = missingPermissions == null ? List.of() : List.copyOf(missingPermissions);
        action = action == null ? "" : action;
        codingErrorCode = codingErrorCode == null ? "" : codingErrorCode;
    }

    public static CodingPermissionError from(CodingOpenApiException error) {
        return new CodingPermissionError("CODING_PERMISSION_DENIED", error.permissionMessage(),
                error.requiredPermissions(), error.action(), error.code());
    }
}
