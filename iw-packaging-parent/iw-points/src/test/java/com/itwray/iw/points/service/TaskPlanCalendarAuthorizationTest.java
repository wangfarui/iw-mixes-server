package com.itwray.iw.points.service;

import com.itwray.iw.points.controller.PointsTaskPlanController;
import com.itwray.iw.points.model.dto.plan.PointsTaskPlanPreviewDto;
import com.itwray.iw.points.model.dto.plan.PointsTaskPlanScheduleDto;
import com.itwray.iw.web.exception.AuthorizedException;
import com.itwray.iw.web.utils.UserUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class TaskPlanCalendarAuthorizationTest {
    @AfterEach void clear() { UserUtils.clearContext(); }

    @Test void anonymousCannotAccessCalendarConversionOrPreview() {
        UserUtils.clearContext();
        var service = mock(PointsTaskPlanService.class);
        var calendar = mock(PointsTaskPlanCalendar.class);
        var controller = new PointsTaskPlanController(service);
        ReflectionTestUtils.setField(controller, "calendar", calendar);
        assertThrows(AuthorizedException.class, () -> controller.calendar(2028));
        assertThrows(AuthorizedException.class, () -> controller.convertDate(new PointsTaskPlanScheduleDto()));
        assertThrows(AuthorizedException.class, () -> controller.preview(new PointsTaskPlanPreviewDto()));
        verifyNoInteractions(service, calendar);
    }
}
