package com.itwray.iw.external.service.impl;

import cn.hutool.http.HttpUtil;
import cn.hutool.http.HttpStatus;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.itwray.iw.auth.client.BaseWebsiteNavigationClient;
import com.itwray.iw.auth.model.vo.WebsiteNavigationListVo;
import com.itwray.iw.external.model.dto.IpLookupQueryDto;
import com.itwray.iw.external.model.enums.ExternalRedisKeyEnum;
import com.itwray.iw.external.model.vo.IpLocationVo;
import com.itwray.iw.external.model.vo.IpLookupRecordVo;
import com.itwray.iw.external.model.vo.IpLookupResultVo;
import com.itwray.iw.external.service.DailyHotService;
import com.itwray.iw.external.service.ExternalApiService;
import com.itwray.iw.starter.redis.RedisUtil;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.exception.IwServerException;
import com.itwray.iw.web.exception.IwWebException;
import com.itwray.iw.web.utils.IpUtils;
import com.itwray.iw.web.utils.SpringWebHolder;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 外部API服务实现层
 *
 * @author wray
 * @since 2024/10/17
 */
@Service
public class ExternalApiServiceImpl implements ExternalApiService {

    private static final String QUERY_PERSPECTIVE_CURRENT_IP = "IW_EXTERNAL_REQUEST_IP";

    private static final String QUERY_PERSPECTIVE_SERVER_DNS = "IW_EXTERNAL_SERVER_DNS";

    private static final int MAX_DNS_RECORDS = 20;

    private static final Pattern IPV4_PATTERN = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");

