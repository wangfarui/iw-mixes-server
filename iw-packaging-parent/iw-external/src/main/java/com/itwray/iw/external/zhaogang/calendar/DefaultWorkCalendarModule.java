package com.itwray.iw.external.zhaogang.calendar;

import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Context;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Day;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.DayType;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Month;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Schedule;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.UpdateDayCommand;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
class DefaultWorkCalendarModule implements WorkCalendarModule {

    private final WorkCalendarRepository repository;
    private final ZhaogangProperties properties;

    DefaultWorkCalendarModule(WorkCalendarRepository repository, ZhaogangProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public Month month(Context context, String monthValue) {
        requireContext(context);
        YearMonth month = parseMonth(monthValue);
        Schedule schedule = resolve(context.codingTeamId(), month);
        var leaveDates = java.util.Optional.ofNullable(
                repository.findLeaveDates(context.codingTeamId(), context.userId(), month)).orElse(java.util.Set.of());
        var days = schedule.days().stream()
                .map(day -> new Day(day.date(), day.dayType(), day.overridden(),
                        day.dayType() == DayType.WORKDAY && leaveDates.contains(day.date())))
                .toList();
        return new Month(schedule.month(), schedule.versionNo(), canManage(context), true, days);
    }

    @Override
    public Month updateDay(Context context, String dateValue, UpdateDayCommand command) {
        requireManager(context);
        LocalDate date = parseDate(dateValue);
        if (command == null || command.dayType() == null) {
            throw new WorkCalendarException("日期状态只能设置为工作日或休息日");
        }
        repository.saveDay(context.codingTeamId(), date, command.dayType(), context.userId(), context.userName());
        return month(context, YearMonth.from(date).toString());
    }

    @Override
    public Month resetDay(Context context, String dateValue) {
        requireManager(context);
        LocalDate date = parseDate(dateValue);
        repository.resetDay(context.codingTeamId(), date);
        return month(context, YearMonth.from(date).toString());
    }

    @Override
    public Month updateLeave(Context context, String dateValue, boolean leave) {
        requireContext(context);
        LocalDate date = parseDate(dateValue);
        if (leave && resolve(context.codingTeamId(), YearMonth.from(date)).days().stream()
                .filter(day -> day.date().equals(date)).findFirst()
                .map(day -> day.dayType() != DayType.WORKDAY).orElse(true)) {
            throw new WorkCalendarException("只有工作日可以设置请假日");
        }
        if (leave) repository.saveLeaveDate(context.codingTeamId(), context.userId(), date);
        else repository.resetLeaveDate(context.codingTeamId(), context.userId(), date);
        return month(context, YearMonth.from(date).toString());
    }

    @Override
    public Schedule resolve(long codingTeamId, YearMonth month) {
        if (codingTeamId <= 0) throw new WorkCalendarException("当前 CODING 顶层团队信息无效");
        WorkCalendarRepository.StoredSchedule stored = repository.find(codingTeamId, month);
        Map<LocalDate, WorkCalendarRepository.StoredDay> overrides = stored.days().stream()
                .collect(Collectors.toMap(WorkCalendarRepository.StoredDay::date, Function.identity()));
        var days = month.atDay(1).datesUntil(month.plusMonths(1).atDay(1))
                .map(date -> {
                    WorkCalendarRepository.StoredDay override = overrides.get(date);
                    return new Day(date, override == null ? WorkCalendarDefaults.dayType(date) : override.dayType(), override != null);
                })
                .toList();
        return new Schedule(month.toString(), stored.versionNo(), days);
    }

    private boolean canManage(Context context) {
        return context.userId() == properties.getCalendarManagerUserId();
    }

    private void requireManager(Context context) {
        requireContext(context);
        if (!canManage(context)) throw new WorkCalendarException("只有日历维护人可以设置工作日和休息日");
    }

    private void requireContext(Context context) {
        if (context == null || context.userId() <= 0 || context.codingTeamId() <= 0) {
            throw new WorkCalendarException("当前 CODING 身份信息无效");
        }
    }

    private YearMonth parseMonth(String value) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException | NullPointerException error) {
            throw new WorkCalendarException("月份参数格式应为 YYYY-MM");
        }
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException error) {
            throw new WorkCalendarException("日期参数格式应为 YYYY-MM-DD");
        }
    }
}
