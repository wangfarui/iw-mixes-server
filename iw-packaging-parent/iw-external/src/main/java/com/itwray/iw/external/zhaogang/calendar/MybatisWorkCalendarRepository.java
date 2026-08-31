package com.itwray.iw.external.zhaogang.calendar;

import com.itwray.iw.external.mapper.ZhaogangWorkCalendarDayMapper;
import com.itwray.iw.external.mapper.ZhaogangWorkCalendarLeaveMapper;
import com.itwray.iw.external.mapper.ZhaogangWorkCalendarMapper;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.DayType;
import com.itwray.iw.external.zhaogang.calendar.entity.WorkCalendarEntities.CalendarDayEntity;
import com.itwray.iw.external.zhaogang.calendar.entity.WorkCalendarEntities.CalendarEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
class MybatisWorkCalendarRepository implements WorkCalendarRepository {

    private final ZhaogangWorkCalendarMapper calendarMapper;
    private final ZhaogangWorkCalendarDayMapper dayMapper;
    private final ZhaogangWorkCalendarLeaveMapper leaveMapper;

    MybatisWorkCalendarRepository(ZhaogangWorkCalendarMapper calendarMapper,
                                  ZhaogangWorkCalendarDayMapper dayMapper,
                                  ZhaogangWorkCalendarLeaveMapper leaveMapper) {
        this.calendarMapper = calendarMapper;
        this.dayMapper = dayMapper;
        this.leaveMapper = leaveMapper;
    }

    @Override
    public StoredSchedule find(long codingTeamId, YearMonth month) {
        CalendarEntity calendar = calendarMapper.selectByCodingTeamId(codingTeamId);
        if (calendar == null) return new StoredSchedule(0, List.of());
        LocalDate from = month.atDay(1);
        List<StoredDay> days = dayMapper.selectRange(calendar.getId(), from, month.plusMonths(1).atDay(1)).stream()
                .map(this::toStoredDay)
                .toList();
        return new StoredSchedule(calendar.getVersionNo(), days);
    }

    @Override
    @Transactional
    public void saveDay(long codingTeamId, LocalDate date, DayType dayType,
                        long updaterUserId, String updaterUserName) {
        CalendarEntity calendar = requireCalendar(codingTeamId);
        DayType defaultType = WorkCalendarDefaults.dayType(date);
        int changed = dayType == defaultType
                ? dayMapper.deleteDay(calendar.getId(), date)
                : dayMapper.upsert(calendar.getId(), date, dayType.name(), updaterUserId, updaterUserName);
        if (changed > 0) calendarMapper.touch(calendar.getId());
    }

    @Override
    @Transactional
    public void resetDay(long codingTeamId, LocalDate date) {
        CalendarEntity calendar = calendarMapper.selectByCodingTeamId(codingTeamId);
        if (calendar != null && dayMapper.deleteDay(calendar.getId(), date) > 0) {
            calendarMapper.touch(calendar.getId());
        }
    }

    @Override
    public Set<LocalDate> findLeaveDates(long codingTeamId, long codingUserId, YearMonth month) {
        return leaveMapper.selectRange(codingTeamId, codingUserId, month.atDay(1), month.plusMonths(1).atDay(1))
                .stream().collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void saveLeaveDate(long codingTeamId, long codingUserId, LocalDate date) {
        leaveMapper.insertLeave(codingTeamId, codingUserId, date);
    }

    @Override
    public void resetLeaveDate(long codingTeamId, long codingUserId, LocalDate date) {
        leaveMapper.deleteLeave(codingTeamId, codingUserId, date);
    }

    private CalendarEntity requireCalendar(long codingTeamId) {
        calendarMapper.insertIgnore(codingTeamId);
        CalendarEntity calendar = calendarMapper.selectByCodingTeamId(codingTeamId);
        if (calendar == null) throw new WorkCalendarException("工作日历初始化失败，请稍后重试");
        return calendar;
    }

    private StoredDay toStoredDay(CalendarDayEntity entity) {
        return new StoredDay(entity.getWorkDate(), DayType.valueOf(entity.getDayType()));
    }
}
