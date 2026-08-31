package com.itwray.iw.external.zhaogang.iteration;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
class CodingIssueUrlParser {

    ParsedIssueUrl parse(String rawUrl, String teamKey) {
        String value = StringUtils.trimToEmpty(rawUrl);
        if (value.length() > 1000) throw new TeamIterationException("CODING 链接不能超过 1000 个字符");
        try {
            URI uri = URI.create(value);
            String expectedHost = StringUtils.trimToEmpty(teamKey) + ".coding.net";
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !expectedHost.equalsIgnoreCase(uri.getHost())) {
                throw new TeamIterationException("请输入当前 CODING 团队下的 HTTPS 事项链接");
            }
            List<String> segments = List.of(StringUtils.split(StringUtils.defaultString(uri.getPath()), '/'));
            int projectIndex = segments.indexOf("p");
            int issuesIndex = segments.indexOf("issues");
            if (projectIndex < 0 || projectIndex + 1 >= segments.size()
                    || issuesIndex < 0 || issuesIndex + 1 >= segments.size()) {
                throw new TeamIterationException("无法从链接中识别 CODING 项目和事项编号");
            }
            String projectName = URLDecoder.decode(segments.get(projectIndex + 1), StandardCharsets.UTF_8);
            long issueCode = Long.parseLong(segments.get(issuesIndex + 1));
            if (StringUtils.isBlank(projectName) || issueCode <= 0) {
                throw new TeamIterationException("CODING 事项链接缺少有效的项目或事项编号");
            }
            return new ParsedIssueUrl(uri.normalize().toString(), projectName, issueCode);
        } catch (TeamIterationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new TeamIterationException("CODING 事项链接格式不正确");
        }
    }

    record ParsedIssueUrl(String url, String projectName, long issueCode) {
    }
}
