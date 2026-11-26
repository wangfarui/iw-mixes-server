package com.itwray.iw.external.zhaogang.codestats;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class CodeSyncCheckpointCodec {
    static final int MAX_COMPRESSED_BYTES = 8 * 1024 * 1024;
    static final int MAX_JSON_BYTES = 64 * 1024 * 1024;
    private static final int MAX_BASE64_CHARS = 4 * ((MAX_COMPRESSED_BYTES + 2) / 3);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FORMAT = "gz2:";
    private static final String LEGACY_FORMAT = "gz1:";

    private record Packed(List<String> shas, Map<String, Integer> branches,
                          Map<Integer, PackedHead> heads, Map<Integer, PackedNode> graph) {}
    private record PackedHead(String through, int[] reachable, int[] matched) {}
    private record PackedNode(int[] parents, boolean matched, int[] summarizedMatches, boolean boundary) {}
    record Encoded(String value, int jsonBytes) {}
    record Decoded(CodeBranchSync.Checkpoint checkpoint, int jsonBytes) {}

    static String encode(CodeBranchSync.Checkpoint checkpoint) throws IOException {
        return encode(checkpoint, MAX_COMPRESSED_BYTES);
    }

    static String encode(CodeBranchSync.Checkpoint checkpoint, int compressedLimit) throws IOException {
        return encodePart(checkpoint, compressedLimit, MAX_JSON_BYTES).value();
    }

    static Encoded encodePart(CodeBranchSync.Checkpoint checkpoint, int compressedLimit, int jsonLimit) throws IOException {
        long references = checkpoint.heads().values().stream()
                .mapToLong(head -> (long) head.reachable().size() + head.matched().size()).sum()
                + checkpoint.graph().values().stream()
                .mapToLong(node -> (long) node.parents().size() + node.summarizedMatches().size()).sum();
        // Each array reference needs at least one digit and one delimiter in JSON.
        if (references > jsonLimit / 2L) throw new TooLarge();
        var ids = new LinkedHashMap<String, Integer>();
        var branches = new LinkedHashMap<String, Integer>();
        checkpoint.branches().forEach((branch, sha) -> branches.put(branch, intern(ids, sha)));
        var heads = new LinkedHashMap<Integer, PackedHead>();
        checkpoint.heads().forEach((sha, head) -> heads.put(intern(ids, sha),
                new PackedHead(head.through(), sortedIds(ids, head.reachable()), sortedIds(ids, head.matched()))));
        var graph = new LinkedHashMap<Integer, PackedNode>();
        checkpoint.graph().forEach((sha, node) -> graph.put(intern(ids, sha), new PackedNode(
                node.parents().stream().mapToInt(parent -> intern(ids, parent)).toArray(), node.matched(),
                sortedIds(ids, node.summarizedMatches()), node.boundary())));
        var packed = new Packed(new ArrayList<>(ids.keySet()), branches, heads, graph);
        var bytes = new ByteArrayOutputStream();
        BoundedOutput raw;
        try (var gzip = new GZIPOutputStream(new BoundedOutput(bytes, compressedLimit))) {
            raw = new BoundedOutput(gzip, jsonLimit);
            JSON.writeValue(raw, packed);
        } catch (IOException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof TooLarge tooLarge) throw tooLarge;
            }
            throw failure;
        }
        return new Encoded(FORMAT + Base64.getEncoder().encodeToString(bytes.toByteArray()), jsonLimit - raw.remaining);
    }

    static CodeBranchSync.Checkpoint decode(String text) throws IOException {
        if (!text.startsWith(FORMAT) && !text.startsWith(LEGACY_FORMAT)) {
            if (text.length() > MAX_JSON_BYTES) throw new TooLarge();
            return JSON.readValue(text, CodeBranchSync.Checkpoint.class);
        }
        return decodePart(text, MAX_JSON_BYTES).checkpoint();
    }

    static Decoded decodePart(String text, int jsonLimit) throws IOException {
        if (!text.startsWith(FORMAT) && !text.startsWith(LEGACY_FORMAT)) throw new IOException("Invalid checkpoint part format");
        if (text.length() - FORMAT.length() > MAX_BASE64_CHARS) throw new TooLarge();
        byte[] compressed = Base64.getDecoder().decode(text.substring(FORMAT.length()));
        if (compressed.length > MAX_COMPRESSED_BYTES) throw new TooLarge();
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] bytes = gzip.readNBytes(jsonLimit + 1);
            if (bytes.length > jsonLimit) throw new TooLarge();
            var checkpoint = text.startsWith(LEGACY_FORMAT) ? JSON.readValue(bytes, CodeBranchSync.Checkpoint.class)
                    : unpack(JSON.readValue(bytes, Packed.class));
            return new Decoded(checkpoint, bytes.length);
        }
    }

    private static CodeBranchSync.Checkpoint unpack(Packed packed) {
        var branches = new LinkedHashMap<String, String>();
        packed.branches().forEach((branch, id) -> branches.put(branch, packed.shas().get(id)));
        var heads = new LinkedHashMap<String, CodeBranchSync.Head>();
        packed.heads().forEach((id, head) -> {
            String sha = packed.shas().get(id);
            heads.put(sha, new CodeBranchSync.Head(sha, head.through(), shaSet(packed.shas(), head.reachable()),
                    shaSet(packed.shas(), head.matched())));
        });
        var graph = new LinkedHashMap<String, CodeCommitGraph.Node>();
        packed.graph().forEach((id, node) -> {
            List<String> parents = new ArrayList<>(node.parents().length);
            for (int parent : node.parents()) parents.add(packed.shas().get(parent));
            graph.put(packed.shas().get(id), new CodeCommitGraph.Node(parents, node.matched(),
                    shaSet(packed.shas(), node.summarizedMatches()), node.boundary()));
        });
        return new CodeBranchSync.Checkpoint(branches, heads, graph);
    }

    private static int intern(Map<String, Integer> ids, String sha) {
        return ids.computeIfAbsent(sha, ignored -> ids.size());
    }

    private static int[] sortedIds(Map<String, Integer> ids, Collection<String> shas) {
        return shas.stream().mapToInt(sha -> intern(ids, sha)).sorted().toArray();
    }

    private static Set<String> shaSet(List<String> shas, int[] ids) {
        Set<String> values = new LinkedHashSet<>();
        for (int id : ids) values.add(shas.get(id));
        return values;
    }

    static final class TooLarge extends IOException {
        TooLarge() { super("同步检查点超过大小上限"); }
    }

    private static final class BoundedOutput extends OutputStream {
        private final OutputStream target;
        private int remaining;

        BoundedOutput(OutputStream target, int limit) {
            this.target = target;
            this.remaining = limit;
        }

        @Override
        public void write(int value) throws IOException {
            reserve(1);
            target.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            reserve(length);
            target.write(bytes, offset, length);
        }

        private void reserve(int length) throws TooLarge {
            if (length > remaining) throw new TooLarge();
            remaining -= length;
        }

        @Override
        public void flush() throws IOException { target.flush(); }

        @Override
        public void close() throws IOException { target.close(); }
    }

    private CodeSyncCheckpointCodec() {}
}
