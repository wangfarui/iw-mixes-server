package com.itwray.iw.external.zhaogang.codestats;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.Base64;
import java.util.zip.*;

/** 游标中的重复 SHA 压缩后持久化；无效/超限数据由调用方安全回退完整读取。 */
final class CodeSyncCheckpointCodec {
    private static final ObjectMapper JSON = new ObjectMapper();
    static String encode(CodeBranchSync.Checkpoint checkpoint) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) { JSON.writeValue(gzip, checkpoint); }
        if (out.size() > 8 * 1024 * 1024) throw new IOException("同步游标超过保护上限");
        return "gz1:" + Base64.getEncoder().encodeToString(out.toByteArray());
    }
    static CodeBranchSync.Checkpoint decode(String text) throws IOException {
        if (!text.startsWith("gz1:")) return JSON.readValue(text, CodeBranchSync.Checkpoint.class);
        try (var in = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(text.substring(4))))) {
            byte[] bytes = in.readNBytes(64 * 1024 * 1024 + 1);
            if (bytes.length > 64 * 1024 * 1024) throw new IOException("同步游标超过保护上限");
            return JSON.readValue(bytes, CodeBranchSync.Checkpoint.class);
        }
    }
    private CodeSyncCheckpointCodec() {}
}
