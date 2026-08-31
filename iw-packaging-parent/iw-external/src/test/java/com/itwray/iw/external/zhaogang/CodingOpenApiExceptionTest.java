package com.itwray.iw.external.zhaogang;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationException;
import com.itwray.iw.web.core.webmvc.ExceptionHandlerInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CodingOpenApiExceptionTest {

    @Test
    void mapsCodingActionToReadableTokenPermission() {
        CodingOpenApiException error = new CodingOpenApiException(
                "DescribeProjectDepotBranches", "UnauthorizedOperation", "无权访问");

        assertThat(error.isPermissionDenied()).isTrue();
        assertThat(error.requiredPermissions()).containsExactly("代码仓库（只读）");
        assertThat(error.permissionMessage()).contains("代码仓库（只读）", "令牌管理开通");
    }

    @Test
    void handlerReturnsStructuredPermissionDetails() {
        ZhaogangExceptionHandler handler = new ZhaogangExceptionHandler(mock(ZhaogangSessionManager.class));
        CodingOpenApiException error = new CodingOpenApiException(
                "TriggerCodingCIBuild", "UnauthorizedOperation", "permission denied");

        GeneralResponse<?> response = handler.handleCodingException(error);

        assertThat(response.getCode()).isEqualTo(403);
        assertThat(response.getMessage()).contains("持续集成构建（读写）");
        assertThat(response.getData()).isEqualTo(new ZhaogangExceptionHandler.PermissionError(
                "CODING_PERMISSION_DENIED", java.util.List.of("持续集成构建（读写）"),
                "TriggerCodingCIBuild", "UnauthorizedOperation"));
    }

    @Test
    void workbenchHandlerRunsBeforeTheGlobalFallbackAndKeepsIterationReasons() {
        Order workbenchOrder = ZhaogangExceptionHandler.class.getAnnotation(Order.class);
        Order globalOrder = ExceptionHandlerInterceptor.class.getAnnotation(Order.class);
        ZhaogangExceptionHandler handler = new ZhaogangExceptionHandler(mock(ZhaogangSessionManager.class));

        assertThat(workbenchOrder).isNotNull();
        assertThat(workbenchOrder.value()).isLessThan(globalOrder.value());
        assertThat(handler.handleIterationException(new TeamIterationException(
                "#4781 事项类型为“任务”，暂不支持关联。")).getMessage())
                .isEqualTo("#4781 事项类型为“任务”，暂不支持关联。");
    }
}
