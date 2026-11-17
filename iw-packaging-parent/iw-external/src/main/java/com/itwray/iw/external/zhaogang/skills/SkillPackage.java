package com.itwray.iw.external.zhaogang.skills;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

final class SkillPackage {
    static final int MAX_ZIP = 8 * 1024 * 1024;
    private static final int MAX_EXPANDED = 32 * 1024 * 1024;
    private static final int MAX_DOCUMENT = 256 * 1024;
    private static final int MAX_ENTRIES = 500;

    record Inspection(String name, String description, String markdown, Map<String, String> files) {}
    record Change(String path, String type) {}

    static Inspection inspect(byte[] content) {
        if (content.length == 0 || content.length > MAX_ZIP) throw SkillProblem.bad("ZIP 须小于或等于 8 MiB");
        Map<String, String> files = new TreeMap<>();
        Set<String> paths = new HashSet<>();
        String root = null;
        String markdown = null;
        int expanded = 0;
        try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(content)).get()) {
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                String path = entry.getName();
                String normalized = entry.isDirectory() ? path.substring(0, path.length() - 1) : path;
                String[] parts = normalized.split("/", -1);
                int type = entry.getUnixMode() & 0170000;
                if (paths.size() >= MAX_ENTRIES || !paths.add(normalized.toLowerCase(Locale.ROOT))
                        || entry.isUnixSymlink() || (type != 0 && type != 0040000 && type != 0100000)
                        || normalized.length() > 320 || !zip.canReadEntryData(entry)) {
                    throw SkillProblem.bad("ZIP 包含不受支持、重复或过多的条目");
                }
                for (String part : parts) {
                    if (!part.matches("[^\\\\/:*?\"<>|\\p{Cntrl}]+") || part.endsWith(".")
                            || !part.equals(part.strip()) || blocked(part)) {
                        throw SkillProblem.bad("ZIP 包含不安全路径或本机凭据文件");
                    }
                }
                if (root == null) root = parts[0];
                if (!root.equals(parts[0]) || (!entry.isDirectory() && parts.length < 2)) {
                    throw SkillProblem.bad("ZIP 必须包含单一 Skill 根目录");
                }
                if (entry.isDirectory()) continue;
                try (var input = zip.getInputStream(entry)) {
                    byte[] bytes = input.readNBytes(MAX_EXPANDED - expanded + 1);
                    expanded += bytes.length;
                    if (expanded > MAX_EXPANDED) throw SkillProblem.bad("ZIP 解压后超过 32 MiB");
                    var crc = new java.util.zip.CRC32();
                    crc.update(bytes);
                    if (bytes.length != entry.getSize() || crc.getValue() != entry.getCrc()) {
                        throw SkillProblem.bad("ZIP 文件校验失败");
                    }
                    String relative = normalized.substring(root.length() + 1);
                    files.put(relative, sha256(bytes));
                    if (relative.equals("SKILL.md")) {
                        if (bytes.length > MAX_DOCUMENT) throw SkillProblem.bad("SKILL.md 超过 256 KiB");
                        markdown = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                    }
                }
            }
        } catch (IOException e) {
            throw SkillProblem.bad("ZIP 或 SKILL.md 格式无效");
        }
        if (markdown == null) throw SkillProblem.bad("缺少根目录下的 SKILL.md");
        Set<String> filePaths = new HashSet<>();
        files.keySet().forEach(file -> filePaths.add(file.toLowerCase(Locale.ROOT)));
        for (String path : paths) {
            int rootSeparator = path.indexOf('/');
            if (rootSeparator < 0) continue;
            String file = path.substring(rootSeparator + 1);
            for (int slash = file.indexOf('/'); slash >= 0; slash = file.indexOf('/', slash + 1)) {
                if (filePaths.contains(file.substring(0, slash))) throw SkillProblem.bad("ZIP 的文件与目录路径冲突");
            }
        }
        String[] front = markdown.replace("\r\n", "\n").split("(?m)^---[ \t]*$", 3);
        if (front.length != 3 || !front[0].isEmpty()) throw SkillProblem.bad("SKILL.md 缺少 YAML 元数据");
        Object metadata;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            metadata = new Yaml(new SafeConstructor(options)).load(front[1]);
        } catch (RuntimeException error) {
            throw SkillProblem.bad("SKILL.md 的 YAML 元数据无效");
        }
        if (!(metadata instanceof Map<?, ?> values) || !(values.get("name") instanceof String name)
                || !name.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || name.length() > 64 || !name.equals(root)
                || !(values.get("description") instanceof String description) || description.isBlank()) {
            throw SkillProblem.bad("包根目录必须与 SKILL.md 的合法 name 一致，并填写 description");
        }
        return new Inspection(name, description.strip(), markdown, Collections.unmodifiableMap(files));
    }

    private static boolean blocked(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals(".git") || lower.equals(".codex") || lower.equals(".ssh")
                || (lower.startsWith(".env") && !lower.equals(".env.example"))
                || lower.equals("auth.json") || lower.equals("credentials.json")
                || lower.matches(".*\\.(pem|key|p12|pfx|jks)") || lower.matches("id_(rsa|dsa|ed25519|ecdsa)")
                || lower.split("\\.", 2)[0].matches("con|prn|aux|nul|com[0-9]|lpt[0-9]");
    }

    static List<Change> changes(Inspection before, Inspection after) {
        Map<String, String> previous = before == null ? Map.of() : before.files();
        Map<String, String> next = after.files();
        Set<String> paths = new TreeSet<>(previous.keySet());
        paths.addAll(next.keySet());
        return paths.stream().filter(path -> !Objects.equals(previous.get(path), next.get(path)))
                .map(path -> new Change(path, !previous.containsKey(path) ? "added" : !next.containsKey(path) ? "removed" : "modified"))
                .toList();
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
