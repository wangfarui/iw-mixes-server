package com.itwray.iw.external.zhaogang.codestats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.codestats.CodeBranchSync.Checkpoint;
import com.itwray.iw.external.zhaogang.codestats.CodeBranchSync.Head;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeSyncCheckpointCodecTest {
    @Test
    void largeRepositorySharesIdenticalShasAcrossHeads() throws Exception {
        var checkpoint = checkpoint(261, 2_500, true);
        String legacy = legacyCompressed(checkpoint);
        String compact = CodeSyncCheckpointCodec.encode(checkpoint);
        assertThat(Base64.getDecoder().decode(legacy.substring(4))).hasSizeGreaterThan(
                CodeSyncCheckpointCodec.MAX_COMPRESSED_BYTES);
        assertThat(compact).startsWith("gz2:").hasSizeLessThan(1_000_000);
        assertThat(CodeSyncCheckpointCodec.decode(compact)).isEqualTo(checkpoint);
    }

    @Test
    void readsLegacyFormatsAndAppliesReadWriteLimits() throws Exception {
        var checkpoint = checkpoint(3, 4, false);
        var raw = new ObjectMapper().writeValueAsString(checkpoint);
        assertThat(CodeSyncCheckpointCodec.decode(raw)).isEqualTo(checkpoint);
        assertThat(CodeSyncCheckpointCodec.decode(legacyCompressed(checkpoint))).isEqualTo(checkpoint);
        assertThatThrownBy(() -> CodeSyncCheckpointCodec.encode(checkpoint, 32))
                .isInstanceOf(CodeSyncCheckpointCodec.TooLarge.class);
        var encoded = CodeSyncCheckpointCodec.encodePart(checkpoint, 8_192, 8_192);
        assertThat(CodeSyncCheckpointCodec.decodePart(encoded.value(), encoded.jsonBytes()).checkpoint())
                .isEqualTo(checkpoint);
        assertThatThrownBy(() -> CodeSyncCheckpointCodec.decodePart(encoded.value(), encoded.jsonBytes() - 1))
                .isInstanceOf(CodeSyncCheckpointCodec.TooLarge.class);
        assertThatThrownBy(() -> CodeSyncCheckpointCodec.encodePart(checkpoint, 8_192, encoded.jsonBytes() - 1))
                .isInstanceOf(CodeSyncCheckpointCodec.TooLarge.class);
    }

    static Checkpoint checkpoint(int branchCount, int commitsPerHead, boolean shareHistory) {
        var random = new Random(261126L);
        Set<String> shared = shareHistory ? shas(random, commitsPerHead) : Set.of();
        var branches = new LinkedHashMap<String, String>();
        var heads = new LinkedHashMap<String, Head>();
        for (int i = 0; i < branchCount; i++) {
            String sha = sha(random);
            branches.put("branch-" + i, sha);
            heads.put(sha, new Head(sha, "2026-11-25", Set.of(sha),
                    shareHistory ? shared : shas(random, commitsPerHead)));
        }
        return new Checkpoint(branches, heads, Map.of());
    }

    private static Set<String> shas(Random random, int count) {
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) result.add(sha(random));
        return result;
    }

    private static String sha(Random random) {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String legacyCompressed(Checkpoint checkpoint) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(out)) {
            new ObjectMapper().writeValue(gzip, checkpoint);
        }
        return "gz1:" + Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
