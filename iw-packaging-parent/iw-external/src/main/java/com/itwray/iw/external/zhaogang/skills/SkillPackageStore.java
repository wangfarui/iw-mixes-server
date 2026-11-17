package com.itwray.iw.external.zhaogang.skills;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.BucketVersioningConfiguration;
import com.aliyun.oss.model.CannedAccessControlList;
import com.aliyun.oss.model.ObjectMetadata;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Component
class SkillPackageStore {
    private final OSS oss;
    private final String bucket;
    private final Path local;

    SkillPackageStore(@Value("${iw.zhaogang.skills.oss-endpoint:}") String endpoint,
                      @Value("${iw.zhaogang.skills.oss-bucket:}") String bucket,
                      @Value("${iw.zhaogang.skills.oss-access-key:}") String accessKey,
                      @Value("${iw.zhaogang.skills.oss-secret-key:}") String secretKey,
                      @Value("${iw.zhaogang.skills.local-directory:}") String directory) {
        if (!endpoint.isBlank() && !endpoint.startsWith("https://")) {
            throw new IllegalArgumentException("Skills OSS endpoint 必须使用 HTTPS");
        }
        this.bucket = bucket;
        this.oss = endpoint.isBlank() || bucket.isBlank() || accessKey.isBlank() || secretKey.isBlank()
                ? null : new OSSClientBuilder().build(endpoint, accessKey, secretKey);
        this.local = directory.isBlank() ? null : Path.of(directory).toAbsolutePath().normalize();
    }

    boolean enabled() { return oss != null || local != null; }

    void put(String id, byte[] bytes) {
        available();
        requirePermanentDeletion();
        try {
            if (oss != null) {
                ObjectMetadata metadata = new ObjectMetadata();
                metadata.setContentLength(bytes.length);
                metadata.setObjectAcl(CannedAccessControlList.Private);
                oss.putObject(bucket, key(id), new ByteArrayInputStream(bytes), metadata);
            } else {
                Files.createDirectories(local);
                Files.write(local.resolve(id + ".zip"), bytes, StandardOpenOption.CREATE_NEW);
            }
        } catch (Exception e) {
            throw new SkillProblem(503, "Skill 包写入失败");
        }
    }

    byte[] get(String id) {
        available();
        try (var input = oss != null ? oss.getObject(bucket, key(id)).getObjectContent()
                : Files.newInputStream(local.resolve(id + ".zip"))) {
            byte[] bytes = input.readNBytes(SkillPackage.MAX_ZIP + 1);
            if (bytes.length > SkillPackage.MAX_ZIP) throw new IOException("Oversized package");
            return bytes;
        } catch (Exception e) {
            throw new SkillProblem(503, "Skill 包暂不可读");
        }
    }

    void delete(String id) {
        available();
        requirePermanentDeletion();
        try {
            if (oss != null) oss.deleteObject(bucket, key(id));
            else Files.deleteIfExists(local.resolve(id + ".zip"));
        } catch (Exception e) {
            throw new SkillProblem(503, "Skill 包清理失败");
        }
    }

    private void requirePermanentDeletion() {
        if (oss == null) return;
        String status;
        try {
            status = oss.getBucketVersioning(bucket).getStatus();
        } catch (Exception error) {
            throw new SkillProblem(503, "无法核对 Skills OSS 的版本保留设置");
        }
        if (status != null && !status.isBlank() && !BucketVersioningConfiguration.OFF.equalsIgnoreCase(status)) {
            throw new SkillProblem(503, "Skills 需要未启用版本保留的私有 OSS Bucket");
        }
    }

    private void available() {
        if (!enabled()) throw new SkillProblem(503, "Skills 制品存储未配置");
    }

    private String key(String id) { return "zhaogang/skills/" + id + ".zip"; }

    @PreDestroy
    void close() { if (oss != null) oss.shutdown(); }
}
