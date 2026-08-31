package com.itwray.iw.external.zhaogang.credential;

import com.itwray.iw.external.mapper.ZhaogangCodingCredentialMapper;
import com.itwray.iw.external.zhaogang.credential.entity.CodingCredentialEntity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Stores the plaintext PAT backup and exposes it only to the team worklog path.
 * Other CODING calls continue to use the current session cookie token.
 */
@Service
public class CodingCredentialService {

    private final ZhaogangCodingCredentialMapper mapper;

    public CodingCredentialService(ZhaogangCodingCredentialMapper mapper) {
        this.mapper = mapper;
    }

    public void upsert(long codingTeamId, long codingUserId, String token, String userName, String avatar) {
        String normalized = token == null ? "" : token.trim();
        if (codingTeamId <= 0 || codingUserId <= 0 || normalized.isBlank()) {
            throw new IllegalArgumentException("CODING 凭证信息不完整");
        }
        mapper.upsert(codingTeamId, codingUserId, normalized, fingerprint(normalized), userName, avatar);
    }

    public Optional<String> token(long codingTeamId, long codingUserId) {
        if (codingTeamId <= 0 || codingUserId <= 0) {
            return Optional.empty();
        }
        CodingCredentialEntity entity = mapper.find(codingTeamId, codingUserId);
        return entity == null || entity.getTokenPlaintext() == null || entity.getTokenPlaintext().isBlank()
                ? Optional.empty() : Optional.of(entity.getTokenPlaintext());
    }

    public boolean exists(long codingTeamId, long codingUserId) {
        return token(codingTeamId, codingUserId).isPresent();
    }

    public void remove(long codingTeamId, long codingUserId) {
        if (codingTeamId > 0 && codingUserId > 0) {
            mapper.delete(codingTeamId, codingUserId);
        }
    }

    public static String fingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
