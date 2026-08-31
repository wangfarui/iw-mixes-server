package com.itwray.iw.external.zhaogang.calendar;

import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Context;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.DayType;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Month;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.UpdateDayCommand;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultWorkCalendarModuleTest {

    private final Context manager = new Context(9292850L, "维护人", 10L);

    @Test
    void resolvesWeekdaysAndWeekendsWithStoredOverrides() {
        WorkCalendarRepository repository = mock(WorkCalendarRepository.class);
        when(repository.find(10L, YearMonth.of(2026, 7))).thenReturn(new WorkCalendarRepository.StoredSchedule(4,
                List.of(new WorkCalendarRepository.StoredDay(LocalDate.of(2026, 7, 3), DayType.REST_DAY),
                        new WorkCalendarRepository.StoredDay(LocalDate.of(2026, 7, 4), DayType.WORKDAY))));
        DefaultWorkCalendarModule module = new DefaultWorkCalendarModule(repository, properties());

        Month month = module.month(manager, "2026-07");

        assertThat(month.versionNo()).isEqualTo(4);
        assertThat(month.canManage()).isTrue();
        assertThat(month.days().get(2).dayType()).isEqualTo(DayType.REST_DAY);
        assertThat(month.days().get(2).overridden()).isTrue();
        assertThat(month.days().get(3).dayType()).isEqualTo(DayType.WORKDAY);
        assertThat(month.days().get(3).overridden()).isTrue();
    }

    @Test
    void onlyConfiguredManagerCanUpdateCalendar() {
        WorkCalendarRepository repository = mock(WorkCalendarRepository.class);
        DefaultWorkCalendarModule module = new DefaultWorkCalendarModule(repository, properties());

        assertThatThrownBy(() -> module.updateDay(new Context(100L, "普通成员", 10L), "2026-07-04",
                new UpdateDayCommand(DayType.WORKDAY)))
                .isInstanceOf(WorkCalendarException.class)
                .hasMessage("只有日历维护人可以设置工作日和休息日");

        when(repository.find(10L, YearMonth.of(2026, 7)))
                .thenReturn(new WorkCalendarRepository.StoredSchedule(1, List.of()));
        module.updateDay(manager, "2026-07-04", new UpdateDayCommand(DayType.WORKDAY));
        verify(repository).saveDay(10L, LocalDate.of(2026, 7, 4), DayType.WORKDAY, 9292850L, "维护人");
    }

    @Test
    void exposesAndUpdatesPersonalLeaveWithoutChangingSharedPermission() {
        WorkCalendarRepository repository = mock(WorkCalendarRepository.class);
        LocalDate leaveDate = LocalDate.of(2026, 7, 6);
        when(repository.find(10L, YearMonth.of(2026, 7)))
                .thenReturn(new WorkCalendarRepository.StoredSchedule(2, List.of()));
        when(repository.findLeaveDates(10L, 9292850L, YearMonth.of(2026, 7))).thenReturn(Set.of(leaveDate));
        DefaultWorkCalendarModule module = new DefaultWorkCalendarModule(repository, properties());

        Month month = module.month(manager, "2026-07");

        assertThat(month.canManageLeave()).isTrue();
        assertThat(month.days().get(5).leave()).isTrue();
        module.updateLeave(manager, "2026-07-07", true);
        verify(repository).saveLeaveDate(10L, 9292850L, LocalDate.of(2026, 7, 7));
    }

    @Test
    void rejectsPersonalLeaveOnRestDay() {
        WorkCalendarRepository repository = mock(WorkCalendarRepository.class);
        when(repository.find(10L, YearMonth.of(2026, 7)))
                .thenReturn(new WorkCalendarRepository.StoredSchedule(0, List.of()));
        DefaultWorkCalendarModule module = new DefaultWorkCalendarModule(repository, properties());

        assertThatThrownBy(() -> module.updateLeave(manager, "2026-07-05", true))
                .isInstanceOf(WorkCalendarException.class)
                .hasMessage("只有工作日可以设置请假日");
    }

    private ZhaogangProperties properties() {
        ZhaogangProperties properties = new ZhaogangProperties();
        properties.setCalendarManagerUserId(9292850L);
        return properties;
    }
}
