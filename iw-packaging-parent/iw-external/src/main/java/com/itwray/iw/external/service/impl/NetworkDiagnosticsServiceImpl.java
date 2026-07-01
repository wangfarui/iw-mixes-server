package com.itwray.iw.external.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.common.constants.RequestHeaderConstants;
import com.itwray.iw.external.model.dto.NetworkDiagnosticsCheckDto;
import com.itwray.iw.external.model.enums.ExternalRedisKeyEnum;
import com.itwray.iw.external.model.enums.NetworkDiagnosticsApiCodeEnum;
import com.itwray.iw.external.model.vo.NetworkDiagnosticsResultVo;
import com.itwray.iw.external.service.NetworkDiagnosticsService;
import com.itwray.iw.web.client.AuthenticationClient;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.utils.IpUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.io.IOException;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 网络诊断服务实现。
 *
 * @author wray
 * @since 2026/7/1
 */
@Slf4j
@Service
public class NetworkDiagnosticsServiceImpl implements NetworkDiagnosticsService {

    private static final String QUERY_PERSPECTIVE = "IW_EXTERNAL_SERVER_NETWORK";
    private static final int MIN_TIMEOUT_MS = 1000;
    private static final int MAX_TIMEOUT_MS = 5000;
    private static final int DEFAULT_TIMEOUT_MS = 3000;
    private static final int MIN_PROBE_COUNT = 1;
    private static final int MAX_PROBE_COUNT = 5;
    private static final int DEFAULT_PROBE_COUNT = 3;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_DNS_RECORDS = 50;
    private static final int MAX_HEADER_VALUES = 8;
    private static final int MAX_HEADER_VALUE_LENGTH = 1000;
    private static final Pattern IPV4_PATTERN = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");
    private static final Pattern DOMAIN_LABEL_PATTERN = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> ALLOWED_DNS_RECORD_TYPES = Set.of("A", "AAAA", "CNAME", "MX", "TXT", "NS");

    private final AuthenticationClient authenticationClient;

