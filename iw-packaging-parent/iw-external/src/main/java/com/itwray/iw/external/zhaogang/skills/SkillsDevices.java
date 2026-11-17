package com.itwray.iw.external.zhaogang.skills;

import com.itwray.iw.external.zhaogang.ZhaogangSession;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class SkillsDevices {
    private static final String DEVICE = "external_zhaogang_skill_device";
    private static final String PAIR = "external_zhaogang_skill_pair";
    private static final String EPOCH = "external_zhaogang_skill_device_epoch";
    private static final Duration PAIR_TTL = Duration.ofMinutes(10);
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final SkillPackageStore packages;
    private final SecureRandom random = new SecureRandom();

    record Pairing(String code, String secret, long expiresIn) {}
    record Device(String id, String label, Instant createdAt) {}
    record Approved(String status, String token, String label) {}
    private record Pair(String code, String label, Instant expires, Long team, Long user, String name, Long epoch) {}

    SkillsDevices(JdbcTemplate jdbc, StringRedisTemplate redis, SkillPackageStore packages) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.packages = packages;
    }

    Pairing start(String label, String remoteAddress) {
        if (!packages.enabled()) throw new SkillProblem(503, "Skills 制品存储未配置");
        String limit = "zhaogang:skills-pair:" + remoteAddress + ":" + (Instant.now().getEpochSecond() / 60);
        Long count = redis.opsForValue().increment(limit);
        if (count != null && count == 1) redis.expire(limit, Duration.ofMinutes(2));
        if (count != null && count > 60) throw new SkillProblem(429, "配对请求过多，请稍后重试");
        label = SkillLibrary.required(label, 120, "设备名");
        byte[] shortCode = new byte[5];
        random.nextBytes(shortCode);
        String code = HexFormat.of().withUpperCase().formatHex(shortCode);
        String secret = token();
        jdbc.update("INSERT INTO " + PAIR + " (code,secret_hash,label,expires_at) VALUES (?,?,?,?)",
                code, hash(secret), label, Timestamp.from(Instant.now().plus(PAIR_TTL)));
        return new Pairing(code, secret, PAIR_TTL.toSeconds());
    }

    Device candidate(String code) {
        Pair pair = pair("code", normalizedCode(code), false);
        if (pair.team() != null) throw SkillProblem.conflict("配对码已确认");
        return new Device("", pair.label(), pair.expires());
    }

    @Transactional
    void approve(SkillLibrary.Actor actor, String code) {
        Pair pair = pair("code", normalizedCode(code), true);
        if (pair.team() != null) throw SkillProblem.conflict("配对码已确认");
        long epoch = lockEpoch(actor.team(), actor.user());
        jdbc.update("UPDATE " + PAIR + " SET actor_team=?,actor_user=?,actor_name=?,epoch=? WHERE code=?",
                actor.team(), actor.user(), actor.name(), epoch, pair.code());
    }

    @Transactional
    Approved claim(String secret) {
        if (secret == null || !secret.matches("[a-zA-Z0-9_-]{43}")) throw SkillProblem.missing();
        Pair pair = pair("secret_hash", hash(secret), true);
        if (pair.team() == null) return new Approved("pending", null, null);
        if (lockEpoch(pair.team(), pair.user()) != pair.epoch()) throw new SkillProblem(401, "设备配对已撤销");
        String token = "zg_sk_" + token();
        jdbc.update("INSERT INTO " + DEVICE + " (id,actor_team,actor_user,label,actor_name,token_hash) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), pair.team(), pair.user(), pair.label(), pair.name(), hash(token));
        jdbc.update("DELETE FROM " + PAIR + " WHERE code=?", pair.code());
        return new Approved("approved", token, pair.label());
    }

    SkillLibrary.Actor bearer(String authorization) {
        if (!authorization.matches("Bearer zg_sk_[a-zA-Z0-9_-]{43}")) throw unauthorized();
        return jdbc.query("SELECT actor_team,actor_user,actor_name FROM " + DEVICE + " WHERE token_hash=?",
                (r, i) -> new SkillLibrary.Actor(r.getLong(1), r.getLong(2), r.getString(3), "AGENT"),
                hash(authorization.substring(7))).stream().findFirst().orElseThrow(SkillsDevices::unauthorized);
    }

    List<Device> list(SkillLibrary.Actor actor) {
        return jdbc.query("SELECT id,label,created_at FROM " + DEVICE
                        + " WHERE actor_team=? AND actor_user=? ORDER BY created_at DESC",
                (r, i) -> new Device(r.getString(1), r.getString(2), r.getTimestamp(3).toInstant()), actor.team(), actor.user());
    }

    void revoke(SkillLibrary.Actor actor, String id) {
        jdbc.update("DELETE FROM " + DEVICE + " WHERE id=? AND actor_team=? AND actor_user=?", id, actor.team(), actor.user());
    }

    @Transactional
    public void revokeAll(ZhaogangSession session) {
        if (session.teamId() == null || session.userId() == null) return;
        lockEpoch(session.teamId(), session.userId());
        jdbc.update("UPDATE " + EPOCH + " SET epoch=epoch+1 WHERE actor_team=? AND actor_user=?", session.teamId(), session.userId());
        jdbc.update("DELETE FROM " + DEVICE + " WHERE actor_team=? AND actor_user=?", session.teamId(), session.userId());
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void cleanup() {
        if (packages.enabled()) jdbc.update("DELETE FROM " + PAIR + " WHERE expires_at<=CURRENT_TIMESTAMP");
    }

    private long lockEpoch(long team, long user) {
        jdbc.update("INSERT INTO " + EPOCH + " (actor_team,actor_user) VALUES (?,?) ON DUPLICATE KEY UPDATE epoch=epoch",
                team, user);
        return jdbc.queryForObject("SELECT epoch FROM " + EPOCH + " WHERE actor_team=? AND actor_user=? FOR UPDATE",
                Long.class, team, user);
    }

    private Pair pair(String column, String value, boolean lock) {
        return jdbc.query("SELECT * FROM " + PAIR + " WHERE " + column + "=? AND expires_at>CURRENT_TIMESTAMP"
                        + (lock ? " FOR UPDATE" : ""),
                (r, i) -> new Pair(r.getString("code"), r.getString("label"), r.getTimestamp("expires_at").toInstant(),
                        r.getObject("actor_team", Long.class), r.getObject("actor_user", Long.class), r.getString("actor_name"),
                        r.getObject("epoch", Long.class)), value)
                .stream().findFirst().orElseThrow(() -> new SkillProblem(404, "配对码不存在或已过期"));
    }

    private String normalizedCode(String code) {
        String value = code == null ? "" : code.strip().toUpperCase(java.util.Locale.ROOT);
        if (!value.matches("[0-9A-F]{10}")) throw SkillProblem.bad("配对码格式错误");
        return value;
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) { return SkillPackage.sha256(value.getBytes(StandardCharsets.UTF_8)); }

    static SkillProblem unauthorized() { return new SkillProblem(401, "请登录工作台或绑定 Skills 设备"); }
}
