package com.itwray.iw.external.zhaogang.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
class SkillLibrary {
    private static final Logger LOG = LoggerFactory.getLogger(SkillLibrary.class);
    private static final String SKILLS = "external_zhaogang_skill";
    private static final String VERSIONS = "external_zhaogang_skill_version";
    private static final String DRAFTS = "external_zhaogang_skill_draft";
    private static final String EVENTS = "external_zhaogang_skill_event";
    private static final String VERSION_ORDER = " ORDER BY major_no DESC, minor_no DESC, patch_no DESC";
    private static final String SKILL_FIELDS = "id, machine_name, creator_team, creator_user, current_version, revision";
    private static final RowMapper<Skill> SKILL_ROW = (r, i) -> new Skill(r.getString("id"), r.getString("machine_name"),
            r.getLong("creator_team"), r.getLong("creator_user"), r.getString("current_version"), r.getLong("revision"));
    private static final RowMapper<Version> VERSION_ROW = (r, i) -> new Version(r.getString("version"), r.getString("title"),
            r.getString("summary"), r.getString("notes"), r.getString("sha256"), r.getString("actor_name"),
            r.getTimestamp("created_at").toInstant(), r.getTimestamp("deleted_at") != null);
    private final JdbcTemplate jdbc;
    private final SkillPackageStore store;
    private final TransactionTemplate transactions;

    record Actor(long team, long user, String name, String source) {}
    record Skill(String id, String machineName, long team, long creator, String currentVersion, long revision) {
        boolean ownedBy(Actor actor) { return team == actor.team() && creator == actor.user(); }
    }
    record Entry(String id, String machineName, String currentVersion, long revision, boolean creator,
                 String title, String summary, String updatedBy, Instant updatedAt) {}
    record Version(String version, String title, String summary, String notes, String sha256, String publishedBy,
                   Instant publishedAt, boolean deleted) {}
    record Event(String action, String actor, String source, String previousVersion, String currentVersion,
                 String targetVersion, String reason, Instant at) {}
    record Detail(Entry skill, List<Version> versions, List<Event> events) {}
    record Difference(String before, String after, List<SkillPackage.Change> files) {}
    record Preview(String draftId, String skillId, Long revision, String machineName, String description,
                   String sha256, String baseVersion, Difference difference) {}
    record Publish(String draftId, String title, String summary, String notes, String bump) {}
    record Published(String skillId, String version, String sha256) {}
    record Mutation(Long revision, String reason) {}
    record Download(String fileName, String sha256, byte[] bytes) {}
    private record Draft(long team, long user, String skillId, Long revision, String name, String sha256,
                         Instant expires, String publishedSkill, String publishedVersion) {}
    private record Numbers(int major, int minor, int patch) {
        static Numbers parse(String version) {
            var parts = Arrays.stream(version.split("\\.")).mapToInt(Integer::parseInt).toArray();
            return new Numbers(parts[0], parts[1], parts[2]);
        }
        Numbers next(String bump) {
            return switch (bump) {
                case "major" -> new Numbers(Math.addExact(major, 1), 0, 0);
                case "minor" -> new Numbers(major, Math.addExact(minor, 1), 0);
                case "patch" -> new Numbers(major, minor, Math.addExact(patch, 1));
                default -> throw SkillProblem.bad("无效的版本升级方式");
            };
        }
        public String toString() { return major + "." + minor + "." + patch; }
    }

    SkillLibrary(JdbcTemplate jdbc, SkillPackageStore store, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.store = store;
        this.transactions = transactions;
    }

    List<Entry> list(Actor actor, String query) {
        String pattern = "%" + query.strip().toLowerCase(java.util.Locale.ROOT) + "%";
        return jdbc.query("""
                SELECT s.id, s.machine_name, s.current_version, s.revision, s.creator_team, s.creator_user,
                       v.title, v.summary, COALESCE(e.actor_name,v.actor_name) updated_by,
                       COALESCE(e.created_at,v.created_at) updated_at
                FROM external_zhaogang_skill s
                JOIN external_zhaogang_skill_version v ON v.skill_id=s.id AND v.version=s.current_version
                LEFT JOIN external_zhaogang_skill_event e ON e.id=(
                    SELECT MAX(x.id) FROM external_zhaogang_skill_event x WHERE x.skill_id=s.id
                )
                WHERE s.deleted_at IS NULL
                  AND (LOWER(s.machine_name) LIKE ? OR LOWER(v.title) LIKE ? OR LOWER(v.summary) LIKE ?)
                ORDER BY updated_at DESC, s.id LIMIT 200
                """, (r, i) -> new Entry(r.getString("id"), r.getString("machine_name"), r.getString("current_version"),
                r.getLong("revision"), r.getLong("creator_team") == actor.team() && r.getLong("creator_user") == actor.user(),
                r.getString("title"), r.getString("summary"), r.getString("updated_by"),
                r.getTimestamp("updated_at").toInstant()), pattern, pattern, pattern);
    }

