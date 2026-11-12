package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.zhaogang.CodingOpenApiPort;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingCommitDiff;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CodeGitLineStatsTest {

    @TempDir Path temp;
    private final CodeGitLineStats stats = new CodeGitLineStats(mock(CodingOpenApiPort.class), new ZhaogangProperties());

    @Test
    void readsAnOldRootAndALargeChangeFromShallowFetchBySha() throws Exception {
        Path source = initSource();
        Files.writeString(source.resolve("README.md"), "first\n");
        String root = commit(source, "root");
        Files.writeString(source.resolve("package-lock.json"), "locked dependency\n".repeat(23_954));
        commit(source, "large lockfile");
        Files.delete(source.resolve("package-lock.json"));
        String large = commit(source, "remove lockfile");
        commit(source, "new branch head");

        Path rootFetch = fetch(source, root, 2, "root-bare");
        assertEquals(new CodingCommitDiff(1, 0, 1, Map.of(".md", new CodeFileTypes.Lines(1, 0))),
                stats.countFetched(rootFetch, root, stats.deadline()));
        Path largeFetch = fetch(source, large, 2, "large-bare");
        assertEquals(new CodingCommitDiff(0, 23_954, 1, Map.of(".json", new CodeFileTypes.Lines(0, 23_954))),
                stats.countFetched(largeFetch, large, stats.deadline()));
    }

    @Test
    void nonRootWithoutParentObjectDoesNotGetCountedAsRoot() throws Exception {
        Path source = initSource();
        Files.writeString(source.resolve("README"), "first\n");
        commit(source, "root");
        Files.writeString(source.resolve("README"), "second\n");
        String next = commit(source, "modify");
        Path tooShallow = fetch(source, next, 1, "shallow");
        assertThrows(CodeGitLineStats.GitFailure.class, () -> stats.countFetched(tooShallow, next, stats.deadline()));
    }

    @Test
    void countsRenamesAndBinaryChangesWithoutInventingTextLines() throws Exception {
        Path source = initSource();
        Files.writeString(source.resolve("old name"), "unchanged\n");
        commit(source, "root");
        Files.move(source.resolve("old name"), source.resolve("new name"));
        Files.write(source.resolve("binary.dat"), new byte[]{0, 1, 2, 3});
        String next = commit(source, "rename and add binary");
        assertEquals(new CodingCommitDiff(0, 0, 2), stats.countFetched(fetch(source, next, 2, "rename-bare"), next, stats.deadline()));
    }

    @Test
    void onlyAllowsCanonicalCodingRepositoryOnTheConfiguredTeam() {
        assertEquals("https://e.coding.net/team/project/repo.git",
                CodeGitLineStats.repositoryUrl("team", "team", "project", "repo"));
        assertNull(CodeGitLineStats.repositoryUrl("team", "other", "project", "repo"));
        assertNull(CodeGitLineStats.repositoryUrl("team", "team", "../project", "repo"));
        assertNull(CodeGitLineStats.repositoryUrl("team", "team", "project", "https://other.test/repo"));
        assertNull(CodeGitLineStats.repositoryUrl("team", "team", "project", "%2frepo"));
    }

    private Path initSource() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        git(source, "init", "--template=", "-q");
        git(source, "config", "uploadpack.allowReachableSHA1InWant", "true");
        return source;
    }

    private String commit(Path source, String message) throws Exception {
        git(source, "add", "-A");
        git(source, "commit", "--allow-empty", "-qm", message);
        return git(source, "rev-parse", "HEAD").trim();
    }

    private Path fetch(Path source, String sha, int depth, String name) throws Exception {
        Path bare = Files.createDirectory(temp.resolve(name));
        git(bare, "init", "--bare", "--template=", "-q");
        git(bare, "fetch", "--depth=" + depth, "--no-tags", "--no-recurse-submodules",
                "--", source.toUri().toString(), sha);
        return bare;
    }

    private String git(Path cwd, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("/usr/bin/git", "-c", "user.name=fixture", "-c",
                "user.email=fixture@example.invalid", "-c", "commit.gpgSign=false"));
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "fixture git timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            return output;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
