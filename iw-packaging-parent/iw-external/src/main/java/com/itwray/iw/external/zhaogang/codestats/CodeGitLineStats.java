package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingCommitDiff;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.CommitEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** 只给定向补算使用的 Git 读取器，统计后丢弃临时裸仓库。 */
@Component
class CodeGitLineStats {

    private static final Logger log = LoggerFactory.getLogger(CodeGitLineStats.class);
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}");
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,199}");
    private static final long OUTPUT_LIMIT = 2 * 1024 * 1024;
    private static final String GIT_HOST = "https://e.coding.net/";
    private final Semaphore gitSlot = new Semaphore(1, true);
    private final CodingOpenApiPort coding;
    private final ZhaogangProperties properties;

    CodeGitLineStats(CodingOpenApiPort coding, ZhaogangProperties properties) {
        this.coding = coding;
        this.properties = properties;
    }

    Batch batch(CodeStatsModule.ScopeInfo scope) {
        return new Batch(scope);
    }

    final class Batch {
        private final CodeStatsModule.ScopeInfo scope;
        private final Map<String, Optional<String>> usernames = new HashMap<>();
        private final Map<String, Set<Long>> deniedDepots = new HashMap<>();

        private Batch(CodeStatsModule.ScopeInfo scope) {
            this.scope = scope;
        }

        Result count(CommitEntity commit, String token) {
            if (!properties.isCodeGitLineStatsEnabled()) return Result.failed(Failure.DISABLED);
            String sha = commit.getSha();
            String remote = repositoryUrl(properties.getTeam(), scope.teamKey(), commit.getProjectName(), commit.getDepotName());
            if (remote == null || sha == null || !SHA.matcher(sha).matches()) return Result.failed(Failure.INVALID_TARGET);
            if (deniedDepots.getOrDefault(token, Set.of()).contains(commit.getDepotId())) {
                return Result.failed(Failure.PERMISSION_DENIED);
            }
            try {
                if (!gitSlot.tryAcquire(5, TimeUnit.SECONDS)) return Result.failed(Failure.BUSY);
                try {
                    String username = usernames.computeIfAbsent(token, this::resolveUsername).orElse(null);
                    if (username == null) return Result.failed(Failure.CREDENTIAL_UNAVAILABLE);
                    Result result = fetchAndCount(remote, sha.toLowerCase(Locale.ROOT), username, token);
                    if (result.failure() == Failure.PERMISSION_DENIED) {
                        deniedDepots.computeIfAbsent(token, ignored -> new HashSet<>()).add(commit.getDepotId());
                    }
                    return result;
                } finally {
                    gitSlot.release();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Result.failed(Failure.INTERRUPTED);
            }
        }

        private Optional<String> resolveUsername(String token) {
            try {
                var user = coding.currentUserProfile(token);
                if (user == null || user.teamId() != scope.codingTeamId()) return Optional.empty();
                String email = user.email();
                if (email == null || email.isBlank() || email.length() > 320
                        || email.indexOf(':') >= 0 || email.chars().anyMatch(Character::isISOControl)) return Optional.empty();
                return Optional.of(email);
            } catch (CodingOpenApiException ignored) {
                return Optional.empty();
            }
        }
    }

    private Result fetchAndCount(String remote, String sha, String username, String token) throws InterruptedException {
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("iw-code-numstat-");
            long deadline = deadline();
            command(workspace, deadline, Map.of(), "init", "--bare", "--template=", "--quiet");
            // 只对这一条固定 HTTPS 仓库请求附带凭证，不写远程配置，不传给其他 Git 命令。
            String authorization = "Authorization: Basic " + Base64.getEncoder()
                    .encodeToString((username + ":" + token).getBytes(StandardCharsets.UTF_8));
            command(workspace, deadline, Map.of("http." + remote + ".extraHeader", authorization),
                    "fetch", "--depth=2", "--no-tags", "--no-recurse-submodules", "--no-auto-gc",
                    "--quiet", "--", remote, sha);
            String fetched = command(workspace, deadline, Map.of(), "rev-parse", "--verify", "FETCH_HEAD^{commit}").trim();
            if (!sha.equals(fetched)) return Result.failed(Failure.OBJECT_UNAVAILABLE);
            checkExpandedObjects(workspace, deadline);
            return Result.ok(countFetched(workspace, sha, deadline));
        } catch (GitFailure failure) {
            return Result.failed(failure.reason);
        } catch (IOException failure) {
            return Result.failed(Failure.IO_ERROR);
        } finally {
            removeWorkspace(workspace);
        }
    }

    static String repositoryUrl(String configuredTeam, String scopeTeam, String project, String depot) {
        if (!Objects.equals(configuredTeam, scopeTeam) || !safeSegment(scopeTeam)
                || !safeSegment(project) || !safeSegment(depot)) return null;
        return GIT_HOST + scopeTeam + "/" + project + "/" + depot + ".git";
    }

    private static boolean safeSegment(String value) {
        return value != null && SEGMENT.matcher(value).matches() && !value.endsWith(".");
    }

    long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, Math.min(60, properties.getCodeGitLineStatsTimeoutSeconds())));
    }

    private void checkExpandedObjects(Path repo, long deadline) throws IOException, InterruptedException, GitFailure {
        String sizes = command(repo, deadline, Map.of(), "cat-file", "--batch-all-objects",
                "--batch-check=%(objectsize)", "--unordered");
        long remaining = workspaceLimit();
        try {
            for (String line : sizes.lines().toList()) {
                long size = Long.parseLong(line);
                if (size < 0) throw new GitFailure(Failure.INVALID_OUTPUT);
                if (size > remaining) throw new GitFailure(Failure.SIZE_LIMIT);
                remaining -= size;
            }
        } catch (NumberFormatException invalid) {
            throw new GitFailure(Failure.INVALID_OUTPUT);
        }
    }

    CodingCommitDiff countFetched(Path repo, String sha, long deadline) throws IOException, InterruptedException, GitFailure {
        if (!SHA.matcher(sha).matches()) throw new GitFailure(Failure.INVALID_TARGET);
        // 原始对象的父指针不受 shallow 标记影响：缺失父对象的普通提交不能冒充根提交。
        String commit = command(repo, deadline, Map.of(), "cat-file", "commit", sha);
        int headerEnd = commit.indexOf("\n\n");
        if (headerEnd < 0 || !commit.startsWith("tree ")) throw new GitFailure(Failure.INVALID_OUTPUT);
        List<String> parents = commit.substring(0, headerEnd).lines().filter(line -> line.startsWith("parent "))
                .map(line -> line.substring(7)).toList();
        if (parents.size() > 1) throw new GitFailure(Failure.MERGE);
        List<String> args = new ArrayList<>(List.of("diff-tree", "--no-commit-id", "-r", "--numstat", "-z",
                "--find-renames", "--no-ext-diff", "--no-textconv", "--no-color"));
        if (parents.isEmpty()) {
            args.add("--root");
        } else {
            if (!SHA.matcher(parents.get(0)).matches()) throw new GitFailure(Failure.INVALID_OUTPUT);
            args.add(parents.get(0));
        }
        args.add(sha);
        args.add("--");
        return parseNumstat(command(repo, deadline, Map.of(), args.toArray(String[]::new)));
    }

    static CodingCommitDiff parseNumstat(String output) throws GitFailure {
        String[] entries = output.split("\\x00", -1);
        int additions = 0, deletions = 0, files = 0;
        Map<String, CodeFileTypes.Lines> fileTypes = new LinkedHashMap<>();
        if (!entries[entries.length - 1].isEmpty()) throw new GitFailure(Failure.INVALID_OUTPUT);
        try {
            for (int index = 0; index < entries.length - 1; index++) {
                String entry = entries[index];
                int first = entry.indexOf('\t');
                int second = first < 0 ? -1 : entry.indexOf('\t', first + 1);
                if (first <= 0 || second <= first + 1) throw new GitFailure(Failure.INVALID_OUTPUT);
                String added = entry.substring(0, first);
                String deleted = entry.substring(first + 1, second);
                String oldPath = entry.substring(second + 1);
                String newPath = oldPath;
                if (oldPath.isEmpty()) {
                    // -z 下重命名格式为 counts + NUL + oldPath + NUL + newPath + NUL。
                    if (index + 2 >= entries.length - 1 || entries[index + 1].isEmpty()
                            || entries[index + 2].isEmpty()) throw new GitFailure(Failure.INVALID_OUTPUT);
                    oldPath = entries[++index];
                    newPath = entries[++index];
                }
                if (!"-".equals(added) || !"-".equals(deleted)) {
                    int a = Integer.parseInt(added), d = Integer.parseInt(deleted);
                    if (a < 0 || d < 0) throw new GitFailure(Failure.INVALID_OUTPUT);
                    additions = Math.addExact(additions, a);
                    deletions = Math.addExact(deletions, d);
                    CodeFileTypes.add(fileTypes, newPath, a, 0);
                    CodeFileTypes.add(fileTypes, oldPath, 0, d);
                }
                files++;
            }
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new GitFailure(Failure.INVALID_OUTPUT);
        }
        return new CodingCommitDiff(additions, deletions, files, fileTypes);
    }

    private String command(Path repo, long deadline, Map<String, String> httpConfig, String... arguments)
            throws IOException, InterruptedException, GitFailure {
        if (System.nanoTime() >= deadline) throw new GitFailure(Failure.TIMEOUT);
        Path stdout = repo.resolve("iw-numstat.out");
        Path stderr = repo.resolve("iw-numstat.err");
        String executable = properties.getCodeGitLineStatsExecutable();
        if (executable == null || !executable.startsWith("/")) {
            throw new GitFailure(Failure.EXECUTABLE_UNAVAILABLE);
        }
        List<String> args = new ArrayList<>(List.of(executable, "--no-replace-objects"));
        args.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(args).directory(repo.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        Map<String, String> env = builder.environment();
        env.clear();
        env.put("PATH", "/usr/bin:/bin");
        env.put("HOME", repo.toString());
        env.put("TMPDIR", repo.toString());
        env.put("LC_ALL", "C");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_ALLOW_PROTOCOL", httpConfig.isEmpty() ? "" : "https");
        Map<String, String> config = new LinkedHashMap<>(Map.of("http.followRedirects", "false",
                "http.sslVerify", "true", "credential.helper", "", "core.hooksPath", "/dev/null",
                "core.deltaBaseCacheLimit", "16m", "pack.threads", "1", "fetch.fsckObjects", "true"));
        config.putAll(httpConfig);
        env.put("GIT_CONFIG_COUNT", Integer.toString(config.size()));
        int index = 0;
        for (var entry : config.entrySet()) {
            env.put("GIT_CONFIG_KEY_" + index, entry.getKey());
            env.put("GIT_CONFIG_VALUE_" + index++, entry.getValue());
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException unavailable) {
            throw new GitFailure(Failure.EXECUTABLE_UNAVAILABLE);
        }
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() >= deadline) throw new GitFailure(Failure.TIMEOUT);
                checkSize(repo, stdout, stderr);
            }
            if (System.nanoTime() >= deadline) throw new GitFailure(Failure.TIMEOUT);
            checkSize(repo, stdout, stderr);
            if (process.exitValue() != 0) {
                String error = new String(Files.readAllBytes(stderr), StandardCharsets.UTF_8);
                boolean denied = error.contains("Authentication failed") || error.contains("could not read Username")
                        || error.contains("returned error: 401") || error.contains("returned error: 403");
                throw new GitFailure(denied ? Failure.PERMISSION_DENIED : Failure.OBJECT_UNAVAILABLE);
            }
            // Git 路径可以包含非 UTF-8 字节，numstat 只解析数字及分隔符。
            return new String(Files.readAllBytes(stdout), StandardCharsets.UTF_8);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
        }
    }

    private void checkSize(Path repo, Path stdout, Path stderr) throws IOException, GitFailure {
        if (Files.size(stdout) > OUTPUT_LIMIT || Files.size(stderr) > OUTPUT_LIMIT) throw new GitFailure(Failure.SIZE_LIMIT);
        long limit = workspaceLimit();
        long size = 0;
        try (var paths = Files.walk(repo)) {
            for (var iterator = paths.iterator(); iterator.hasNext(); ) {
                Path file = iterator.next();
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        size += Files.size(file);
                    } catch (java.nio.file.NoSuchFileException vanished) {
                        // Git may replace a pack temporary file while we are measuring.
                    }
                    if (size > limit) throw new GitFailure(Failure.SIZE_LIMIT);
                }
            }
        } catch (java.io.UncheckedIOException traversal) {
            if (!(traversal.getCause() instanceof java.nio.file.NoSuchFileException)) throw traversal.getCause();
        }
    }

    private long workspaceLimit() {
        return Math.max(1024 * 1024L, Math.min(256 * 1024 * 1024L, properties.getCodeGitLineStatsMaxBytes()));
    }

    private void removeWorkspace(Path workspace) {
        if (workspace == null) return;
        try (var paths = Files.walk(workspace)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException | java.io.UncheckedIOException failure) {
            log.warn("zhaogang Git numstat temporary workspace cleanup failed, error={}", failure.getClass().getSimpleName());
        }
    }

    enum Failure {
        DISABLED, INVALID_TARGET, CREDENTIAL_UNAVAILABLE, PERMISSION_DENIED, OBJECT_UNAVAILABLE,
        SIZE_LIMIT, TIMEOUT, INVALID_OUTPUT, MERGE, IO_ERROR, EXECUTABLE_UNAVAILABLE, BUSY, INTERRUPTED
    }

    record Result(CodingCommitDiff stats, Failure failure) {
        static Result ok(CodingCommitDiff stats) { return new Result(stats, null); }
        static Result failed(Failure failure) { return new Result(null, failure); }
    }

    static final class GitFailure extends Exception {
        private final Failure reason;
        GitFailure(Failure reason) {
            super(reason.name());
            this.reason = reason;
        }
    }
}
