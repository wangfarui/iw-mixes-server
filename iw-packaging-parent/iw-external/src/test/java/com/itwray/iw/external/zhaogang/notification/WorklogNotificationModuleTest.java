package com.itwray.iw.external.zhaogang.notification;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.calendar.*;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.*;
import com.itwray.iw.external.zhaogang.team.*;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.*;
import com.itwray.iw.external.zhaogang.worklog.WorklogDailyReader;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class WorklogNotificationModuleTest {
    private final NotificationStore store=mock(NotificationStore.class);
    private final WorkbenchTeamModule teams=mock(WorkbenchTeamModule.class);
    private final WorkCalendarResolver calendar=mock(WorkCalendarResolver.class);
    private final WorklogDailyReader reader=mock(WorklogDailyReader.class);
    private final WecomWebhook webhook=mock(WecomWebhook.class);
    private final WorklogNotificationModule module=new WorklogNotificationModule(store,teams,calendar,reader,new ObjectMapper().findAndRegisterModules(),webhook);
    private final LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
    private final NotificationStore.Config config=new NotificationStore.Config(1,10,true,"cipher","hash",1);
    @AfterEach void close() { module.shutdown(); }
    private CodeSyncTeam team() { return new CodeSyncTeam("团队","key","host",List.of(new CodeSyncMember(100,"张三","",null))); }
    private NotificationStore.Batch batch(long expires) { return new NotificationStore.Batch("batch",1,10,"DAILY",1,"hash","100","READY","",2,System.currentTimeMillis(),expires); }
    private void workday() { when(calendar.resolve(eq(10L),any())).thenReturn(new Schedule(today.toString().substring(0,7),1,List.of(new Day(today,DayType.WORKDAY,true)))); }
    @Test void usesWorkbenchCalendarIncludingOverrideInsteadOfWeekdayHeuristics() throws Exception {
        when(store.enabled()).thenReturn(List.of(config));
        when(calendar.resolve(eq(10L),any())).thenReturn(new Schedule("month",1,List.of(new Day(today,DayType.REST_DAY,true))));
        module.daily(); verify(store,never()).create(any(),anyString(),anyString(),anyString(),anyLong());
        workday(); when(teams.findCodeSyncTeam(10,1)).thenReturn(Optional.of(team()));
        when(store.create(eq(config),anyString(),eq("DAILY"),eq("100"),anyLong())).thenReturn("batch");
        when(reader.read(10,team(),today)).thenReturn(new WorklogDailyReader.Snapshot("团队",today,"17:00",List.of(new WorklogDailyReader.Member(100,"张三",false,true,"",List.of()))));
        module.daily(); verify(store).readyMessages(eq("batch"),anyString(),argThat(list->list.size()==2 && list.get(0).content().contains("今日工时报告") && list.get(1).content().contains("张三")));
    }
    @Test void cancelsChangedConfigAndRemovedMembersBeforeClaiming() {
        var b=batch(System.currentTimeMillis()+600_000); when(store.pending()).thenReturn(List.of(b));
        when(store.config(1)).thenReturn(new NotificationStore.Config(1,10,true,"cipher","hash",2));
        module.deliver(); verify(store).stop("batch","CANCELLED","通知配置已停用或更新");
        when(store.config(1)).thenReturn(config); when(teams.findCodeSyncTeam(10,1)).thenReturn(Optional.of(new CodeSyncTeam("team","key","host",List.of())));
        module.deliver(); verify(store).stop("batch","CANCELLED","团队已解散、成员或手机号发生变化");
        verify(store,never()).claim(any(),anyLong()); verify(webhook, never()).send(anyString(), any(NotificationMessage.class));
    }
    @Test void phoneChangeCancelsRemainingMessagesEvenWhenMembersAreUnchanged() {
        var before = new CodeSyncTeam("team", "key", "host", List.of(new CodeSyncMember(100, "张三", "", null, "13800000001")));
        var after = new CodeSyncTeam("team", "key", "host", List.of(new CodeSyncMember(100, "张三", "", null, "13800000002")));
        var now = System.currentTimeMillis();
        var batch = new NotificationStore.Batch("phone-change", 1, 10, "MANUAL", 1, "hash",
                WorklogNotificationModule.memberIds(before), "READY", "", 2, now, now + 600_000);
        when(store.pending()).thenReturn(List.of(batch));
        when(store.config(1)).thenReturn(config);
        when(teams.findCodeSyncTeam(10, 1)).thenReturn(Optional.of(after));
        module.deliver();
        verify(store).stop("phone-change", "CANCELLED", "团队已解散、成员或手机号发生变化");
        verify(store, never()).claim(any(), anyLong());
        verify(webhook, never()).send(anyString(), any(NotificationMessage.class));
    }
    @Test void expiresOldBatchesAndSubmitsOnlyClaimedMessage() {
        when(store.pending()).thenReturn(List.of(batch(0))); module.deliver(); verify(store).stop("batch","EXPIRED","已超过当日发送窗口");
        var b=batch(System.currentTimeMillis()+600_000); when(store.pending()).thenReturn(List.of(b)); when(store.config(1)).thenReturn(config);
        when(teams.findCodeSyncTeam(10,1)).thenReturn(Optional.of(team())); workday();
        var delivery=new NotificationStore.Delivery(b,0,"summary",1); when(store.claim(eq(b),anyLong())).thenReturn(delivery);
        var result=new WecomWebhook.Result(WecomWebhook.Outcome.ACCEPTED,"accepted"); when(webhook.send("cipher",NotificationMessage.markdown("summary"))).thenReturn(result);
        module.deliver(); verify(webhook).send("cipher",NotificationMessage.markdown("summary")); verify(store).complete(delivery,result);
    }
    @Test void deniesNotificationAccessToOrdinaryMember() {
        var actor=new Actor(200,"member","",10,"key","host");
        var detail=mock(TeamDetail.class); when(detail.permissions()).thenReturn(new Permissions(false,false,false,false,true,false)); when(teams.detail(actor,1)).thenReturn(detail);
        assertThatThrownBy(()->module.status(actor,1)).hasMessageContaining("只有团队管理员");
        assertThatThrownBy(()->module.save(actor,1,new WorklogNotificationModule.Command(false,0,null,false))).hasMessageContaining("只有团队管理员");
        assertThatThrownBy(()->module.test(actor,1,0)).hasMessageContaining("只有团队管理员");
        assertThatThrownBy(()->module.sendNow(actor,1,new WorklogNotificationModule.SendNowCommand(0,"request-0001"))).hasMessageContaining("只有团队管理员");
        verifyNoInteractions(store,webhook);
    }
    @Test void connectionTestQueuesExactlyOneMessageUsingSavedConfigWithoutEnabling() {
        var actor=new Actor(100,"admin","",10,"key","host");
        var detail=mock(TeamDetail.class); when(detail.permissions()).thenReturn(new Permissions(true,true,true,true,true,true)); when(teams.detail(actor,1)).thenReturn(detail);
        when(store.config(1)).thenReturn(new NotificationStore.Config(1,10,false,"cipher","hash",1));
        when(store.createTest(any(),eq(""),eq(1L),eq(100L))).thenReturn("test-batch");
        var status=module.test(actor,1,1);
        assertThat(status.enabled()).isFalse();
        verify(store).readyMessages(eq("test-batch"),eq("{}"),argThat(messages->messages.size()==1 && messages.get(0).content().contains("连接测试")));
        verify(store,never()).save(anyLong(),anyLong(),anyLong(),any(),any());
        verify(webhook, never()).send(anyString(), any(NotificationMessage.class));
    }
    @Test void candidateTestSendsToDraftWithoutReplacingSavedAddressOrEnabling() {
        var actor = manualAdmin();
        String candidate = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=new-fixture-1234";
        when(webhook.encrypt(candidate)).thenReturn("new-cipher");
        when(webhook.hint("cipher")).thenReturn("oldxxx0001");
        var batch = new NotificationStore.Batch("draft", 1, 10, "TEST", 1, "hash", "", "TESTING", "", 1, 1, 2);
        var delivery = new NotificationStore.Delivery(batch, 0, NotificationMessage.text("test"), 1);
        when(store.beginTest(eq(1L), eq(10L), eq(100L), eq(1L), anyString(), any())).thenReturn(delivery);
        when(webhook.send("new-cipher", delivery.message())).thenReturn(new WecomWebhook.Result(WecomWebhook.Outcome.ACCEPTED, "企微已受理"));
        var result = module.test(actor, 1, new WorklogNotificationModule.TestCommand(1, candidate));
        assertThat(result.state()).isEqualTo("ACCEPTED");
        assertThat(result.status().enabled()).isFalse();
        assertThat(result.status().webhookHint()).isEqualTo("oldxxx0001");
        verify(store).complete(delivery, new WecomWebhook.Result(WecomWebhook.Outcome.ACCEPTED, "企微已受理"));
        verify(store, never()).save(anyLong(), anyLong(), anyLong(), any(), any());
    }
    @Test void statusReturnsThreeOrderedHistoryEntriesWithProgress() {
        var actor = manualAdmin();
        var records = List.of(
                new NotificationStore.Batch("third", 1, 10, "TEST", 1, "hash", "", "ACCEPTED", "done", 1, 3, 4),
                new NotificationStore.Batch("second", 1, 10, "MANUAL", 1, "hash", "", "READY", "", 3, 2, 4),
                new NotificationStore.Batch("first", 1, 10, "DAILY", 1, "hash", "", "FAILED", "failed", 3, 1, 4));
        when(store.recent(1)).thenReturn(records);
        when(store.accepted("third")).thenReturn(1);
        var status = module.status(actor, 1);
        assertThat(status.recent()).extracting(WorklogNotificationModule.Latest::id).containsExactly("third", "second", "first");
        assertThat(status.latest()).isEqualTo(status.recent().get(0));
        assertThat(status.latest().accepted()).isEqualTo(1);
    }
    @Test void candidateTestRejectsInvalidAddressAndNeverRetriesUnknownOrBusyResults() {
        var actor = manualAdmin();
        assertThatThrownBy(() -> module.test(actor, 1, new WorklogNotificationModule.TestCommand(1, "http://127.0.0.1/secret")))
                .hasMessageContaining("有效的企业微信群");
        verify(store, never()).beginTest(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any());
        String candidate = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=fixture-test-0001";
        when(webhook.encrypt(candidate)).thenReturn("candidate-cipher");
        var batch = new NotificationStore.Batch("draft", 1, 10, "TEST", 1, "hash", "", "TESTING", "", 1, 1, 2);
        var delivery = new NotificationStore.Delivery(batch, 0, NotificationMessage.text("test"), 1);
        when(store.beginTest(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any())).thenReturn(delivery);
        when(webhook.send("candidate-cipher", delivery.message()))
                .thenReturn(new WecomWebhook.Result(WecomWebhook.Outcome.UNKNOWN, "投递结果未知"))
                .thenReturn(new WecomWebhook.Result(WecomWebhook.Outcome.RETRYABLE, "企微暂忙"));
        assertThat(module.test(actor, 1, new WorklogNotificationModule.TestCommand(1, candidate)).state()).isEqualTo("UNKNOWN");
        assertThat(module.test(actor, 1, new WorklogNotificationModule.TestCommand(1, candidate)).state()).isEqualTo("FAILED");
        verify(webhook, times(2)).send("candidate-cipher", delivery.message());
        verify(store).complete(delivery, new WecomWebhook.Result(WecomWebhook.Outcome.FAILED, "企微暂忙"));
        verify(store, never()).save(anyLong(), anyLong(), anyLong(), any(), any());
    }

    private Actor manualAdmin() {
        var actor=new Actor(100,"admin","",10,"key","host");
        var detail=mock(TeamDetail.class); when(detail.permissions()).thenReturn(new Permissions(true,true,true,true,true,true)); when(teams.detail(actor,1)).thenReturn(detail);
        when(store.config(1)).thenReturn(new NotificationStore.Config(1,10,false,"cipher","hash",1));
        when(teams.findCodeSyncTeam(10,1)).thenReturn(Optional.of(team()));
        return actor;
    }
    @Test void manualReportReturnsWhileCollectingAndQueuesFreshFullReportWithAutoNotificationOff() throws Exception {
        var actor=manualAdmin();
        when(store.createManual(any(),eq("100"),eq(1L),eq(100L),eq("request-0001")))
                .thenReturn(new NotificationStore.ManualBatch("manual-batch",true));
        var started=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(reader.read(10,team(),today)).thenAnswer(call -> {
            started.countDown();
            if (!release.await(3,TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
            return new WorklogDailyReader.Snapshot("团队",today,"17:24",List.of(new WorklogDailyReader.Member(100,"张三",false,true,"",List.of())));
        });
        try {
            var result=module.sendNow(actor,1,new WorklogNotificationModule.SendNowCommand(1,"request-0001"));
            assertThat(result.enabled()).isFalse();
            assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
            verify(store,never()).readyMessages(anyString(),anyString(),anyList());
            release.countDown();
            verify(store,timeout(2000)).readyMessages(eq("manual-batch"),anyString(),argThat(messages -> messages.size()==2 && messages.get(0).content().contains("今日工时报告") && messages.get(1).content().contains("张三")));
            verifyNoInteractions(calendar); verify(webhook, never()).send(anyString(), any(NotificationMessage.class)); // Manual reports do not depend on workday/17:00 or send in the HTTP request.
        } finally {release.countDown();}
    }
    @Test void retryOfSameManualRequestNeverCollectsOrEnqueuesAgain() {
        var actor=manualAdmin();
        when(store.createManual(any(),anyString(),anyLong(),anyLong(),anyString())).thenReturn(new NotificationStore.ManualBatch("existing",false));
        module.sendNow(actor,1,new WorklogNotificationModule.SendNowCommand(1,"request-0001"));
        verifyNoInteractions(reader,calendar); verify(webhook, never()).send(anyString(), any(NotificationMessage.class));
        verify(store,never()).readyMessages(anyString(),anyString(),anyList());
    }
    @Test void manualCollectionFailureIsRecordedWithoutSendingPartialBatch() {
        var actor=manualAdmin();
        when(store.createManual(any(),anyString(),anyLong(),anyLong(),anyString())).thenReturn(new NotificationStore.ManualBatch("manual-failed",true));
        when(reader.read(10,team(),today)).thenThrow(new IllegalStateException("fixture failure"));
        module.sendNow(actor,1,new WorklogNotificationModule.SendNowCommand(1,"request-0001"));
        verify(store,timeout(2000)).stop("manual-failed","FAILED","当日工时采集未完成，未发送报告");
        verify(store,never()).readyMessages(anyString(),anyString(),anyList()); verify(webhook, never()).send(anyString(), any(NotificationMessage.class));
    }
    @Test void manualDeliveryIgnoresAutomaticSwitchAndCalendarButStillChecksMembers() {
        var b=new NotificationStore.Batch("manual",1,10,"MANUAL",1,"hash","100","READY","",2,System.currentTimeMillis(),System.currentTimeMillis()+1_800_000);
        when(store.pending()).thenReturn(List.of(b)); when(store.config(1)).thenReturn(new NotificationStore.Config(1,10,false,"cipher","hash",1));
        when(teams.findCodeSyncTeam(10,1)).thenReturn(Optional.of(team()));
        var delivery=new NotificationStore.Delivery(b,0,"report",1); when(store.claim(eq(b),anyLong())).thenReturn(delivery);
        var accepted=new WecomWebhook.Result(WecomWebhook.Outcome.ACCEPTED,"ok");when(webhook.send("cipher",NotificationMessage.markdown("report"))).thenReturn(accepted);
        module.deliver(); verify(store).complete(delivery,accepted);verifyNoInteractions(calendar);
        when(teams.findCodeSyncTeam(10,1)).thenReturn(Optional.of(new CodeSyncTeam("team","key","host",List.of())));
        module.deliver();verify(store).stop("manual","CANCELLED","团队已解散、成员或手机号发生变化");
    }

}
