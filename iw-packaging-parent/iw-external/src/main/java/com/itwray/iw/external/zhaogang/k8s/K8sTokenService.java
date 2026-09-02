package com.itwray.iw.external.zhaogang.k8s;

import com.itwray.iw.external.mapper.ZhaogangK8sTokenMapper;
import com.itwray.iw.external.zhaogang.k8s.entity.K8sTokenEntity;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class K8sTokenService {

    private final ZhaogangK8sTokenMapper mapper;

    public K8sTokenService(ZhaogangK8sTokenMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Boolean> statuses(long teamId, long userId) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        List<K8sTokenEntity> entities = teamId <= 0 || userId <= 0
                ? List.of() : mapper.findAll(teamId, userId);
        for (K8sTokenEnvironment environment : K8sTokenEnvironment.values()) {
            result.put(environment.code(), entities.stream().anyMatch(item -> environment.code().equals(item.getEnvironment())
                    && item.getTokenPlaintext() != null && !item.getTokenPlaintext().isBlank()));
        }
        return result;
    }

    public String token(long teamId, long userId, String environment) {
        K8sTokenEnvironment parsed = K8sTokenEnvironment.parse(environment);
        if (teamId <= 0 || userId <= 0) {
            throw new IllegalArgumentException("找钢工作台会话信息不完整");
        }
        K8sTokenEntity entity = mapper.find(teamId, userId, parsed.code());
        if (entity == null || entity.getTokenPlaintext() == null || entity.getTokenPlaintext().isBlank()) {
            throw new IllegalArgumentException("当前环境尚未配置 K8s Token");
        }
        return entity.getTokenPlaintext();
    }

    public void upsert(long teamId, long userId, String environment, String token) {
        K8sTokenEnvironment parsed = K8sTokenEnvironment.parse(environment);
        String normalized = token == null ? "" : token.trim();
        if (teamId <= 0 || userId <= 0 || normalized.isBlank()) {
            throw new IllegalArgumentException("K8s Token 信息不完整");
        }
        mapper.upsert(teamId, userId, parsed.code(), normalized);
    }

    public void delete(long teamId, long userId, String environment) {
        K8sTokenEnvironment parsed = K8sTokenEnvironment.parse(environment);
        if (teamId > 0 && userId > 0) {
            mapper.delete(teamId, userId, parsed.code());
        }
    }

    public List<String> environments() {
        return Arrays.stream(K8sTokenEnvironment.values()).map(K8sTokenEnvironment::code).toList();
    }
}
