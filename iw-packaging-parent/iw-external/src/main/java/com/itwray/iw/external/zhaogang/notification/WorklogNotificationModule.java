package com.itwray.iw.external.zhaogang.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarResolver;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.DayType;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamException;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModule;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.Actor;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.CodeSyncTeam;
import com.itwray.iw.external.zhaogang.worklog.WorklogDailyReader;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class WorklogNotificationModule {
    public record Command(boolean enabled, long revision, String webhook, boolean clearWebhook) {
        @Override public String toString() { return "Command[enabled=" + enabled + ", revision=" + revision + ", webhook=[redacted], clearWebhook=" + clearWebhook + "]"; }
    }
    public record TestCommand(long revision) { }
    public record SendNowCommand(long revision, String requestId) { }
    public record Latest(String state, String reason, int accepted, int total, long createdAt, String kind) { }
    public record Status(boolean enabled, boolean webhookConfigured, long revision, Latest latest) { }
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final NotificationStore store;
    private final WorkbenchTeamModule teams;
    private final WorkCalendarResolver calendar;
    private final WorklogDailyReader reader;
    private final ObjectMapper json;
    private final WecomWebhook webhook;
    private final WorklogMessageFormatter formatter = new WorklogMessageFormatter();
    private final ExecutorService collection = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "worklog-notification-collection"); thread.setDaemon(true); return thread;
    });

    private final ExecutorService manualReports = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> {
                Thread thread = new Thread(r, "worklog-notification-manual"); thread.setDaemon(true); return thread;
            });

    public WorklogNotificationModule(NotificationStore store, WorkbenchTeamModule teams, WorkCalendarResolver calendar,
                                    WorklogDailyReader reader, ObjectMapper json, WecomWebhook webhook) {
        this.store=store; this.teams=teams; this.calendar=calendar; this.reader=reader; this.json=json; this.webhook=webhook;
    }
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    @PostConstruct
    void start() {
        // This private scheduler does not replace Spring Boot's scheduler for existing business jobs.
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("worklog-notification-");
        scheduler.initialize();
        scheduler.schedule(this::daily, new CronTrigger("0 0 17 * * *", ZONE));
        scheduler.scheduleWithFixedDelay(this::deliver, Duration.ofSeconds(1));
        scheduler.schedule(this::cleanup, new CronTrigger("0 15 3 * * *", ZONE));
    }
    public Status status(Actor actor, long teamId) { requireAdmin(actor,teamId); return status(teamId); }
    public Status save(Actor actor, long teamId, Command command) {
        requireAdmin(actor,teamId);
        store.save(teamId,actor.codingTeamId(),actor.userId(),command,webhook);
        return status(teamId);
    }
    public Status test(Actor actor, long teamId, long revision) {
        requireAdmin(actor,teamId);
        var config = store.config(teamId);
        if (config == null || config.cipher() == null) throw new WorkbenchTeamException("请先保存群 Webhook");
        String id = store.createTest(config, "", revision, actor.userId());
        store.ready(id, "{}", List.of("工作台助手｜连接测试\n群 Webhook 已连接。\n这是一条测试消息，不包含工时数据，也不会自动开启通知。"));
        return status(teamId);
    }
    public Status sendNow(Actor actor, long teamId, SendNowCommand command) {
        requireAdmin(actor, teamId);
        var config = store.config(teamId);
        if (config == null || config.cipher() == null) throw new WorkbenchTeamException("请先保存群 Webhook");
        var team = teams.findCodeSyncTeam(actor.codingTeamId(), teamId)
                .orElseThrow(() -> new WorkbenchTeamException("团队不存在或已解散"));
        var batch = store.createManual(config, memberIds(team), command.revision(), actor.userId(), command.requestId());
        if (batch.created()) {
            LocalDate date = LocalDate.now(ZONE);
            try {
                manualReports.execute(() -> collect(batch.id(), config, team, date));
            } catch (RejectedExecutionException e) {
                store.stop(batch.id(), "FAILED", "报告采集繁忙，请稍后重新触发");
                throw new WorkbenchTeamException("报告采集繁忙，请稍后重新触发");
            }
        }
        return status(teamId);
    }
    private void collect(String id, NotificationStore.Config config, CodeSyncTeam team, LocalDate date) {
        try {
            // Manual and scheduled reports share one reader, formatter and durable sending queue.
            var future = collection.submit(() -> reader.read(config.codingTeamId(), team, date));
            try {
                var snapshot = future.get(120, TimeUnit.SECONDS);
                store.ready(id, json.writeValueAsString(snapshot), formatter.format(snapshot));
            } finally { if (!future.isDone()) future.cancel(true); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            store.stop(id, "FAILED", "当日工时采集中断，未发送报告");
        } catch (Exception e) { store.stop(id, "FAILED", "当日工时采集未完成，未发送报告"); }
    }
    private void requireAdmin(Actor actor,long teamId) {
        if (!teams.detail(actor,teamId).permissions().administrator()) throw new WorkbenchTeamException("只有团队管理员可以管理工时群通知");
    }
    private Status status(long teamId) {
        var config=store.config(teamId); var latest=store.latest(teamId);
        return new Status(config != null && config.enabled(), config != null && config.cipher() != null,
                config == null ? 0 : config.revision(), latest == null ? null : new Latest(latest.state(), latest.reason(),store.accepted(latest.id()),latest.total(),latest.createdAt(),latest.kind()));
    }
    void daily() {
        LocalDate date=LocalDate.now(ZONE);
        for (var config:store.enabled()) {
            try {
                if (calendar.resolve(config.codingTeamId(),YearMonth.from(date)).days().stream()
                        .noneMatch(day->day.date().equals(date) && day.dayType()==DayType.WORKDAY)) continue;
                var team=teams.findCodeSyncTeam(config.codingTeamId(),config.teamId()).orElse(null);
                if (team==null) continue;
                long expiry=date.atTime(17,30).atZone(ZONE).toInstant().toEpochMilli();
                String id=store.create(config,"WORKLOG_DAILY_REPORT:"+date,"DAILY",memberIds(team),expiry);
                if (id==null) continue;
                collect(id, config, team, date);
            } catch (RuntimeException e) { log.warn("工时通知批次创建失败，teamId={}，类型={}",config.teamId(),e.getClass().getSimpleName()); }
        }
    }
    void deliver() {
        try {
            store.recoverUnknown(System.currentTimeMillis());
            for (var batch:store.pending()) {
                long now=System.currentTimeMillis();
                if (now>=batch.expiresAt()) { store.stop(batch.id(),"EXPIRED","已超过当日发送窗口"); continue; }
                if ("COLLECTING".equals(batch.state())) {
                    if (batch.createdAt()<now-180_000) store.stop(batch.id(),"FAILED","采集进程中断或超时，未发送报告");
                    continue;
                }
                var config=store.config(batch.teamId());
                if (config==null || config.revision()!=batch.revision() || !Objects.equals(config.hash(),batch.hash())
                        || "DAILY".equals(batch.kind()) && !config.enabled()) {
                    store.stop(batch.id(),"CANCELLED","通知配置已停用或更新"); continue;
                }
                var team=teams.findCodeSyncTeam(batch.codingTeamId(),batch.teamId()).orElse(null);
                if (team==null || !"TEST".equals(batch.kind()) && !memberIds(team).equals(batch.members())) {
                    store.stop(batch.id(),"CANCELLED","团队已解散或成员发生变化"); continue;
                }
                if ("DAILY".equals(batch.kind()) && calendar.resolve(batch.codingTeamId(),YearMonth.now(ZONE)).days().stream()
                        .noneMatch(day->day.date().equals(LocalDate.now(ZONE)) && day.dayType()==DayType.WORKDAY)) {
                    store.stop(batch.id(),"CANCELLED","日历已调整为非工作日"); continue;
                }
                var delivery=store.claim(batch,now);
                if (delivery!=null) store.complete(delivery,webhook.send(config.cipher(),delivery.content()));
            }
        } catch (RuntimeException e) {
            // Safe exception type only; SQL/URI/HTTP messages can include configuration secrets.
            log.warn("工时通知发送队列处理失败，类型={}",e.getClass().getSimpleName());
        }
    }
    void cleanup() { store.cleanup(System.currentTimeMillis()); }
    static String memberIds(CodeSyncTeam team) {
        return team.members().stream().map(m->m.userId()).sorted().map(String::valueOf).collect(Collectors.joining(","));
    }
    @PreDestroy void shutdown() { scheduler.shutdown(); manualReports.shutdownNow(); collection.shutdownNow(); }
}
