package com.itwray.iw.external.zhaogang.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Requires an explicitly supplied disposable schema; never uses application datasource settings. */
@EnabledIfEnvironmentVariable(named="IW_NOTIFICATION_TEST_JDBC_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/notify_accept_[a-zA-Z0-9_]+.*")
class NotificationStoreMysqlTest {
    private JdbcTemplate jdbc;
    private NotificationStore store;
    private WecomWebhook webhook;
    private long id;
    private TransactionTemplate tx;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource(System.getenv("IW_NOTIFICATION_TEST_JDBC_URL"),"root","");
        jdbc=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        String sql=Files.readString(Path.of("scripts/iw-external-migrate-20261113-worklog-notification.sql"));
        for (int repeat=0;repeat<2;repeat++) for (String statement:sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        jdbc.execute("CREATE TABLE IF NOT EXISTS external_zhaogang_workbench_team (id BIGINT PRIMARY KEY,coding_team_id BIGINT,administrator_user_id BIGINT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS external_zhaogang_workbench_team_member (team_id BIGINT,coding_user_id BIGINT,PRIMARY KEY(team_id,coding_user_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS external_zhaogang_workbench_profile (coding_team_id BIGINT,coding_user_id BIGINT,PRIMARY KEY(coding_team_id,coding_user_id))");
        jdbc.update("INSERT IGNORE INTO " + NotificationStore.C + " (team_id,coding_team_id,revision) VALUES (0,10,5)");
        migrate("iw-external-migrate-20261116-worklog-mobile.sql");
        migrate("iw-external-migrate-20261118-worklog-notification-time.sql");
        id=System.nanoTime(); jdbc.update("INSERT INTO external_zhaogang_workbench_team VALUES (?,10,100)",id);
        store=new NotificationStore(jdbc,tx); webhook=new WecomWebhook(new ObjectMapper(),new ZhaogangProperties());
    }
    private void migrate(String filename) throws Exception {
        String sql = Files.readString(Path.of("scripts", filename));
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var sqlStatement = connection.createStatement()) {
                for (int repeat=0;repeat<2;repeat++) for (String statement:sql.split(";"))
                    if (!statement.isBlank()) sqlStatement.execute(statement);
            }
            return null;
        });
    }
    private NotificationStore.Config config() { return store.save(id,10,100,new WorklogNotificationModule.Command(true,0,"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=fixture-key-"+id,false),webhook); }
    private NotificationStore.Batch batch(String id) { return store.pending().stream().filter(b->b.id().equals(id)).findFirst().orElseThrow(); }
    @Test void enforcesRevisionAuthorizationAndClearAtomically() {
        var c=config(); assertThat(c.revision()).isEqualTo(1);
        assertThat(c.cipher()).doesNotContain("fixture-key");
        assertThatThrownBy(()->store.save(id,10,100,new WorklogNotificationModule.Command(false,0,null,false),webhook)).hasMessageContaining("已变化");
        assertThatThrownBy(()->store.save(id,10,200,new WorklogNotificationModule.Command(false,1,null,false),webhook)).hasMessageContaining("管理员");
        var kept=store.save(id,10,100,new WorklogNotificationModule.Command(true,1,null,false),webhook);
        assertThat(kept.cipher()).isEqualTo(c.cipher());
        var cleared=store.save(id,10,100,new WorklogNotificationModule.Command(true,2,null,true),webhook);
        assertThat(cleared.enabled()).isFalse(); assertThat(cleared.cipher()).isNull();
    }
    @Test void dailyCreationAndMessageClaimAreUniqueAcrossInstances() throws Exception {
        var c=config();
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            var tasks=new ArrayList<Callable<String>>();
            for(int i=0;i<8;i++) tasks.add(() -> store.createDaily(c, LocalDate.of(2026, 11, 13), "1,2",
                    System.currentTimeMillis() + 600_000));
            var ids=pool.invokeAll(tasks).stream().map(f->{try{return f.get();}catch(Exception e){throw new RuntimeException(e);}}).filter(Objects::nonNull).toList();
            assertThat(ids).hasSize(1);
            store.ready(ids.get(0),"{}",List.of("team-summary","member-one","member-two"));
            var b=batch(ids.get(0)); long now=System.currentTimeMillis();
            var first=store.claim(b,now); assertThat(first.sequence()).isZero();
            var restarted=new NotificationStore(jdbc,tx);
            assertThat(restarted.claim(b,now+5000)).isNull(); // In-flight submission cannot be reclaimed.
            store.complete(first,new WecomWebhook.Result(WecomWebhook.Outcome.ACCEPTED,"accepted"));
            assertThat(restarted.claim(b,System.currentTimeMillis())).isNull();
            var second=restarted.claim(b,System.currentTimeMillis()+5000);
            assertThat(second.sequence()).isEqualTo(1); assertThat(second.message().content()).isEqualTo("member-one");
            store.complete(second,new WecomWebhook.Result(WecomWebhook.Outcome.FAILED,"rejected"));
            assertThat(store.latest(id).state()).isEqualTo("FAILED"); assertThat(store.accepted(b.id())).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    @Test void savesTeamTimeWithoutReplacingTheAlreadyScheduledBatch() throws Exception {
        assertThat(store.config(0).sendTime()).isEqualTo(LocalTime.of(17, 0));
        assertThat(store.config(0).deliveryRevision()).isEqualTo(5);
        var initial = config();
        var date = LocalDate.of(2026, 11, 18);
        long expiry = System.currentTimeMillis() + 600_000;
        String batchId = store.createDaily(initial, date, "100", expiry);
        store.ready(batchId, "{}", List.of("summary"));

        assertThatThrownBy(() -> store.save(id, 10, 100,
                new WorklogNotificationModule.Command(true, 1, null, false, null, "24:00"), webhook))
                .hasMessageContaining("HH:mm");
        var updated = store.save(id, 10, 100,
                new WorklogNotificationModule.Command(true, 1, null, false, null, "18:45"), webhook);
        assertThat(updated.revision()).isEqualTo(2);
        assertThat(updated.deliveryRevision()).isEqualTo(initial.revision());
        assertThat(store.due(LocalTime.of(17, 0))).doesNotContain(updated);
        assertThat(store.due(LocalTime.of(18, 45))).contains(updated);
        assertThat(batch(batchId).expiresAt()).isEqualTo(expiry);
        assertThat(store.createDaily(updated, date, "100", expiry)).isNull();
        assertThat(store.createDaily(initial, date.plusDays(1), "100", expiry)).isNull();
        migrate("iw-external-migrate-20261118-worklog-notification-time.sql");
        assertThat(store.config(id)).isEqualTo(updated);

        var disabled = store.save(id, 10, 100, new WorklogNotificationModule.Command(false, 2, null, false), webhook);
        assertThat(disabled.sendTime()).isEqualTo(LocalTime.of(18, 45));
        assertThat(store.due(LocalTime.of(18, 45))).doesNotContain(disabled);
        assertThat(store.latest(id).state()).isEqualTo("CANCELLED");
    }
    @Test void pausesUnknownSubmissionAndStopsAfterTwoExplicitRetries() {
        var c=config(); String key=store.create(c,"a","TEST","",System.currentTimeMillis()+600_000);
        store.ready(key,"{}",List.of("test")); var b=batch(key);
        var first=store.claim(b,System.currentTimeMillis());
        assertThat(first).isNotNull();
        jdbc.update("UPDATE "+NotificationStore.M+" SET updated_at=0 WHERE batch_id=?",key);
        assertThat(store.claim(b,System.currentTimeMillis()+5000)).isNull();
        assertThat(store.latest(id).state()).isEqualTo("UNKNOWN");
        String next=store.create(c,"b","TEST","",System.currentTimeMillis()+600_000); store.ready(next,"{}",List.of("retry"));
        var retry=batch(next);
        for(int i=1;i<=3;i++) {
            long now=System.currentTimeMillis()+i*70_000;
            var d=store.claim(retry,now); assertThat(d).isNotNull(); assertThat(d.attempts()).isEqualTo(i);
            store.complete(d,new WecomWebhook.Result(WecomWebhook.Outcome.RETRYABLE,"busy"));
        }
        assertThat(store.latest(id).state()).isEqualTo("FAILED");
    }
    @Test void sharesChannelOrderingAndConfigChangeCancelsUnsentMessages() {
        var c=config(); long expiry=System.currentTimeMillis()+600_000;
        String a=store.create(c,"a","DAILY","1",expiry), b=store.create(c,"b","TEST","",expiry);
        store.ready(a,"{}",List.of("summary","member")); store.ready(b,"{}",List.of("test"));
        var first=store.claim(batch(a),System.currentTimeMillis());
        assertThat(first).isNotNull(); assertThat(store.claim(batch(b),System.currentTimeMillis()+5000)).isNull();
        store.complete(first,new WecomWebhook.Result(WecomWebhook.Outcome.ACCEPTED,"ok"));
        store.save(id,10,100,new WorklogNotificationModule.Command(false,1,null,false),webhook);
        assertThat(jdbc.queryForList("SELECT state FROM "+NotificationStore.B+" WHERE id IN (?,?)",String.class,a,b)).containsOnly("CANCELLED");
        assertThat(store.claim(new NotificationStore.Batch(a,id,10,"DAILY",1,c.hash(),"1","READY","",2,0,expiry),System.currentTimeMillis()+5000)).isNull();
    }
    @Test void recoveredInFlightMessageRemainsUnknownAfterSendWindowAndTestRevisionIsChecked() {
        var c=config();
        assertThatThrownBy(()->store.createTest(c,"",0,100)).hasMessageContaining("已变化");
        String test=store.createTest(c,"",1,100);
        assertThatThrownBy(()->store.createTest(c,"",1,100)).hasMessageContaining("处理中");
        store.ready(test,"{}",List.of("test"));
        var b=batch(test); assertThat(store.claim(b,System.currentTimeMillis())).isNotNull();
        jdbc.update("UPDATE "+NotificationStore.B+" SET expires_at=0 WHERE id=?",test);
        jdbc.update("UPDATE "+NotificationStore.M+" SET updated_at=0 WHERE batch_id=?",test);
        store.recoverUnknown(System.currentTimeMillis());
        assertThat(store.latest(id).state()).isEqualTo("UNKNOWN");
    }

    @Test void manualCreationIsIdempotentConcurrentAndIndependentFromDailyKey() throws Exception {
        var c=config();
        var disabled=store.save(id,10,100,new WorklogNotificationModule.Command(false,1,null,false),webhook);
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            var tasks=new ArrayList<Callable<NotificationStore.ManualBatch>>();
            for(int i=0;i<8;i++) tasks.add(()->new NotificationStore(jdbc,tx).createManual(disabled,"1,2",2,100,"manual-request-0001"));
            var results=pool.invokeAll(tasks).stream().map(f->{try{return f.get();}catch(Exception e){throw new RuntimeException(e);}}).toList();
            assertThat(results.stream().filter(NotificationStore.ManualBatch::created).count()).isEqualTo(1);
            assertThat(results.stream().map(NotificationStore.ManualBatch::id).distinct()).hasSize(1);
            assertThat(store.config(id).enabled()).isFalse();
            var manual=batch(results.get(0).id());assertThat(manual.kind()).isEqualTo("MANUAL");
            assertThat(manual.expiresAt()-manual.createdAt()).isBetween(1_790_000L,1_810_000L);
            assertThatThrownBy(()->store.createManual(disabled,"1,2",2,100,"manual-request-0002")).hasMessageContaining("正在采集或发送");
            assertThat(store.create(disabled,"WORKLOG_DAILY_REPORT:2026-11-13","DAILY","1,2",System.currentTimeMillis()+600_000)).isNotNull();
        } finally {pool.shutdownNow();}
    }
    @Test void manualTriggerChecksSavedRevisionAdministratorActiveDailyReportAndCooldown() {
        var c=config();
        assertThatThrownBy(()->store.createManual(c,"1",0,100,"manual-request-0001")).hasMessageContaining("已变化");
        assertThatThrownBy(()->store.createManual(c,"1",1,200,"manual-request-0001")).hasMessageContaining("管理员");
        assertThatThrownBy(()->store.createManual(c,"1",1,100,"bad")).hasMessageContaining("标识不正确");
        String daily=store.create(c,"WORKLOG_DAILY_REPORT:2026-11-13","DAILY","1",System.currentTimeMillis()+600_000);
        assertThatThrownBy(()->store.createManual(c,"1",1,100,"manual-request-0001")).hasMessageContaining("正在采集或发送");
        store.stop(daily,"FAILED","fixture");
        var manual=store.createManual(c,"1",1,100,"manual-request-0001");
        store.stop(manual.id(),"FAILED","fixture");
        assertThatThrownBy(()->store.createManual(c,"1",1,100,"manual-request-0002")).hasMessageContaining("刚刚已触发");
        jdbc.update("UPDATE "+NotificationStore.B+" SET created_at=created_at-11000 WHERE id=?",manual.id());
        assertThat(store.createManual(c,"1",1,100,"manual-request-0002").created()).isTrue();
    }

    @Test void persistsPhoneMentionsAndRejectsSummaryTargetsOutsideTeam() {
        jdbc.update("INSERT INTO external_zhaogang_workbench_team_member VALUES (?,100)", id);
        var initial = config();
        assertThatThrownBy(() -> store.save(id, 10, 100,
                new WorklogNotificationModule.Command(true, initial.revision(), null, false, 999L), webhook))
                .hasMessageContaining("当前团队成员");
        assertThat(store.config(id).revision()).isEqualTo(initial.revision());
        var selected = store.save(id, 10, 100,
                new WorklogNotificationModule.Command(true, initial.revision(), null, false, 100L), webhook);
        assertThat(new NotificationStore(jdbc, tx).config(id).summaryMemberId()).isEqualTo(100L);
        String batchId = store.create(selected, "mobile", "MANUAL", "100", System.currentTimeMillis()+600_000);
        store.readyMessages(batchId, "{}", List.of(new NotificationMessage("text", "团队汇总", List.of("13800000001")), NotificationMessage.text("后续明细")));
        var claimed = new NotificationStore(jdbc, tx).claim(batch(batchId), System.currentTimeMillis());
        assertThat(claimed.message()).isEqualTo(new NotificationMessage("text", "团队汇总", List.of("13800000001")));
    }
    @Test void draftTestPreservesSavedConfigurationReservesChannelAndRecoversUnknown() {
        var saved = config();
        var draft = store.beginTest(id, 10, 100, saved.revision(), "draft-" + id, NotificationMessage.text("test"));
        assertThat(store.config(id).cipher()).isEqualTo(saved.cipher());
        assertThat(store.config(id).revision()).isEqualTo(saved.revision());
        assertThat(store.latest(id).state()).isEqualTo("TESTING");
        assertThatThrownBy(() -> store.beginTest(id, 10, 100, saved.revision(), "draft-" + id, NotificationMessage.text("test"))).hasMessageContaining("处理中");
        String report = store.create(saved, "report", "MANUAL", "100", System.currentTimeMillis()+600_000);
        store.ready(report, "{}", List.of("report"));
        var sameChannel = new NotificationStore.Batch(report, id, 10, "MANUAL", saved.revision(), "draft-" + id, "100", "READY", "", 1, 1, Long.MAX_VALUE);
        assertThat(store.claim(sameChannel, System.currentTimeMillis()+5000)).isNull();
        jdbc.update("UPDATE " + NotificationStore.M + " SET updated_at=0 WHERE batch_id=?", draft.batch().id());
        store.recoverUnknown(System.currentTimeMillis());
        assertThat(store.recent(id)).filteredOn(b -> b.id().equals(draft.batch().id())).singleElement()
                .extracting(NotificationStore.Batch::state).isEqualTo("UNKNOWN");
    }
    @Test void draftTestSupportsFirstConfigurationAndHistoryIsBoundedAndOrdered() {
        assertThatThrownBy(() -> store.beginTest(id, 10, 200, 0, "hash", NotificationMessage.text("test"))).hasMessageContaining("管理员");
        assertThatThrownBy(() -> store.beginTest(id, 10, 100, 99, "hash", NotificationMessage.text("test"))).hasMessageContaining("已变化");
        var delivery = store.beginTest(id, 10, 100, 0, "new-" + id, NotificationMessage.text("test"));
        store.complete(delivery, new WecomWebhook.Result(WecomWebhook.Outcome.ACCEPTED, "accepted"));
        assertThat(store.config(id).cipher()).isNull();
        assertThat(store.config(id).enabled()).isFalse();
        assertThat(store.config(id).revision()).isZero();
        assertThat(store.latest(id).state()).isEqualTo("ACCEPTED");
        var configured = config();
        var created = new ArrayList<String>();
        for (int i=0; i<4; i++) {
            String batchId = store.create(configured, "history-" + i, "MANUAL", "", 100_000);
            jdbc.update("UPDATE " + NotificationStore.B + " SET created_at=? WHERE id=?", System.currentTimeMillis() + 1000*(i+1), batchId);
            created.add(batchId);
        }
        assertThat(store.recent(id)).extracting(NotificationStore.Batch::id).containsExactly(created.get(3), created.get(2), created.get(1));
    }

}