    @Transactional(readOnly = true)
    Detail detail(Actor actor, String id) {
        Skill skill = skill(id, false);
        List<Version> versions = jdbc.query("SELECT * FROM " + VERSIONS + " WHERE skill_id=?" + VERSION_ORDER, VERSION_ROW, id);
        Version current = versions.stream().filter(v -> v.version().equals(skill.currentVersion())).findFirst()
                .orElseThrow(SkillProblem::missing);
        List<Event> events = jdbc.query("SELECT * FROM " + EVENTS + " WHERE skill_id=? ORDER BY id DESC",
                (r, i) -> new Event(r.getString("action"), r.getString("actor_name"), r.getString("source"),
                        r.getString("previous_version"), r.getString("current_version"), r.getString("target_version"),
                        r.getString("reason"), r.getTimestamp("created_at").toInstant()), id);
        Event last = events.isEmpty() ? null : events.get(0);
        return new Detail(new Entry(id, skill.machineName(), skill.currentVersion(), skill.revision(), skill.ownedBy(actor),
                current.title(), current.summary(), last == null ? current.publishedBy() : last.actor(),
                last == null ? current.publishedAt() : last.at()), versions, events);
    }

    Preview preview(Actor actor, String id, Long revision, byte[] bytes) {
        SkillPackage.Inspection next = SkillPackage.inspect(bytes);
        Skill current = id == null ? null : skill(id, false);
        if (current == null) {
            if (!jdbc.queryForList("SELECT id FROM " + SKILLS + " WHERE active_name=?", String.class, next.name()).isEmpty()) {
                throw SkillProblem.conflict("已有同名 Skill，请从该条目的详情页发起更新");
            }
        } else {
            checkRevision(current, revision);
            if (!current.machineName().equals(next.name())) throw SkillProblem.bad("ZIP 的 Skill 名称与目标条目不一致");
        }
        SkillPackage.Inspection before = current == null ? null : inspectVersion(id, current.currentVersion());
        String draft = UUID.randomUUID().toString();
        String hash = SkillPackage.sha256(bytes);
        Instant expires = Instant.now().plus(1, ChronoUnit.HOURS);
        jdbc.update("INSERT INTO " + DRAFTS
                        + " (id,actor_team,actor_user,skill_id,base_revision,machine_name,sha256,expires_at) VALUES (?,?,?,?,?,?,?,?)",
                draft, actor.team(), actor.user(), id, revision, next.name(), hash, Timestamp.from(expires));
        try {
            store.put(draft, bytes);
        } catch (SkillProblem problem) {
            jdbc.update("UPDATE " + DRAFTS + " SET expires_at=CURRENT_TIMESTAMP WHERE id=?", draft);
            throw problem;
        }
        return new Preview(draft, id, revision, next.name(), next.description(), hash,
                current == null ? null : current.currentVersion(), difference(before, next));
    }

