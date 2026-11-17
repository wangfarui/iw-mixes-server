package com.itwray.iw.external.zhaogang.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.ZhaogangSession;
import com.itwray.iw.external.zhaogang.ZhaogangSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SkillLibraryTest {
    private static final String ENDPOINT = "/external-service/api/zhaogang/skills";
    private static final SkillLibrary.Actor OWNER = new SkillLibrary.Actor(1, 2, "创建者", "WEB");
    private static final SkillLibrary.Actor MEMBER = new SkillLibrary.Actor(3, 4, "协作者", "AGENT");
    @TempDir Path storage;
    private SkillLibrary library;
    private SkillsDevices devices;
    private TransactionTemplate tx;
    private MockMvc web;
    private ZhaogangSession ownerSession;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new FileSystemResource("scripts/iw-external-migrate-20261110-skills.sql")).execute(source);
        var jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var store = new SkillPackageStore("", "", "", "", storage.toString());
        library = new SkillLibrary(jdbc, store, tx);
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> counters = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(counters);
        when(counters.increment(anyString())).thenReturn(1L);
        devices = new SkillsDevices(jdbc, redis, store);
        ownerSession = new ZhaogangSession("test-token", 2L, "创建者", "", "test", 1L);
        var sessions = mock(ZhaogangSessionManager.class);
        when(sessions.resolve(any(), any())).thenReturn(ownerSession);
        web = MockMvcBuilders.standaloneSetup(new ZhaogangSkillsController(library, devices, sessions, store)).build();
    }

    @Test
    void versionsAreSharedButDeletionIsCreatorOnlyAndSameNameGetsNewIdentity() throws Exception {
        var first = publish(OWNER, preview(OWNER, null, "first"), "patch");
        var second = publish(MEMBER, preview(MEMBER, first.skillId(), "second"), "minor");
        var third = publish(MEMBER, preview(MEMBER, first.skillId(), "third"), "patch");
        assertEquals(List.of("1.1.1", "1.1.0", "1.0.0"), detail(first).versions().stream().map(SkillLibrary.Version::version).toList());
        assertFalse(library.detail(MEMBER, first.skillId()).skill().creator());
        assertEquals(403, assertThrows(SkillProblem.class, () -> inTx(() ->
                library.deleteVersion(MEMBER, first.skillId(), third.version(), mutation(first)))).status);
        inTx(() -> library.rollback(MEMBER, first.skillId(), first.version(), mutation(first)));
        inTx(() -> library.deleteVersion(OWNER, first.skillId(), first.version(), mutation(first)));
        assertEquals(third.version(), detail(first).skill().currentVersion());
        assertThrows(SkillProblem.class, () -> library.download(first.skillId(), first.version()));
        library.purge();
        try (var files = Files.list(storage)) { assertEquals(2, files.count()); }
        inTx(() -> library.deleteVersion(OWNER, first.skillId(), second.version(), mutation(first)));
        assertEquals(409, assertThrows(SkillProblem.class, () -> inTx(() ->
                library.deleteVersion(OWNER, first.skillId(), third.version(), mutation(first)))).status);
        inTx(() -> library.deleteSkill(OWNER, first.skillId(), mutation(first)));
        assertThrows(SkillProblem.class, () -> library.download(first.skillId(), third.version()));
        var recreated = publish(MEMBER, preview(MEMBER, null, "new owner"), "patch");
        assertNotEquals(first.skillId(), recreated.skillId());
        assertEquals("1.0.0", recreated.version());
        assertTrue(library.detail(MEMBER, recreated.skillId()).skill().creator());
        assertThrows(SkillProblem.class, () -> library.detail(OWNER, first.skillId()));
    }

    @Test
    void simultaneousDraftsCannotBothPublishAndRetryDoesNotCreateAnotherVersion() throws Exception {
        var first = publish(OWNER, preview(OWNER, null, "first"), "patch");
        var a = preview(OWNER, first.skillId(), "a");
        var b = preview(MEMBER, first.skillId(), "b");
        assertEquals(List.of("SKILL.md"), a.difference().files().stream().map(SkillPackage.Change::path).toList());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var outcomes = executor.invokeAll(List.<Callable<Integer>>of(
                    () -> publishStatus(OWNER, a), () -> publishStatus(MEMBER, b)));
            assertEquals(List.of(200, 409), outcomes.stream().map(result -> {
                try { return result.get(); } catch (Exception error) { throw new AssertionError(error); }
            }).sorted().toList());
            boolean aWon = outcomes.get(0).get() == 200;
            var replay = publish(aWon ? OWNER : MEMBER, aWon ? a : b, "minor");
            assertEquals("1.0.1", replay.version());
            assertEquals(2, detail(first).versions().size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void devicePairingCanOnlyBeClaimedOnceAndUnbindRevokesPendingAndActiveGrants() throws Exception {
        var one = devices.start("Laptop", "127.0.0.1");
        assertEquals("pending", tx.execute(status -> devices.claim(one.secret())).status());
        inTx(() -> devices.approve(OWNER, one.code()));
        String token = tx.execute(status -> devices.claim(one.secret())).token();
        assertEquals(OWNER.user(), devices.bearer("Bearer " + token).user());
        assertThrows(SkillProblem.class, () -> inTx(() -> devices.claim(one.secret())));
        var pending = devices.start("Second", "127.0.0.1");
        inTx(() -> devices.approve(OWNER, pending.code()));
        inTx(() -> devices.revokeAll(ownerSession));
        assertThrows(SkillProblem.class, () -> devices.bearer("Bearer " + token));
        assertThrows(SkillProblem.class, () -> inTx(() -> devices.claim(pending.secret())));
    }

    @Test
    void downloadsUseZipAndCookieMutationsRequireWorkbenchHeader() throws Exception {
        var first = publish(OWNER, preview(OWNER, null, "first"), "patch");
        web.perform(get(ENDPOINT + "/" + first.skillId() + "/versions/1.0.0/download"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentType("application/zip"))
                .andExpect(header().string("X-Skill-SHA256", first.sha256()));
        web.perform(delete(ENDPOINT + "/" + first.skillId()).contentType("application/json")
                        .content("{\"revision\":1,\"reason\":\"remove\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void browserCreatesAndAgentUpdatesThroughTheSameMultipartWorkflow() throws Exception {
        var mapper = new ObjectMapper();
        var file = new MockMultipartFile("file", "shared.zip", "application/zip",
                zip("shared/SKILL.md", "---\nname: shared\ndescription: example\n---\noriginal"));
        String response = web.perform(multipart(ENDPOINT + "/preview").file(file).header("X-ZG-Skills-Request", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String draft = mapper.readTree(response).path("data").path("draftId").asText();
        String createBody = mapper.writeValueAsString(new SkillLibrary.Publish(draft, "Title", "Summary", "Notes", "patch"));
        String created = tx.execute(status -> {
            try {
                return web.perform(post(ENDPOINT + "/publish").header("X-ZG-Skills-Request", "1")
                                .contentType("application/json").content(createBody))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            } catch (Exception error) { throw new AssertionError(error); }
        });
        String id = mapper.readTree(created).path("data").path("skillId").asText();
        var pair = devices.start("Second device", "127.0.0.1");
        inTx(() -> devices.approve(MEMBER, pair.code()));
        String authorization = "Bearer " + tx.execute(status -> devices.claim(pair.secret())).token();
        var updated = new MockMultipartFile("file", "shared.zip", "application/zip",
                zip("shared/SKILL.md", "---\nname: shared\ndescription: example\n---\nupdated"));
        String preview = web.perform(multipart(ENDPOINT + "/preview").file(updated).param("skillId", id)
                        .param("revision", "1").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.baseVersion").value("1.0.0"))
                .andReturn().getResponse().getContentAsString();
        String update = mapper.writeValueAsString(new SkillLibrary.Publish(
                mapper.readTree(preview).path("data").path("draftId").asText(), "Title", "Summary", "Revision", "patch"));
        tx.executeWithoutResult(status -> {
            try {
                web.perform(post(ENDPOINT + "/publish").header("Authorization", authorization)
                                .contentType("application/json").content(update))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value("1.0.1"));
            } catch (Exception error) { throw new AssertionError(error); }
        });
        assertEquals("AGENT", library.detail(OWNER, id).events().get(0).source());
    }

    @Test
    void invalidPackagesAreRejectedBeforeStorage() throws Exception {
        assertThrows(SkillProblem.class, () -> SkillPackage.inspect(zip("shared/../SKILL.md", "malicious")));
        assertThrows(SkillProblem.class, () -> SkillPackage.inspect(zip("shared/.env", "secret")));
        assertThrows(SkillProblem.class, () -> SkillPackage.inspect(zip("shared/SKILL.md",
                "---\nname: other\ndescription: demo\n---\n")));
        try (var files = Files.list(storage)) { assertEquals(0, files.count()); }
    }

    private int publishStatus(SkillLibrary.Actor actor, SkillLibrary.Preview draft) {
        try { publish(actor, draft, "patch"); return 200; }
        catch (SkillProblem error) { return error.status; }
    }

    private SkillLibrary.Preview preview(SkillLibrary.Actor actor, String id, String text) throws IOException {
        return library.preview(actor, id, id == null ? null : library.detail(actor, id).skill().revision(),
                zip("shared/SKILL.md", "---\nname: shared\ndescription: test\n---\n" + text));
    }

    private SkillLibrary.Published publish(SkillLibrary.Actor actor, SkillLibrary.Preview draft, String bump) {
        return tx.execute(status -> library.publish(actor, new SkillLibrary.Publish(draft.draftId(), "title", "summary", "notes", bump)));
    }

    private SkillLibrary.Detail detail(SkillLibrary.Published published) { return library.detail(OWNER, published.skillId()); }

    private SkillLibrary.Mutation mutation(SkillLibrary.Published published) {
        return new SkillLibrary.Mutation(detail(published).skill().revision(), "reason");
    }

    private void inTx(Runnable action) { tx.executeWithoutResult(status -> action.run()); }

    private static byte[] zip(String path, String text) throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry(path));
            zip.write(text.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }
}