    private final StringRedisTemplate stringRedisTemplate;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(MAX_TIMEOUT_MS))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Value("${iw.external.network-diagnostics.total-daily-limit:1000}")
    private int totalDailyLimit;

    @Value("${iw.external.network-diagnostics.anonymous-daily-limit:5}")
    private int anonymousDailyLimit;

    @Value("${iw.external.network-diagnostics.anonymous-minute-limit:2}")
    private int anonymousMinuteLimit;

    @Value("${iw.external.network-diagnostics.user-daily-limit:200}")
    private int userDailyLimit;

    @Value("${iw.external.network-diagnostics.user-minute-limit:10}")
    private int userMinuteLimit;

    public NetworkDiagnosticsServiceImpl(AuthenticationClient authenticationClient,
                                         StringRedisTemplate stringRedisTemplate) {
        this.authenticationClient = authenticationClient;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public GeneralResponse<NetworkDiagnosticsResultVo> check(NetworkDiagnosticsCheckDto dto, HttpServletRequest request) {
        RequestPrincipal principal = resolvePrincipal(request);
        QuotaResult quotaResult = consumeQuota(principal);
        if (!quotaResult.allowed()) {
            NetworkDiagnosticsApiCodeEnum apiCode = quotaResult.toApiCode();
            return new GeneralResponse<>(apiCode.getCode(), apiCode.getMessage());
        }

        long startedAt = System.nanoTime();
        try {
            NetworkTarget target = normalizeTarget(dto.getTarget());
            validateTargetResolvedAddresses(target);

            NetworkDiagnosticsResultVo result = new NetworkDiagnosticsResultVo();
            result.setInput(dto.getTarget());
            result.setNormalizedTarget(target.uri().toString());
            result.setHost(target.host());
            result.setScheme(target.scheme());
            result.setPort(target.port());
            result.setTargetType(target.ipLiteral() ? "IP" : "DOMAIN");
            result.setQueryPerspective(QUERY_PERSPECTIVE);
            result.setAuthenticated(principal.authenticated());
            result.setCheckedAt(OffsetDateTime.now());
            result.setQuota(quotaResult.toQuotaVo(principal));

            boolean latencyEnabled = Boolean.TRUE.equals(dto.getLatencyEnabled());
            boolean dnsEnabled = Boolean.TRUE.equals(dto.getDnsEnabled());
            boolean headersEnabled = Boolean.TRUE.equals(dto.getHeadersEnabled());
            if (!latencyEnabled && !dnsEnabled && !headersEnabled) {
                result.getWarnings().add("未选择诊断项，请至少启用延迟、DNS或响应头中的一项");
            }
            if (dnsEnabled) {
                result.setDns(runDnsDiagnostics(target, dto));
            }
            if (latencyEnabled) {
                result.setLatency(runLatencyDiagnostics(target, dto));
            }
            if (headersEnabled) {
                result.setHeaders(runHeaderDiagnostics(target, dto));
            }

            result.setDurationMs(elapsedMs(startedAt));
            result.setSuccess(calculateOverallSuccess(result));
            result.setSummary(buildSummary(result));
            return GeneralResponse.success(result);
        } catch (BusinessException | IllegalArgumentException e) {
            return new GeneralResponse<>(400, e.getMessage());
        } catch (Exception e) {
            log.error("网络诊断执行失败", e);
            return new GeneralResponse<>(NetworkDiagnosticsApiCodeEnum.DIAGNOSTIC_FAILED);
        }
    }

    private NetworkDiagnosticsResultVo.Dns runDnsDiagnostics(NetworkTarget target, NetworkDiagnosticsCheckDto dto) {
        NetworkDiagnosticsResultVo.Dns dns = new NetworkDiagnosticsResultVo.Dns();
        long startedAt = System.nanoTime();
        List<String> recordTypes = normalizeDnsRecordTypes(dto.getDnsRecordTypes());
        dns.setRecordTypes(recordTypes);

        if (target.ipLiteral()) {
            dns.setSuccess(false);
            dns.setDurationMs(elapsedMs(startedAt));
            dns.getWarnings().add("IP目标不执行DNS记录查询");
            return dns;
        }

        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", String.valueOf(clampTimeout(dto.getTimeoutMs())));
        env.put("com.sun.jndi.dns.timeout.retries", "1");

        try {
            DirContext context = new InitialDirContext(env);
            Attributes attributes = context.getAttributes(target.host(), recordTypes.toArray(String[]::new));
            for (String type : recordTypes) {
                Attribute attribute = attributes.get(type);
                if (attribute == null) {
                    continue;
                }
                NamingEnumeration<?> values = attribute.getAll();
                while (values.hasMore()) {
                    if (dns.getRecords().size() >= MAX_DNS_RECORDS) {
                        dns.getWarnings().add("DNS记录较多，仅展示前" + MAX_DNS_RECORDS + "条");
                        break;
                    }
                    NetworkDiagnosticsResultVo.DnsRecord record = new NetworkDiagnosticsResultVo.DnsRecord();
                    record.setType(type);
                    record.setName(target.host());
                    record.setValue(String.valueOf(values.next()));
                    dns.getRecords().add(record);
                }
                values.close();
            }
            context.close();
            if (dns.getRecords().isEmpty()) {
                dns.getWarnings().add("未查询到所选DNS记录");
            }
            dns.setSuccess(!dns.getRecords().isEmpty());
        } catch (NamingException e) {
            dns.setSuccess(false);
            dns.setError("DNS查询失败：" + StringUtils.defaultIfBlank(e.getMessage(), "无返回记录"));
        } finally {
            dns.setDurationMs(elapsedMs(startedAt));
        }
        return dns;
    }

    private NetworkDiagnosticsResultVo.Latency runLatencyDiagnostics(NetworkTarget target, NetworkDiagnosticsCheckDto dto) {
        NetworkDiagnosticsResultVo.Latency latency = new NetworkDiagnosticsResultVo.Latency();
        int probeCount = clampProbeCount(dto.getProbeCount());
        int timeoutMs = clampTimeout(dto.getTimeoutMs());
        latency.setProbeCount(probeCount);

        List<Long> successDurations = new ArrayList<>();
        for (int i = 1; i <= probeCount; i++) {
            NetworkDiagnosticsResultVo.LatencyAttempt attempt = new NetworkDiagnosticsResultVo.LatencyAttempt();
            attempt.setIndex(i);
            try {
                HttpProbeResult probeResult = executeHeadProbe(target.uri(), timeoutMs);
                attempt.setSuccess(true);
                attempt.setStatusCode(probeResult.statusCode());
                attempt.setDurationMs(probeResult.durationMs());
                attempt.setFinalUrl(probeResult.finalUri().toString());
                successDurations.add(probeResult.durationMs());
            } catch (Exception e) {
                attempt.setSuccess(false);
                attempt.setError(readableError(e));
            }
            latency.getAttempts().add(attempt);
        }

        latency.setSuccessCount(successDurations.size());
        latency.setFailureCount(probeCount - successDurations.size());
        latency.setSuccess(!successDurations.isEmpty());
        if (successDurations.isEmpty()) {
            latency.getWarnings().add("全部延迟测试均失败，请检查目标是否允许服务端访问");
            return latency;
        }

        long min = successDurations.stream().mapToLong(Long::longValue).min().orElse(0);
        long max = successDurations.stream().mapToLong(Long::longValue).max().orElse(0);
        long avg = Math.round(successDurations.stream().mapToLong(Long::longValue).average().orElse(0));
        latency.setMinMs(min);
        latency.setMaxMs(max);
        latency.setAvgMs(avg);
        latency.setJitterMs(max - min);
        if (latency.getFailureCount() > 0) {
            latency.getWarnings().add("部分探测失败，平均延迟仅基于成功请求计算");
        }
        return latency;
    }

    private NetworkDiagnosticsResultVo.Headers runHeaderDiagnostics(NetworkTarget target, NetworkDiagnosticsCheckDto dto) {
        NetworkDiagnosticsResultVo.Headers headers = new NetworkDiagnosticsResultVo.Headers();
        int timeoutMs = clampTimeout(dto.getTimeoutMs());
        try {
            HttpProbeResult probeResult = executeHeadProbe(target.uri(), timeoutMs);
            headers.setSuccess(true);
            headers.setStatusCode(probeResult.statusCode());
            headers.setDurationMs(probeResult.durationMs());
            headers.setFinalUrl(probeResult.finalUri().toString());
            headers.setRedirects(probeResult.redirects());
            headers.setResponseHeaders(sanitizeHeaders(probeResult.headers()));
            if (probeResult.redirects().size() >= MAX_REDIRECTS) {
                headers.getWarnings().add("重定向链较长，仅跟随前" + MAX_REDIRECTS + "跳");
            }
        } catch (Exception e) {
            headers.setSuccess(false);
            headers.setError(readableError(e));
        }
        return headers;
    }

    private HttpProbeResult executeHeadProbe(URI startUri, int timeoutMs) throws IOException, InterruptedException {
        URI current = startUri;
        List<NetworkDiagnosticsResultVo.Redirect> redirects = new ArrayList<>();
        long totalStartedAt = System.nanoTime();

        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            NetworkTarget redirectTarget = normalizeTarget(current.toString());
            validateTargetResolvedAddresses(redirectTarget);

            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .header(HttpHeaders.USER_AGENT, "IW-Network-Diagnostics/1.0")
                    .header(HttpHeaders.ACCEPT, "*/*")
                    .build();

            long requestStartedAt = System.nanoTime();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            long requestDurationMs = elapsedMs(requestStartedAt);
            if (!isRedirectStatus(response.statusCode())) {
                return new HttpProbeResult(response.statusCode(), current, response.headers().map(), redirects,
                        elapsedMs(totalStartedAt));
            }

            String location = response.headers().firstValue(HttpHeaders.LOCATION).orElse(null);
            if (StringUtils.isBlank(location)) {
                return new HttpProbeResult(response.statusCode(), current, response.headers().map(), redirects,
                        elapsedMs(totalStartedAt));
            }
            if (redirects.size() >= MAX_REDIRECTS) {
                throw new BusinessException("重定向次数超过限制");
            }

            URI next = current.resolve(location);
            NetworkTarget nextTarget = normalizeTarget(next.toString());
            validateTargetResolvedAddresses(nextTarget);

            NetworkDiagnosticsResultVo.Redirect redirect = new NetworkDiagnosticsResultVo.Redirect();
            redirect.setFromUrl(current.toString());
            redirect.setToUrl(nextTarget.uri().toString());
            redirect.setStatusCode(response.statusCode());
            redirect.setDurationMs(requestDurationMs);
            redirects.add(redirect);
            current = nextTarget.uri();
        }
        throw new BusinessException("重定向次数超过限制");
    }

    private Map<String, List<String>> sanitizeHeaders(Map<String, List<String>> headers) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        headers.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> {
                    List<String> values = new ArrayList<>();
                    for (String value : entry.getValue()) {
                        if (values.size() >= MAX_HEADER_VALUES) {
                            break;
                        }
                        values.add(StringUtils.left(value, MAX_HEADER_VALUE_LENGTH));
                    }
                    result.put(entry.getKey(), values);
                });
        return result;
    }

    private RequestPrincipal resolvePrincipal(HttpServletRequest request) {
        String clientIp = IpUtils.getClientIp(request);
        String token = StringUtils.trimToNull(request.getHeader(RequestHeaderConstants.TOKEN_HEADER));
        if (token != null) {
            try {
                Integer userId = authenticationClient.getUserIdByToken(token);
                if (userId != null) {
                    return new RequestPrincipal(true, userId, clientIp, hashIp(clientIp));
                }
            } catch (RuntimeException e) {
                log.warn("网络诊断接口识别登录态失败，按匿名额度处理", e);
            }
        }
        return new RequestPrincipal(false, null, clientIp, hashIp(clientIp));
    }

    private QuotaResult consumeQuota(RequestPrincipal principal) {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String minute = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        List<String> keys = new ArrayList<>();
        List<String> args = new ArrayList<>();
        List<QuotaExceededType> exceededTypes = new ArrayList<>();

        String totalKey = ExternalRedisKeyEnum.NETWORK_DIAGNOSTICS_DAILY_TOTAL.getKey(date);
        keys.add(totalKey);
        args.add(String.valueOf(totalDailyLimit));
        exceededTypes.add(QuotaExceededType.TOTAL_DAILY);

        String dailyKey;
        String minuteKey;
        int dailyLimit;
        int minuteLimit;
        long minuteExpireSeconds;
        if (principal.authenticated()) {
            dailyKey = ExternalRedisKeyEnum.NETWORK_DIAGNOSTICS_DAILY_USER.getKey(principal.userId(), date);
            minuteKey = ExternalRedisKeyEnum.NETWORK_DIAGNOSTICS_MINUTE_USER.getKey(principal.userId(), minute);
            dailyLimit = userDailyLimit;
            minuteLimit = userMinuteLimit;
            minuteExpireSeconds = ExternalRedisKeyEnum.NETWORK_DIAGNOSTICS_MINUTE_USER.getExpireTime();
            exceededTypes.add(QuotaExceededType.USER_DAILY);
            exceededTypes.add(QuotaExceededType.USER_MINUTE);
        } else {
            dailyKey = ExternalRedisKeyEnum.NETWORK_DIAGNOSTICS_DAILY_ANON_IP.getKey(principal.clientIpHash(), date);
            minuteKey = ExternalRedisKeyEnum.NETWORK_DIAGNOSTICS_MINUTE_ANON_IP.getKey(principal.clientIpHash(), minute);
            dailyLimit = anonymousDailyLimit;
            minuteLimit = anonymousMinuteLimit;
            minuteExpireSeconds = ExternalRedisKeyEnum.NETWORK_DIAGNOSTICS_MINUTE_ANON_IP.getExpireTime();
            exceededTypes.add(QuotaExceededType.ANONYMOUS_DAILY);
            exceededTypes.add(QuotaExceededType.ANONYMOUS_MINUTE);
        }
        keys.add(dailyKey);
        keys.add(minuteKey);
        args.add(String.valueOf(dailyLimit));
        args.add(String.valueOf(minuteLimit));
        args.add(String.valueOf(ExternalRedisKeyEnum.NETWORK_DIAGNOSTICS_DAILY_TOTAL.getExpireTime()));
        args.add(String.valueOf(minuteExpireSeconds));

        String script = """
                for i = 1, #KEYS do
                  local current = tonumber(redis.call('get', KEYS[i]) or '0')
                  local limit = tonumber(ARGV[i])
                  if current >= limit then
                    return -i
                  end
                end
                for i = 1, #KEYS do
                  local value = redis.call('incr', KEYS[i])
                  if value == 1 then
                    if i == #KEYS then
                      redis.call('expire', KEYS[i], tonumber(ARGV[#KEYS + 2]))
                    else
                      redis.call('expire', KEYS[i], tonumber(ARGV[#KEYS + 1]))
                    end
                  end
                end
                return 0
                """;
        Long scriptResult = stringRedisTemplate.execute(new DefaultRedisScript<>(script, Long.class), keys, args.toArray());
        QuotaExceededType exceededType = null;
        if (scriptResult != null && scriptResult < 0) {
            int index = Math.abs(scriptResult.intValue()) - 1;
            if (index >= 0 && index < exceededTypes.size()) {
                exceededType = exceededTypes.get(index);
            }
        }

        return new QuotaResult(
                exceededType == null,
                exceededType,
                totalDailyLimit,
                getCounter(totalKey),
                dailyLimit,
                getCounter(dailyKey),
                minuteLimit,
                getCounter(minuteKey)
        );
    }

    private NetworkTarget normalizeTarget(String input) {
        String value = StringUtils.trimToEmpty(input);
        if (StringUtils.isBlank(value)) {
            throw new BusinessException("请输入公网域名、URL或公网IP");
        }

        String uriText = value.contains("://") ? value : "https://" + value;
        URI rawUri;
        try {
            rawUri = URI.create(uriText);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("请输入有效的URL、域名或公网IP");
        }

        String scheme = StringUtils.lowerCase(rawUri.getScheme(), Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BusinessException("仅支持HTTP或HTTPS目标");
        }
        if (StringUtils.isNotBlank(rawUri.getUserInfo())) {
            throw new BusinessException("目标地址不能包含用户信息");
        }

        String host = rawUri.getHost();
        if (StringUtils.isBlank(host)) {
            throw new BusinessException("请输入有效的URL、域名或公网IP");
        }
        NormalizedHost normalizedHost = normalizeHost(host);

        int port = rawUri.getPort() == -1 ? defaultPort(scheme) : rawUri.getPort();
        if (port != 80 && port != 443) {
            throw new BusinessException("网络诊断仅允许访问80或443端口");
        }

        String path = StringUtils.defaultIfBlank(rawUri.getRawPath(), "/");
        int uriPort = port == defaultPort(scheme) ? -1 : port;
        try {
            URI normalizedUri = new URI(scheme, null, normalizedHost.value(), uriPort, path, rawUri.getRawQuery(), null);
            return new NetworkTarget(normalizedUri, normalizedHost.value(), scheme, port, normalizedHost.ipLiteral());
        } catch (URISyntaxException e) {
            throw new BusinessException("请输入有效的URL、域名或公网IP");
        }
    }

    private NormalizedHost normalizeHost(String host) {
        String value = StringUtils.trimToEmpty(host);
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (isIpLiteral(value)) {
            return new NormalizedHost(normalizeIpAddress(value), true);
        }
        return new NormalizedHost(normalizeDomain(value), false);
    }

    private String normalizeDomain(String host) {
        String value = host.trim();
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        String asciiDomain;
        try {
            asciiDomain = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("请输入有效的域名");
        }

        if (asciiDomain.length() > 253 || !asciiDomain.contains(".")) {
            throw new BusinessException("请输入有效的公网域名");
        }
        if (asciiDomain.endsWith(".local") || asciiDomain.endsWith(".localhost") || asciiDomain.endsWith(".internal")) {
            throw new BusinessException("不允许诊断本地域名或内部域名");
        }
        String[] labels = asciiDomain.split("\\.");
        for (String label : labels) {
            if (!DOMAIN_LABEL_PATTERN.matcher(label).matches()) {
                throw new BusinessException("请输入有效的域名");
            }
        }
        return asciiDomain;
    }

    private void validateTargetResolvedAddresses(NetworkTarget target) {
        InetAddress[] addresses;
        if (target.ipLiteral()) {
            addresses = new InetAddress[]{parseIpAddress(target.host())};
        } else {
            try {
                addresses = InetAddress.getAllByName(target.host());
            } catch (UnknownHostException e) {
                throw new BusinessException("域名解析失败，请检查目标是否存在");
            }
        }

        if (addresses.length == 0) {
            throw new BusinessException("目标未解析到可访问地址");
        }
        for (InetAddress address : addresses) {
            String addressType = classifyIpAddress(address);
            if (!"public".equals(addressType)) {
                throw new BusinessException("目标解析到非公网地址，已拦截");
            }
        }
    }

    private List<String> normalizeDnsRecordTypes(List<String> recordTypes) {
        List<String> result = new ArrayList<>();
        if (recordTypes == null || recordTypes.isEmpty()) {
            recordTypes = List.of("A", "AAAA", "CNAME", "MX", "TXT", "NS");
        }
        for (String recordType : recordTypes) {
            String type = StringUtils.upperCase(StringUtils.trimToEmpty(recordType), Locale.ROOT);
            if (ALLOWED_DNS_RECORD_TYPES.contains(type) && !result.contains(type)) {
                result.add(type);
            }
        }
        if (result.isEmpty()) {
            result.addAll(List.of("A", "AAAA"));
        }
        return result;
    }

    private boolean calculateOverallSuccess(NetworkDiagnosticsResultVo result) {
        List<Boolean> states = new ArrayList<>();
        if (result.getLatency() != null) {
            states.add(result.getLatency().getSuccess());
        }
        if (result.getDns() != null) {
            states.add(result.getDns().getSuccess());
        }
        if (result.getHeaders() != null) {
            states.add(result.getHeaders().getSuccess());
        }
        return states.stream().anyMatch(Boolean.TRUE::equals);
    }

    private String buildSummary(NetworkDiagnosticsResultVo result) {
        List<String> parts = new ArrayList<>();
        if (result.getLatency() != null && Boolean.TRUE.equals(result.getLatency().getSuccess())) {
            parts.add("平均延迟 " + result.getLatency().getAvgMs() + "ms");
        }
        if (result.getDns() != null) {
            parts.add("DNS记录 " + result.getDns().getRecords().size() + " 条");
        }
        if (result.getHeaders() != null && result.getHeaders().getStatusCode() != null) {
            parts.add("HTTP " + result.getHeaders().getStatusCode());
        }
        if (parts.isEmpty()) {
            return Boolean.TRUE.equals(result.getSuccess()) ? "诊断完成" : "诊断未获得有效结果";
        }
        return String.join("，", parts);
    }

    private boolean isRedirectStatus(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }

    private int defaultPort(String scheme) {
        return "http".equals(scheme) ? 80 : 443;
    }

    private int clampTimeout(Integer timeoutMs) {
        if (timeoutMs == null) {
            return DEFAULT_TIMEOUT_MS;
        }
        return Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, timeoutMs));
    }

    private int clampProbeCount(Integer probeCount) {
        if (probeCount == null) {
            return DEFAULT_PROBE_COUNT;
        }
        return Math.max(MIN_PROBE_COUNT, Math.min(MAX_PROBE_COUNT, probeCount));
    }

    private String readableError(Exception e) {
        if (e instanceof BusinessException) {
            return e.getMessage();
        }
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        String message = e.getMessage();
        if (StringUtils.isBlank(message)) {
            return e.getClass().getSimpleName();
        }
        return message;
    }

    private long elapsedMs(long startedAt) {
        return Math.max(0, Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    }

    private boolean isIpLiteral(String value) {
        try {
            normalizeIpAddress(value);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    private String normalizeIpAddress(String ip) {
        String value = ip.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        int zoneIndex = value.indexOf('%');
        if (zoneIndex > -1) {
            value = value.substring(0, zoneIndex);
        }

        if (IPV4_PATTERN.matcher(value).matches()) {
            return normalizeIpv4Address(value);
        }
        if (value.contains(":")) {
            InetAddress address = parseIpAddress(value);
            if (address instanceof Inet6Address) {
                return address.getHostAddress();
            }
        }
        throw new BusinessException("请输入有效的IP地址");
    }

    private String normalizeIpv4Address(String ip) {
        String[] parts = ip.split("\\.");
        List<String> normalizedParts = new ArrayList<>(parts.length);
        for (String part : parts) {
            int value;
            try {
                value = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                throw new BusinessException("请输入有效的IPv4地址");
            }
            if (value < 0 || value > 255) {
                throw new BusinessException("请输入有效的IPv4地址");
            }
            normalizedParts.add(String.valueOf(value));
        }
        return String.join(".", normalizedParts);
    }

    private InetAddress parseIpAddress(String ip) {
        try {
            return InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            throw new BusinessException("请输入有效的IP地址");
        }
    }

    private String classifyIpAddress(InetAddress address) {
        if (address.isAnyLocalAddress()) {
            return "unspecified";
        }
        if (address.isLoopbackAddress()) {
            return "loopback";
        }
        if (address.isLinkLocalAddress()) {
            return "link-local";
        }
        if (address.isSiteLocalAddress()) {
            return "private";
        }
        if (address.isMulticastAddress()) {
            return "multicast";
        }
        if (address instanceof Inet4Address inet4Address) {
            return classifyIpv4Address(inet4Address);
        }
        if (address instanceof Inet6Address inet6Address) {
            return classifyIpv6Address(inet6Address);
        }
        return "public";
    }

    private String classifyIpv4Address(Inet4Address address) {
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        int third = bytes[2] & 0xff;

        if (first == 0 || first >= 240) {
            return "reserved";
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return "carrier-grade-nat";
        }
        if (first == 169 && second == 254) {
            return "link-local";
        }
        if (first == 192 && second == 0 && third == 0) {
            return "reserved";
        }
        if ((first == 192 && second == 0 && third == 2)
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113)) {
            return "documentation";
        }
        if (first == 198 && (second == 18 || second == 19)) {
            return "benchmark";
        }
        return "public";
    }

    private String classifyIpv6Address(Inet6Address address) {
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        int third = bytes[2] & 0xff;
        int fourth = bytes[3] & 0xff;

        if ((first & 0xfe) == 0xfc) {
            return "unique-local";
        }
        if (first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8) {
            return "documentation";
        }
        return "public";
    }

    private int getCounter(String key) {
        String value = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.isBlank(value)) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String hashIp(String clientIp) {
        return DigestUtil.sha256Hex(StringUtils.defaultString(clientIp));
    }

    private record NetworkTarget(URI uri, String host, String scheme, int port, boolean ipLiteral) {
    }

    private record NormalizedHost(String value, boolean ipLiteral) {
    }

    private record RequestPrincipal(boolean authenticated, Integer userId, String clientIp, String clientIpHash) {
    }

    private record HttpProbeResult(int statusCode,
                                   URI finalUri,
                                   Map<String, List<String>> headers,
                                   List<NetworkDiagnosticsResultVo.Redirect> redirects,
                                   long durationMs) {
    }

    private record QuotaResult(boolean allowed,
                               QuotaExceededType exceededType,
                               int totalDailyLimit,
                               int totalDailyUsed,
                               int dailyLimit,
                               int dailyUsed,
                               int minuteLimit,
                               int minuteUsed) {

        NetworkDiagnosticsApiCodeEnum toApiCode() {
            if (exceededType == null) {
                return NetworkDiagnosticsApiCodeEnum.DIAGNOSTIC_FAILED;
            }
            return switch (exceededType) {
                case TOTAL_DAILY -> NetworkDiagnosticsApiCodeEnum.TOTAL_DAILY_QUOTA_EXCEEDED;
                case ANONYMOUS_DAILY -> NetworkDiagnosticsApiCodeEnum.ANONYMOUS_DAILY_QUOTA_EXCEEDED;
                case ANONYMOUS_MINUTE -> NetworkDiagnosticsApiCodeEnum.ANONYMOUS_MINUTE_QUOTA_EXCEEDED;
                case USER_DAILY -> NetworkDiagnosticsApiCodeEnum.USER_DAILY_QUOTA_EXCEEDED;
                case USER_MINUTE -> NetworkDiagnosticsApiCodeEnum.USER_MINUTE_QUOTA_EXCEEDED;
            };
        }

        NetworkDiagnosticsResultVo.Quota toQuotaVo(RequestPrincipal principal) {
            NetworkDiagnosticsResultVo.Quota quota = new NetworkDiagnosticsResultVo.Quota();
            quota.setScope(principal.authenticated() ? "USER" : "ANONYMOUS");
            quota.setDailyLimit(dailyLimit);
            quota.setDailyUsed(dailyUsed);
            quota.setMinuteLimit(minuteLimit);
            quota.setMinuteUsed(minuteUsed);
            quota.setTotalDailyLimit(totalDailyLimit);
            quota.setTotalDailyUsed(totalDailyUsed);
            return quota;
        }
    }

    private enum QuotaExceededType {
        TOTAL_DAILY,
        ANONYMOUS_DAILY,
        ANONYMOUS_MINUTE,
        USER_DAILY,
        USER_MINUTE
    }
}