    @Transactional
    Published publish(Actor actor, Publish command) {
        Draft draft = lockDraft(command.draftId());
        if (draft.team() != actor.team() || draft.user() != actor.user()) throw SkillProblem.missing();
        if (draft.publishedSkill() != null) return new Published(draft.publishedSkill(), draft.publishedVersion(), draft.sha256());
        if (!draft.expires().isAfter(Instant.now())) throw SkillProblem.conflict("上传预览已过期，请重新上传");
        String title = required(command.title(), 120, "展示名");
        String summary = required(command.summary(), 500, "简介");
        String notes = required(command.notes(), 2000, "发布说明");
        boolean create = draft.skillId() == null;
        Skill current;
        Numbers version;
        if (create) {
            String id = UUID.randomUUID().toString();
            try {
                jdbc.update("INSERT INTO " + SKILLS + " (id,machine_name,active_name,creator_team,creator_user) VALUES (?,?,?,?,?)",
                        id, draft.name(), draft.name(), actor.team(), actor.user());
            } catch (DuplicateKeyException error) {
                throw SkillProblem.conflict("已有同名 Skill，请从该条目的详情页发起更新");
            }
            current = skill(id, true);
            version = new Numbers(1, 0, 0);
        } else {
            current = skill(draft.skillId(), true);
            checkRevision(current, draft.revision());
            String highest = jdbc.queryForObject("SELECT version FROM " + VERSIONS
                    + " WHERE skill_id=?" + VERSION_ORDER + " LIMIT 1", String.class, current.id());
            version = Numbers.parse(highest).next(command.bump() == null ? "patch" : command.bump());
        }
        jdbc.update("INSERT INTO " + VERSIONS + """
                 (skill_id,version,major_no,minor_no,patch_no,title,summary,notes,package_id,sha256,actor_name)
                 VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, current.id(), version.toString(), version.major(), version.minor(), version.patch(),
                title, summary, notes, command.draftId(), draft.sha256(), actor.name());
        setCurrent(current, version.toString());
        event(actor, current, create ? "CREATE" : "UPDATE", version.toString(), version.toString(), "");
        jdbc.update("UPDATE " + DRAFTS + " SET published_skill_id=?, published_version=? WHERE id=?",
                current.id(), version.toString(), command.draftId());
        return new Published(current.id(), version.toString(), draft.sha256());
    }

    Difference compare(String id, String from, String to) {
        return difference(inspectVersion(id, from), inspectVersion(id, to));
    }

    Download download(String id, String version) {
        Skill skill = skill(id, false);
        var keys = jdbc.query("SELECT package_id,sha256 FROM " + VERSIONS
                        + " WHERE skill_id=? AND version=? AND deleted_at IS NULL",
                (r, i) -> new String[]{r.getString(1), r.getString(2)}, id, version);
        if (keys.isEmpty()) throw SkillProblem.missing();
        String[] reference = keys.get(0);
        byte[] bytes = store.get(reference[0]);
        if (!SkillPackage.sha256(bytes).equals(reference[1])) throw new SkillProblem(503, "Skill 包完整性校验失败");
        return new Download(skill.machineName() + "-" + version + ".zip", reference[1], bytes);
    }

    @Transactional
    void rollback(Actor actor, String id, String version, Mutation mutation) {
        Skill current = skill(id, true);
        checkRevision(current, mutation.revision());
        if (current.currentVersion().equals(version)) throw SkillProblem.bad("该版本已是当前版本");
        if (jdbc.queryForList("SELECT version FROM " + VERSIONS + " WHERE skill_id=? AND version=? AND deleted_at IS NULL",
                String.class, id, version).isEmpty()) throw SkillProblem.missing();
        setCurrent(current, version);
        event(actor, current, "ROLLBACK", version, version, required(mutation.reason(), 500, "回退原因"));
    }

    @Transactional
    void deleteVersion(Actor actor, String id, String version, Mutation mutation) {
        Skill current = requireCreator(actor, id, mutation.revision());
        List<String> active = jdbc.queryForList("SELECT version FROM " + VERSIONS
                + " WHERE skill_id=? AND deleted_at IS NULL" + VERSION_ORDER, String.class, id);
        if (!active.contains(version)) throw SkillProblem.missing();
        if (active.size() == 1) throw SkillProblem.conflict("最后一个版本只能通过删除整个 Skill 移除");
        String next = current.currentVersion().equals(version)
                ? active.stream().filter(v -> !v.equals(version)).findFirst().orElseThrow()
                : current.currentVersion();
        deleteVersions(id, version);
        setCurrent(current, next);
        event(actor, current, "DELETE_VERSION", next, version, required(mutation.reason(), 500, "删除原因"));
    }

    @Transactional
    void deleteSkill(Actor actor, String id, Mutation mutation) {
        Skill current = requireCreator(actor, id, mutation.revision());
        deleteVersions(id, null);
        jdbc.update("UPDATE " + SKILLS + " SET current_version=NULL, active_name=NULL, deleted_at=CURRENT_TIMESTAMP, revision=revision+1 WHERE id=?", id);
        event(actor, current, "DELETE_SKILL", null, null, required(mutation.reason(), 500, "删除原因"));
    }

    private void deleteVersions(String id, String version) {
        String sql = "UPDATE " + VERSIONS
                + " SET deleted_at=CURRENT_TIMESTAMP, title='', summary='', notes='' WHERE skill_id=? AND deleted_at IS NULL";
        if (version == null) jdbc.update(sql, id);
        else jdbc.update(sql + " AND version=?", id, version);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void purge() {
        if (!store.enabled()) return;
        for (String id : jdbc.queryForList("SELECT package_id FROM " + VERSIONS
                + " WHERE deleted_at IS NOT NULL AND purged_at IS NULL LIMIT 40", String.class)) {
            try {
                store.delete(id);
                jdbc.update("UPDATE " + VERSIONS + " SET purged_at=CURRENT_TIMESTAMP WHERE package_id=?", id);
            } catch (SkillProblem error) {
                LOG.warn("Skills 版本制品待重试清理，packageId={}", id);
            }
        }
        for (String id : jdbc.queryForList("SELECT id FROM " + DRAFTS
                + " WHERE published_skill_id IS NULL AND expires_at<=CURRENT_TIMESTAMP LIMIT 40", String.class)) {
            try {
                transactions.executeWithoutResult(status -> {
                    List<String> expired = jdbc.queryForList("SELECT id FROM " + DRAFTS
                            + " WHERE id=? AND published_skill_id IS NULL AND expires_at<=CURRENT_TIMESTAMP FOR UPDATE", String.class, id);
                    if (!expired.isEmpty()) {
                        store.delete(id);
                        jdbc.update("DELETE FROM " + DRAFTS + " WHERE id=?", id);
                    }
                });
            } catch (SkillProblem error) {
                LOG.warn("Skills 草稿制品待重试清理，draftId={}", id);
            }
        }
    }

    private Skill requireCreator(Actor actor, String id, Long revision) {
        Skill skill = skill(id, true);
        if (!skill.ownedBy(actor)) throw new SkillProblem(403, "只有 Skill 创建者可以删除");
        checkRevision(skill, revision);
        return skill;
    }

    private Skill skill(String id, boolean lock) {
        return jdbc.query("SELECT " + SKILL_FIELDS + " FROM " + SKILLS + " WHERE id=? AND deleted_at IS NULL"
                + (lock ? " FOR UPDATE" : ""), SKILL_ROW, id).stream().findFirst().orElseThrow(SkillProblem::missing);
    }

    private Draft lockDraft(String id) {
        return jdbc.query("SELECT * FROM " + DRAFTS + " WHERE id=? FOR UPDATE",
                (r, i) -> new Draft(r.getLong("actor_team"), r.getLong("actor_user"), r.getString("skill_id"),
                        r.getObject("base_revision", Long.class), r.getString("machine_name"), r.getString("sha256"),
                        r.getTimestamp("expires_at").toInstant(), r.getString("published_skill_id"), r.getString("published_version")), id)
                .stream().findFirst().orElseThrow(SkillProblem::missing);
    }

    private void setCurrent(Skill skill, String version) {
        jdbc.update("UPDATE " + SKILLS + " SET current_version=?, revision=revision+1 WHERE id=?", version, skill.id());
    }

    private void event(Actor actor, Skill skill, String action, String current, String target, String reason) {
        jdbc.update("INSERT INTO " + EVENTS
                        + " (skill_id,actor_team,actor_user,actor_name,source,action,previous_version,current_version,target_version,reason)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                skill.id(), actor.team(), actor.user(), actor.name(), actor.source(), action, skill.currentVersion(), current, target, reason);
    }

    private void checkRevision(Skill skill, Long expected) {
        if (!Objects.equals(skill.revision(), expected)) throw SkillProblem.conflict("当前版本已变化，请基于最新版本重新预览");
    }

    private SkillPackage.Inspection inspectVersion(String id, String version) {
        return SkillPackage.inspect(download(id, version).bytes());
    }

    private Difference difference(SkillPackage.Inspection before, SkillPackage.Inspection after) {
        return new Difference(before == null ? "" : before.markdown(), after.markdown(), SkillPackage.changes(before, after));
    }

    static String required(String value, int max, String field) {
        if (value == null || value.isBlank() || value.strip().length() > max) {
            throw SkillProblem.bad(field + "不能为空且不得超过 " + max + " 字符");
        }
        return value.strip();
    }
}
