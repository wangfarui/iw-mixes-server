package com.itwray.iw.external.zhaogang.notification;

import com.itwray.iw.external.zhaogang.team.WorkbenchTeamException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Repository
class NotificationStore {
    static final String C = "external_zhaogang_worklog_notify_config";
    static final String B = "external_zhaogang_worklog_notify_batch";
    static final String M = "external_zhaogang_worklog_notify_message";
    private static final String BATCH_COLUMNS = "id,team_id,coding_team_id,kind,revision,webhook_hash,member_ids,state,reason,total_count,created_at,expires_at";
    static final String H = "external_zhaogang_worklog_notify_channel";
    record Config(long teamId, long codingTeamId, boolean enabled, String cipher, String hash, long revision) { }
    record Batch(String id, long teamId, long codingTeamId, String kind, long revision, String hash,
                 String members, String state, String reason, int total, long createdAt, long expiresAt) { }
    record ManualBatch(String id, boolean created) { }
    record Delivery(Batch batch, int sequence, String content, int attempts) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    NotificationStore(JdbcTemplate jdbc, TransactionTemplate tx) { this.jdbc = jdbc; this.tx = tx; }

    Config config(long id) {
        return jdbc.query("SELECT * FROM " + C + " WHERE team_id=?", (r,n) -> configRow(r), id).stream().findFirst().orElse(null);
    }
    List<Config> enabled() { return jdbc.query("SELECT * FROM " + C + " WHERE enabled=1", (r,n) -> configRow(r)); }
    Config save(long id, long codingTeamId, long actor, WorklogNotificationModule.Command command, WecomWebhook webhook) {
        return tx.execute(status -> {
            authorize(id, codingTeamId, actor);
            jdbc.update("INSERT INTO " + C + " (team_id,coding_team_id) VALUES (?,?) ON DUPLICATE KEY UPDATE team_id=team_id", id, codingTeamId);
            Config old = jdbc.queryForObject("SELECT * FROM " + C + " WHERE team_id=? FOR UPDATE", (r,n) -> configRow(r), id);
            if (old.revision() != command.revision()) throw new WorkbenchTeamException("通知配置已变化，请重新加载后保存");
            boolean clear = command.clearWebhook();
            if (clear && command.webhook() != null && !command.webhook().isBlank()) throw new WorkbenchTeamException("清除与更换 Webhook 不能同时操作");
            String cipher = clear ? null : old.cipher();
            String hash = clear ? null : old.hash();
            if (!clear && command.webhook() != null && !command.webhook().isBlank()) {
                String url = WecomWebhook.validate(command.webhook());
                cipher = webhook.encrypt(url);
                hash = com.itwray.iw.external.zhaogang.credential.CodingCredentialService.fingerprint(url);
            }
            boolean enabled = !clear && command.enabled();
            if (enabled && cipher == null) throw new WorkbenchTeamException("请先保存群 Webhook");
            long now = System.currentTimeMillis();
            jdbc.update("UPDATE " + C + " SET enabled=?,webhook_cipher=?,webhook_hash=?,revision=revision+1,updated_by=?,updated_at=? WHERE team_id=?", enabled, cipher, hash, actor, now, id);
            // Stop only unsent messages. An already submitted HTTP request cannot be recalled.
            jdbc.update("UPDATE " + B + " SET state='CANCELLED',reason='通知配置已更新',updated_at=? WHERE team_id=? AND state IN ('COLLECTING','READY')", now, id);
            return config(id);
        });
    }
    Batch latest(long teamId) {
        return jdbc.query("SELECT " + BATCH_COLUMNS + " FROM " + B + " WHERE team_id=? ORDER BY created_at DESC,id DESC LIMIT 1", (r,n)->batchRow(r), teamId).stream().findFirst().orElse(null);
    }
    int accepted(String batchId) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + M + " WHERE batch_id=? AND state='ACCEPTED'", Integer.class, batchId); }

    String create(Config config, String key, String kind, String members, long expiresAt) {
        String id = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
        try {
            int count = jdbc.update("INSERT INTO " + B + " (id,team_id,coding_team_id,batch_key,kind,revision,webhook_hash,member_ids,state,created_at,expires_at,updated_at) VALUES (?,?,?,?,?,?,?,?,'COLLECTING',?,?,?)",
                    id, config.teamId(), config.codingTeamId(), key, kind, config.revision(), config.hash(), members, now, expiresAt, now);
            return count == 1 ? id : null;
        } catch (org.springframework.dao.DuplicateKeyException ignored) { return null; }
    }
    String createTest(Config expected, String members, long revision, long actor) {
        return tx.execute(status -> {
            authorize(expected.teamId(), expected.codingTeamId(), actor);
            Config current = jdbc.queryForObject("SELECT * FROM " + C + " WHERE team_id=? FOR UPDATE", (r,n)->configRow(r), expected.teamId());
            if (current.revision() != revision || current.cipher() == null) throw new WorkbenchTeamException("通知配置已变化或尚未配置 Webhook，请重新加载");
            long now = System.currentTimeMillis();
            int changed = jdbc.update("UPDATE " + C + " SET last_test_at=? WHERE team_id=? AND last_test_at<=?", now, current.teamId(), now-10_000);
            if (changed != 1 || jdbc.queryForObject("SELECT COUNT(*) FROM " + B + " WHERE team_id=? AND kind='TEST' AND state IN ('COLLECTING','READY')", Integer.class, current.teamId()) > 0)
                throw new WorkbenchTeamException("测试发送处理中，请稍后再试");
            return create(current, "TEST:" + UUID.randomUUID(), "TEST", members, now + 600_000);
        });
    }
    ManualBatch createManual(Config expected, String members, long revision, long actor, String requestId) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{8,64}"))
            throw new WorkbenchTeamException("立即发送请求标识不正确，请刷新后重试");
        return tx.execute(status -> {
            authorize(expected.teamId(), expected.codingTeamId(), actor);
            Config current = jdbc.queryForObject("SELECT * FROM " + C + " WHERE team_id=? FOR UPDATE", (r,n)->configRow(r), expected.teamId());
            if (current.revision() != revision || current.cipher() == null)
                throw new WorkbenchTeamException("通知配置已变化或尚未配置 Webhook，请重新加载");
            String key = "MANUAL:" + requestId;
            var existing = jdbc.queryForList("SELECT id FROM " + B + " WHERE team_id=? AND batch_key=?", String.class, current.teamId(), key);
            if (!existing.isEmpty()) return new ManualBatch(existing.get(0), false);
            if (jdbc.queryForObject("SELECT COUNT(*) FROM " + B + " WHERE team_id=? AND kind IN ('MANUAL','DAILY') AND state IN ('COLLECTING','READY')", Integer.class, current.teamId()) > 0)
                throw new WorkbenchTeamException("团队工时报告正在采集或发送，请稍后再试");
            long now = System.currentTimeMillis();
            if (jdbc.queryForObject("SELECT COUNT(*) FROM " + B + " WHERE team_id=? AND kind='MANUAL' AND created_at>?", Integer.class, current.teamId(), now-10_000) > 0)
                throw new WorkbenchTeamException("刚刚已触发工时报告，请稍后再试");
            return new ManualBatch(create(current, key, "MANUAL", members, now + 1_800_000), true);
        });
    }
    void ready(String id, String snapshot, List<String> messages) {
        tx.executeWithoutResult(status -> {
            var state = jdbc.queryForObject("SELECT state FROM " + B + " WHERE id=? FOR UPDATE", String.class, id);
            if (!"COLLECTING".equals(state)) return;
            long now = System.currentTimeMillis();
            for (int i=0;i<messages.size();i++) jdbc.update("INSERT INTO " + M + " (batch_id,sequence_no,content,updated_at) VALUES (?,?,?,?)", id, i, messages.get(i), now);
            jdbc.update("UPDATE " + B + " SET snapshot_json=?,total_count=?,state='READY',updated_at=? WHERE id=?", snapshot, messages.size(), now, id);
        });
    }
    List<Batch> pending() { return jdbc.query("SELECT " + BATCH_COLUMNS + " FROM " + B + " WHERE state IN ('COLLECTING','READY') ORDER BY created_at,id", (r,n)->batchRow(r)); }
    void stop(String id, String state, String reason) {
        jdbc.update("UPDATE " + B + " SET state=?,reason=?,updated_at=? WHERE id=? AND state IN ('COLLECTING','READY')", state, reason, System.currentTimeMillis(), id);
    }
    Delivery claim(Batch batch, long now) {
        return tx.execute(status -> {
            jdbc.update("INSERT INTO " + H + " (webhook_hash) VALUES (?) ON DUPLICATE KEY UPDATE webhook_hash=webhook_hash", batch.hash());
            var channel = jdbc.queryForMap("SELECT active_batch,next_at FROM " + H + " WHERE webhook_hash=? FOR UPDATE", batch.hash());
            String active = (String) channel.get("active_batch");
            if (active != null && !active.equals(batch.id())) {
                String state = jdbc.queryForObject("SELECT state FROM " + B + " WHERE id=?", String.class, active);
                if (Set.of("COLLECTING", "READY").contains(state)) return null;
            }
            jdbc.update("UPDATE " + H + " SET active_batch=? WHERE webhook_hash=?", batch.id(), batch.hash());
            if (((Number)channel.get("next_at")).longValue() > now) return null;
            String state = jdbc.queryForObject("SELECT state FROM " + B + " WHERE id=? FOR UPDATE", String.class, batch.id());
            if (!"READY".equals(state)) return null;
            var messages = jdbc.queryForList("SELECT * FROM " + M + " WHERE batch_id=? AND state<>'ACCEPTED' ORDER BY sequence_no LIMIT 1 FOR UPDATE", batch.id());
            if (messages.isEmpty()) { stop(batch.id(), "ACCEPTED", "企微已受理全部消息"); return null; }
            var m = messages.get(0);
            if ("SENDING".equals(m.get("state"))) {
                if (((Number)m.get("updated_at")).longValue() < now - 60_000) {
                    jdbc.update("UPDATE " + M + " SET state='UNKNOWN',updated_at=? WHERE batch_id=? AND sequence_no=?", now, batch.id(), m.get("sequence_no"));
                    stop(batch.id(), "UNKNOWN", "发送进程中断，投递结果未知，已停止自动续发");
                }
                return null;
            }
            if (!"WAITING".equals(m.get("state")) || ((Number)m.get("next_at")).longValue() > now) return null;
            int sequence = ((Number)m.get("sequence_no")).intValue();
            int attempts = ((Number)m.get("attempts")).intValue() + 1;
            jdbc.update("UPDATE " + M + " SET state='SENDING',attempts=?,updated_at=? WHERE batch_id=? AND sequence_no=?", attempts, now, batch.id(), sequence);
            jdbc.update("UPDATE " + H + " SET next_at=? WHERE webhook_hash=?", now+4000, batch.hash());
            return new Delivery(batch, sequence, (String)m.get("content"), attempts);
        });
    }
    void complete(Delivery d, WecomWebhook.Result result) {
        tx.executeWithoutResult(status -> {
            long now = System.currentTimeMillis();
            // Reserve spacing after completion too; slow HTTP requests never allow concurrent next sends.
            jdbc.update("UPDATE " + H + " SET next_at=GREATEST(next_at,?) WHERE webhook_hash=?", now+4000, d.batch().hash());
            String state = switch (result.outcome()) {
                case ACCEPTED -> "ACCEPTED";
                case RETRYABLE -> d.attempts() < 3 ? "WAITING" : "FAILED";
                case FAILED -> "FAILED";
                case UNKNOWN -> "UNKNOWN";
            };
            jdbc.update("UPDATE " + M + " SET state=?,next_at=?,updated_at=? WHERE batch_id=? AND sequence_no=? AND state='SENDING'", state, now+60_000, now, d.batch().id(), d.sequence());
            if ("FAILED".equals(state) || "UNKNOWN".equals(state)) stop(d.batch().id(), state, result.reason());
            else if ("ACCEPTED".equals(state) && accepted(d.batch().id()) == d.batch().total()) stop(d.batch().id(), "ACCEPTED", "企微已受理全部消息");
        });
    }
    void recoverUnknown(long now) {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE " + B + " b INNER JOIN " + M + " m ON b.id=m.batch_id SET b.state='UNKNOWN',b.reason='发送进程中断，投递结果未知，已停止自动续发',b.updated_at=? WHERE b.state='READY' AND m.state='SENDING' AND m.updated_at<?", now, now-60_000);
            jdbc.update("UPDATE " + M + " m INNER JOIN " + B + " b ON b.id=m.batch_id SET m.state='UNKNOWN',m.updated_at=? WHERE b.state='UNKNOWN' AND m.state='SENDING' AND m.updated_at<?", now, now-60_000);
        });
    }
    void cleanup(long now) {
        // Keep sending/unknown state for at least 30 days; remove children before their batch.
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE m FROM " + M + " m INNER JOIN " + B + " b ON b.id=m.batch_id WHERE b.created_at<? AND b.state NOT IN ('COLLECTING','READY')", now-30L*86400_000);
            jdbc.update("UPDATE " + H + " h LEFT JOIN " + B + " b ON b.id=h.active_batch SET h.active_batch=NULL WHERE b.id IS NULL OR b.state NOT IN ('COLLECTING','READY')");
            jdbc.update("DELETE FROM " + B + " WHERE created_at<? AND state NOT IN ('COLLECTING','READY')", now-30L*86400_000);
        });
    }
    private void authorize(long teamId, long codingTeamId, long actor) {
        var rows = jdbc.queryForList("SELECT coding_team_id,administrator_user_id FROM external_zhaogang_workbench_team WHERE id=? FOR UPDATE", teamId);
        if (rows.isEmpty() || ((Number)rows.get(0).get("coding_team_id")).longValue()!=codingTeamId
                || ((Number)rows.get(0).get("administrator_user_id")).longValue()!=actor)
            throw new WorkbenchTeamException("团队不存在或当前用户已不是管理员");
    }
    private Config configRow(ResultSet r) throws SQLException { return new Config(r.getLong("team_id"),r.getLong("coding_team_id"),r.getBoolean("enabled"),r.getString("webhook_cipher"),r.getString("webhook_hash"),r.getLong("revision")); }
    private Batch batchRow(ResultSet r) throws SQLException { return new Batch(r.getString("id"),r.getLong("team_id"),r.getLong("coding_team_id"),r.getString("kind"),r.getLong("revision"),r.getString("webhook_hash"),r.getString("member_ids"),r.getString("state"),r.getString("reason"),r.getInt("total_count"),r.getLong("created_at"),r.getLong("expires_at")); }
}