    private static final Pattern DOMAIN_LABEL_PATTERN = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$",
            Pattern.CASE_INSENSITIVE);

    private BaseWebsiteNavigationClient baseWebsiteNavigationClient;

    private DailyHotService dailyHotService;

    /**
     * 高德地图API Key
     */
    @Value("${iw.external.amap.key:}")
    private String amapKey;

    /**
     * UptimeRobot API Key
     */
    @Value("${iw.external.uptimerobot.key:}")
    private String uptimeRobotKey;

    @Value("${iw.remote.auth.base-url:}")
    private String coreBaseUrl;

    @Autowired
    public void setBaseWebsiteNavigationClient(BaseWebsiteNavigationClient baseWebsiteNavigationClient) {
        this.baseWebsiteNavigationClient = baseWebsiteNavigationClient;
    }

    @Autowired
    public void setDailyHotService(DailyHotService dailyHotService) {
        this.dailyHotService = dailyHotService;
    }

    @Override
    public void heartbeat() {
        if (StringUtils.isBlank(coreBaseUrl)) {
            return;
        }
        try (HttpResponse response = HttpUtil.createGet(coreBaseUrl + "/actuator/health").timeout(3000).execute()) {
            if (response.getStatus() >= HttpStatus.HTTP_BAD_REQUEST) {
                throw new IwServerException("iw-core 服务异常");
            }
        } catch (Exception e) {
            throw new IwServerException("iw-core 服务已下线");
        }
    }

    @Override
    public Map<Object, Object> getIpAddress() {
        HttpServletRequest request = SpringWebHolder.getRequest();
        String clientIp = IpUtils.getClientIp(request);
        if (StringUtils.isBlank(clientIp)) {
            throw new IwWebException("无效的请求");
        }
        return queryAmapIpAddress(clientIp.trim());
    }

    @Override
    public IpLookupResultVo getCurrentIpLookup() {
        HttpServletRequest request = SpringWebHolder.getRequest();
        String clientIp = IpUtils.getClientIp(request);
        if (StringUtils.isBlank(clientIp)) {
            throw new BusinessException("无效的请求IP");
        }

        String normalizedIp = normalizeIpAddress(clientIp);
        IpLookupResultVo result = createIpLookupResult(clientIp, normalizedIp, "CURRENT_IP", QUERY_PERSPECTIVE_CURRENT_IP);
        result.setClientIp(normalizedIp);
        result.getRecords().add(buildIpLookupRecord(null, normalizedIp));
        appendRecordWarnings(result);
        return result;
    }

    @Override
    public IpLookupResultVo queryIpLookup(IpLookupQueryDto dto) {
        String mode = normalizeMode(dto.getMode());
        String input = dto.getInput().trim();
        String target = extractLookupTarget(input);

        if ("ip".equals(mode) || ("auto".equals(mode) && isIpLiteral(target))) {
            String normalizedIp = normalizeIpAddress(target);
            IpLookupResultVo result = createIpLookupResult(input, normalizedIp, "IP", QUERY_PERSPECTIVE_SERVER_DNS);
            result.getRecords().add(buildIpLookupRecord(null, normalizedIp));
            appendRecordWarnings(result);
            return result;
        }

        if ("auto".equals(mode) || "domain".equals(mode)) {
            return queryDomainIpLookup(input, target);
        }

        throw new BusinessException("查询模式仅支持 auto、ip、domain");
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> queryAmapIpAddress(String ip) {
        Map<Object, Object> ipCache = (Map<Object, Object>) RedisUtil.get(ExternalRedisKeyEnum.IP_ADDRESS_KEY.getKey(ip));
        if (ipCache != null) {
            return normalizeAmapResponse(ipCache);
        }
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("key", this.amapKey);
        paramMap.put("ip", ip);
        String res = HttpUtil.get("https://restapi.amap.com/v3/ip", paramMap);
        Map<Object, Object> resMap = (Map<Object, Object>) JSONUtil.toBean(res, Map.class);
        normalizeAmapResponse(resMap);
        // 缓存请求ip的地址信息
        ExternalRedisKeyEnum.IP_ADDRESS_KEY.setStringValue(resMap, ip);
        return resMap;
    }

    private IpLookupResultVo queryDomainIpLookup(String input, String target) {
        String domain = normalizeDomain(target);
        IpLookupResultVo result = createIpLookupResult(input, domain, "DOMAIN", QUERY_PERSPECTIVE_SERVER_DNS);

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(domain);
        } catch (UnknownHostException e) {
            throw new BusinessException("域名解析失败，请检查域名是否存在");
        }

        Set<String> seenIps = new LinkedHashSet<>();
        for (InetAddress address : addresses) {
            String ip = address.getHostAddress();
            if (!seenIps.add(ip)) {
                continue;
            }
            if (result.getRecords().size() >= MAX_DNS_RECORDS) {
                result.getWarnings().add("DNS记录较多，仅展示前" + MAX_DNS_RECORDS + "条");
                break;
            }
            result.getRecords().add(buildIpLookupRecord(domain, address));
        }

        appendRecordWarnings(result);
        return result;
    }

    private IpLookupResultVo createIpLookupResult(String input, String normalizedInput, String targetType,
                                                  String queryPerspective) {
        IpLookupResultVo result = new IpLookupResultVo();
        result.setInput(input);
        result.setNormalizedInput(normalizedInput);
        result.setTargetType(targetType);
        result.setQueryPerspective(queryPerspective);
        result.setQueriedAt(OffsetDateTime.now());
        return result;
    }

    private IpLookupRecordVo buildIpLookupRecord(String host, String ip) {
        return buildIpLookupRecord(host, parseIpAddress(ip));
    }

    private IpLookupRecordVo buildIpLookupRecord(String host, InetAddress address) {
        String addressType = classifyIpAddress(address);

        IpLookupRecordVo record = new IpLookupRecordVo();
        record.setHost(host);
        record.setIp(address.getHostAddress());
        record.setFamily(address instanceof Inet6Address ? "IPv6" : "IPv4");
        record.setAddressType(addressType);
        record.setPublicIp("public".equals(addressType));

        if (!record.isPublicIp()) {
            record.setMessage("内网或保留地址，不查询公网定位");
            return record;
        }

        Map<Object, Object> locationMap = queryAmapIpAddress(record.getIp());
        record.setLocation(toIpLocationVo(locationMap));
        if (!"1".equals(String.valueOf(locationMap.get("status")))) {
            String info = getStringValue(locationMap, "info");
            record.setMessage(StringUtils.isBlank(info) ? "定位服务未返回有效结果" : info);
        }
        return record;
    }

    private void appendRecordWarnings(IpLookupResultVo result) {
        if (result.getRecords().isEmpty()) {
            result.getWarnings().add("未解析到可展示的IP记录");
            return;
        }

        Set<String> warnings = new LinkedHashSet<>(result.getWarnings());
        for (IpLookupRecordVo record : result.getRecords()) {
            if (StringUtils.isNotBlank(record.getMessage())) {
                warnings.add(record.getIp() + "：" + record.getMessage());
            }
        }
        result.setWarnings(new ArrayList<>(warnings));
    }

    private String normalizeMode(String mode) {
        if (StringUtils.isBlank(mode)) {
            return "auto";
        }
        String normalizedMode = mode.trim().toLowerCase();
        if ("auto".equals(normalizedMode) || "ip".equals(normalizedMode) || "domain".equals(normalizedMode)) {
            return normalizedMode;
        }
        throw new BusinessException("查询模式仅支持 auto、ip、domain");
    }

    private String extractLookupTarget(String input) {
        String value = input.trim();
        if (StringUtils.isBlank(value)) {
            throw new BusinessException("请输入公网IP、域名或URL");
        }

        String parsedHost = parseUrlHost(value);
        if (StringUtils.isNotBlank(parsedHost)) {
            return parsedHost;
        }
        return value;
    }

    private String parseUrlHost(String value) {
        if (isIpLiteral(value)) {
            return value;
        }

        String uriText = null;
        if (value.contains("://")) {
            uriText = value;
        } else if (value.startsWith("//")) {
            uriText = "http:" + value;
        } else if (value.startsWith("[") || value.contains("/") || value.contains("?") || value.contains("#")
                || countChar(value, ':') == 1) {
            uriText = "http://" + value;
        }

        if (StringUtils.isBlank(uriText)) {
            return null;
        }

        try {
            URI uri = URI.create(uriText);
            return uri.getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String normalizeDomain(String host) {
        String value = host.trim();
        if (StringUtils.isBlank(value)) {
            throw new BusinessException("请输入域名");
        }
        if (isIpLiteral(value)) {
            throw new BusinessException("当前模式为域名，请输入域名或切换为IP模式");
        }
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.contains(":") || value.contains("/") || value.contains("?") || value.contains("#")) {
            throw new BusinessException("请输入有效的域名");
        }

        String asciiDomain;
        try {
            asciiDomain = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase();
        } catch (IllegalArgumentException e) {
            throw new BusinessException("请输入有效的域名");
        }

        if (asciiDomain.length() > 253 || !asciiDomain.contains(".")) {
            throw new BusinessException("请输入有效的公网域名");
        }
        String[] labels = asciiDomain.split("\\.");
        for (String label : labels) {
            if (!DOMAIN_LABEL_PATTERN.matcher(label).matches()) {
                throw new BusinessException("请输入有效的域名");
            }
        }
        return asciiDomain;
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

    private Map<Object, Object> normalizeAmapResponse(Map<Object, Object> resMap) {
        normalizeScalarField(resMap, "country");
        normalizeScalarField(resMap, "province");
        normalizeScalarField(resMap, "city");
        normalizeScalarField(resMap, "adcode");
        normalizeScalarField(resMap, "rectangle");
        return resMap;
    }

    private void normalizeScalarField(Map<Object, Object> resMap, String key) {
        resMap.put(key, normalizeScalarValue(resMap.get(key)));
    }

    private Object normalizeScalarValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Collection<?> collection) {
            if (collection.isEmpty()) {
                return "";
            }
            return collection.iterator().next();
        }
        return value;
    }

    private IpLocationVo toIpLocationVo(Map<Object, Object> locationMap) {
        IpLocationVo location = new IpLocationVo();
        location.setProvider("amap");
        location.setStatus(getStringValue(locationMap, "status"));
        location.setInfo(getStringValue(locationMap, "info"));
        location.setCountry(getStringValue(locationMap, "country"));
        location.setProvince(getStringValue(locationMap, "province"));
        location.setCity(getStringValue(locationMap, "city"));
        location.setAdcode(getStringValue(locationMap, "adcode"));
        location.setRectangle(getStringValue(locationMap, "rectangle"));
        location.setRaw(toStringKeyMap(locationMap));
        return location;
    }

    private String getStringValue(Map<Object, Object> map, String key) {
        Object value = normalizeScalarValue(map.get(key));
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> toStringKeyMap(Map<Object, Object> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), normalizeScalarValue(value)));
        return result;
    }

    private int countChar(String value, char target) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == target) {
                count++;
            }
        }
        return count;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<Object, Object> getWeather() {
        // 查询请求ip
        Map<Object, Object> ipAddress = getIpAddress();
        // 获取ip的城市编码
        String adcode = String.valueOf(ipAddress.get("adcode"));
        if (StringUtils.isBlank(adcode)) {
            adcode = "110000";
        }

        Map<Object, Object> resMap = this.getWeather(adcode);
        Object info = resMap.get("info");
        if (!"OK".equals(info)) {
            return getWeather("110000");
        }
        return resMap;
    }

    public Map<Object, Object> getWeather(String adcode) {
        Map<Object, Object> adcodeCache = (Map<Object, Object>) RedisUtil.get(ExternalRedisKeyEnum.CITY_WEATHER_KEY.getKey(adcode));
        if (adcodeCache != null) {
            return adcodeCache;
        }
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("key", this.amapKey);
        paramMap.put("city", adcode);
        String res = HttpUtil.get("https://restapi.amap.com/v3/weather/weatherInfo", paramMap);
        Map<Object, Object> resMap = (Map<Object, Object>) JSONUtil.toBean(res, Map.class);
        // 城市天气缓存3小时
        ExternalRedisKeyEnum.CITY_WEATHER_KEY.setStringValue(resMap, adcode);
        return resMap;
    }

    @Override
    public Map<Object, Object> getMonitorsByUptimeRobot(Map<String, Object> bodyParam) {
        Map<Object, Object> monitorsCache = (Map<Object, Object>) RedisUtil.get(ExternalRedisKeyEnum.SITE_MONITORS_KEY.getKey());
        if (monitorsCache != null) {
            return monitorsCache;
        }

        bodyParam.put("api_key", this.uptimeRobotKey);
        String res = HttpUtil.post("https://api.uptimerobot.com/v2/getMonitors", bodyParam);
        Map<Object, Object> resMap = (Map<Object, Object>) JSONUtil.toBean(res, Map.class);
        // 缓存个人站点的监控信息
        ExternalRedisKeyEnum.SITE_MONITORS_KEY.setStringValue(resMap);
        return resMap;
    }

    @Override
    public Map<Object, Object> getDailyHot(String source) {
        return dailyHotService.getDailyHot(source);
    }

    @Override
    public List<WebsiteNavigationListVo> querySharedWebsiteList() {
        return baseWebsiteNavigationClient.querySharedWebsiteList();
    }
}
