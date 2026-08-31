package com.itwray.iw.external.zhaogang.iteration;

import com.itwray.iw.external.zhaogang.CodingOpenApiPort;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.IssueField;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CodingIssueType;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IssueCreationOptions;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.SelectionOption;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 按项目缓存 CODING 事项类型和字段目录，业务层只使用稳定的中文业务值。 */
@Component
class CodingIssueMetadataCatalog {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final List<SelectionOption> DEFAULT_BUG_PRIORITIES = List.of(
            new SelectionOption("低", "低"), new SelectionOption("中", "中"),
            new SelectionOption("高", "高"), new SelectionOption("紧急", "紧急"));

    private final CodingOpenApiPort coding;
    private final Map<CacheKey, CachedMetadata> cache = new ConcurrentHashMap<>();

    CodingIssueMetadataCatalog(CodingOpenApiPort coding) {
        this.coding = coding;
    }

    IssueMetadata metadata(String token, String projectName, CodingIssueType type) {
        CacheKey key = new CacheKey(tokenFingerprint(token), projectName, type);
        CachedMetadata cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) return cached.metadata();
        IssueMetadata loaded = load(token, projectName, type);
        cache.put(key, new CachedMetadata(loaded, Instant.now().plus(TTL)));
        return loaded;
    }

    IssueCreationOptions creationOptions(String token, String projectName, CodingIssueType type) {
        IssueMetadata metadata = metadata(token, projectName, type);
        return new IssueCreationOptions(type,
                options(metadata, "开发团队"),
                options(metadata, "DoD", "DOD", "Definition of Done"),
                taskTypeOptions(metadata),
                bugPriorityOptions(metadata));
    }

    CodingOpenApiPort.CustomFieldValue customValue(IssueMetadata metadata, String businessValue,
                                                    String... fieldNames) {
        if (StringUtils.isBlank(businessValue)) return null;
        CodingOpenApiPort.IssueField field = findField(metadata, fieldNames);
        if (field == null) {
            throw new TeamIterationException("CODING 项目未配置字段“" + fieldNames[0] + "”");
        }
        String content = resolveContent(field, businessValue);
        return new CodingOpenApiPort.CustomFieldValue(field.id(), content);
    }

    String displayValue(String token, String projectName, CodingIssueType type, String codingValue,
                        String... fieldNames) {
        if (StringUtils.isBlank(codingValue)) return codingValue;
        try {
            return displayValue(metadata(token, projectName, type), codingValue, fieldNames);
        } catch (TeamIterationException ignored) {
            // 字段目录不可用时保留 CODING 原值，避免同步过程中把本地值静默清空。
            return codingValue;
        }
    }

    private String displayValue(IssueMetadata metadata, String codingValue, String... fieldNames) {
        IssueField field = findField(metadata, fieldNames);
        if (field == null || field.options().isEmpty()) return codingValue;
        String normalized = normalizeOption(codingValue);
        return field.options().stream()
                .filter(option -> normalizeOption(option.value()).equals(normalized)
                        || normalizeOption(option.title()).equals(normalized))
                .map(CodingOpenApiPort.IssueFieldOption::title)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(codingValue);
    }

    CodingOpenApiPort.IssueField findField(IssueMetadata metadata, String... names) {
        for (String name : names) {
            for (CodingOpenApiPort.IssueField field : metadata.fields()) {
                if (StringUtils.equalsIgnoreCase(StringUtils.trim(field.name()), StringUtils.trim(name))) return field;
            }
        }
        return null;
    }

    long taskIssueTypeId(IssueMetadata metadata, String taskType) {
        CodingOpenApiPort.IssueField field = findField(metadata, "任务类型");
        if (field != null) return metadata.targetType().id();
        return metadata.allTypes().stream()
                .filter(item -> matches(item, CodingIssueType.SUB_TASK))
                .filter(item -> normalizeOption(item.name()).equals(normalizeOption(taskType)))
                .map(CodingOpenApiPort.IssueType::id).findFirst()
                .orElseThrow(() -> new TeamIterationException("CODING 不存在任务类型“" + taskType + "”"));
    }

    void validateUserStoryParent(IssueMetadata metadata, Long parentIssueTypeId) {
        if (parentIssueTypeId == null || parentIssueTypeId <= 0) return;
        CodingOpenApiPort.IssueType parentType = metadata.allTypes().stream()
                .filter(item -> item.id() == parentIssueTypeId).findFirst().orElse(null);
        if (parentType == null) return;
        if ("UNSPLITTABLE".equalsIgnoreCase(parentType.splitType())) {
            throw new TeamIterationException("该 CODING 需求类型不允许拆分用户故事");
        }
        if ("SPECIFIC_TYPE".equalsIgnoreCase(parentType.splitType())
                && !parentType.splitTargetIssueTypeIds().isEmpty()
                && !parentType.splitTargetIssueTypeIds().contains(metadata.targetType().id())) {
            throw new TeamIterationException("该 CODING 需求类型不能拆分为用户故事");
        }
    }

    private IssueMetadata load(String token, String projectName, CodingIssueType type) {
        List<CodingOpenApiPort.IssueType> allTypes = coding.issueTypes(token, projectName);
        CodingOpenApiPort.IssueType target = allTypes.stream()
                .filter(item -> matches(item, type))
                .sorted((left, right) -> Boolean.compare(right.system(), left.system()))
                .findFirst()
                .orElseThrow(() -> new TeamIterationException(missingTypeMessage(type)));
        String systemType = normalizedSystemType(target, type);
        List<CodingOpenApiPort.IssueField> fields = coding.issueFields(token, projectName, systemType, target.id());
        return new IssueMetadata(target, systemType, fields, allTypes);
    }

    private boolean matches(CodingOpenApiPort.IssueType item, CodingIssueType type) {
        String name = StringUtils.trimToEmpty(item.name()).toLowerCase(Locale.ROOT);
        String system = StringUtils.trimToEmpty(item.systemType()).replace('-', '_').toUpperCase(Locale.ROOT);
        return switch (type) {
            case REQUIREMENT -> ("REQUIREMENT".equals(system) || "STORY".equals(system))
                    && !name.contains("用户故事");
            case TASK -> "TASK".equals(system) || "ASSIGNMENT".equals(system) || name.equals("任务");
            case USER_STORY -> name.contains("用户故事") || "USER_STORY".equals(system)
                    || "USERSTORY".equals(system) || "STORY".equals(system);
            case SUB_TASK -> "SUB_TASK".equals(system) || "SUBTASK".equals(system)
                    || name.contains("子工作项") || name.contains("子任务");
            case DEFECT -> "DEFECT".equals(system) || "BUG".equals(system) || name.contains("缺陷");
        };
    }

    private String normalizedSystemType(CodingOpenApiPort.IssueType target, CodingIssueType type) {
        String system = StringUtils.trimToEmpty(target.systemType()).replace('-', '_').toUpperCase(Locale.ROOT);
        if (type == CodingIssueType.USER_STORY && (system.isEmpty() || "STORY".equals(system))) return "REQUIREMENT";
        if (type == CodingIssueType.SUB_TASK && "SUBTASK".equals(system)) return "SUB_TASK";
        return StringUtils.defaultIfBlank(system, type.name());
    }

    private List<SelectionOption> options(IssueMetadata metadata, String... fieldNames) {
        CodingOpenApiPort.IssueField field = findField(metadata, fieldNames);
        if (field == null) return List.of();
        if (!field.options().isEmpty()) {
            return field.options().stream().map(option -> new SelectionOption(option.title(), option.title())).toList();
        }
        return StringUtils.isBlank(field.defaultValue()) ? List.of()
                : List.of(new SelectionOption(field.defaultValue(), field.defaultValue()));
    }

    private List<SelectionOption> taskTypeOptions(IssueMetadata metadata) {
        List<SelectionOption> configured = options(metadata, "任务类型");
        if (!configured.isEmpty()) return configured;
        return metadata.allTypes().stream().filter(item -> matches(item, CodingIssueType.SUB_TASK))
                .map(item -> new SelectionOption(item.name(), item.name())).distinct().toList();
    }

    private List<SelectionOption> bugPriorityOptions(IssueMetadata metadata) {
        List<SelectionOption> configured = options(metadata, "Bug优先级", "Bug 优先级");
        return configured.isEmpty() ? DEFAULT_BUG_PRIORITIES : configured;
    }

    private String resolveContent(CodingOpenApiPort.IssueField field, String businessValue) {
        if (field.options().isEmpty()) return businessValue;
        String normalizedBusinessValue = normalizeOption(businessValue);
        return field.options().stream()
                .filter(option -> normalizeOption(option.title()).equals(normalizedBusinessValue)
                        || normalizeOption(option.value()).equals(normalizedBusinessValue)
                        || booleanOptionMatches(normalizedBusinessValue, normalizeOption(option.title())))
                .map(CodingOpenApiPort.IssueFieldOption::value)
                .findFirst()
                .orElseThrow(() -> new TeamIterationException("CODING 字段“" + field.name()
                        + "”不存在选项“" + businessValue + "”"));
    }

    private boolean booleanOptionMatches(String expected, String candidate) {
        if ("是".equals(expected)) {
            return "true".equals(candidate) || "yes".equals(candidate)
                    || candidate.contains("线上") && !candidate.contains("非线上");
        }
        if ("否".equals(expected)) {
            return "false".equals(candidate) || "no".equals(candidate) || candidate.contains("非线上");
        }
        return false;
    }

    private String normalizeOption(String value) {
        return StringUtils.trimToEmpty(value).replace("（", "(").replace("）", ")")
                .replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String missingTypeMessage(CodingIssueType type) {
        return switch (type) {
            case REQUIREMENT -> "目标项目未配置需求事项类型";
            case TASK -> "目标项目未配置任务事项类型";
            case USER_STORY -> "目标项目未配置用户故事事项类型";
            case SUB_TASK -> "目标项目不支持子工作项，请核对项目协同模式";
            case DEFECT -> "目标项目未配置缺陷事项类型";
        };
    }

    private String tokenFingerprint(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(StringUtils.defaultString(token).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("无法生成令牌摘要", error);
        }
    }

    record IssueMetadata(CodingOpenApiPort.IssueType targetType, String systemType,
                         List<CodingOpenApiPort.IssueField> fields, List<CodingOpenApiPort.IssueType> allTypes) {
        IssueMetadata {
            fields = fields == null ? List.of() : List.copyOf(fields);
            allTypes = allTypes == null ? List.of() : List.copyOf(allTypes);
        }
    }

    private record CacheKey(String tokenFingerprint, String projectName, CodingIssueType type) {
    }

    private record CachedMetadata(IssueMetadata metadata, Instant expiresAt) {
    }
}
