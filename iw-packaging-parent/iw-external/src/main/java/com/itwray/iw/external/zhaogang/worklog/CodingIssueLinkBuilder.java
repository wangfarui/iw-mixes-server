package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Issue;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

@Component
class CodingIssueLinkBuilder {

    private static final Map<String, String> TYPE_SEGMENTS = Map.of(
            "EPIC", "epics",
            "REQUIREMENT", "requirements",
            "MISSION", "assignments",
            "DEFECT", "bug-tracking"
    );

    String build(String teamHost, String projectName, long issueCode, Issue issue) {
        String project = encode(projectName);
        String base = stripTrailingSlash(teamHost) + "/p/" + project;
        if (issueCode <= 0 || issue == null) {
            return base + "/all/issues";
        }
        String segment = issue.subtask() ? "subtasks" : TYPE_SEGMENTS.get(normalize(issue.type()));
        if (segment == null) {
            return base + "/all/issues";
        }
        return base + "/" + segment + "/issues/" + issueCode + "/detail";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String stripTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
