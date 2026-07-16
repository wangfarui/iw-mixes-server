package com.itwray.iw.external.service.impl;

import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpStatus;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.model.enums.StockTrackerApiCodeEnum;
import com.itwray.iw.external.model.vo.StockTrackerCandleSeriesVo;
import com.itwray.iw.external.model.vo.StockTrackerQuoteVo;
import com.itwray.iw.external.service.StockTrackerService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 股票跟踪服务实现。
 *
 * @author wray
 * @since 2026/7/1
 */
@Service
public class StockTrackerServiceImpl implements StockTrackerService {

    private static final String SOURCE_NAME = "东方财富";

    private static final String TENCENT_SOURCE_NAME = "腾讯行情";

    private static final String QUOTE_URL = "https://push2.eastmoney.com/api/qt/stock/get";

    private static final String KLINE_URL = "https://push2his.eastmoney.com/api/qt/stock/kline/get";

    private static final String TENCENT_QUOTE_URL = "https://qt.gtimg.cn/q";

    private static final String TENCENT_KLINE_URL = "https://web.ifzq.gtimg.cn/appstock/app/kline/kline";

    private static final String TENCENT_MINUTE_URL = "https://web.ifzq.gtimg.cn/appstock/app/minute/query";

    private static final String YAHOO_CHART_URL = "https://query2.finance.yahoo.com/v8/finance/chart/";

    private static final String BROWSER_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static final String YAHOO_USER_AGENT = "Mozilla/5.0";

    private static final String QUOTE_FIELDS = "f43,f44,f45,f46,f47,f48,f57,f58,f60,f86,f169,f170,f152";

    private static final String KLINE_FIELDS_1 = "f1,f2,f3,f4,f5,f6";

    private static final String KLINE_FIELDS_2 = "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61";

    private static final int HTTP_TIMEOUT_MS = 5000;

    private static final int MAX_BATCH_SYMBOLS = 20;

    private static final int MAX_KLINE_LIMIT = 800;

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    private static final Pattern SIX_DIGIT_SYMBOL = Pattern.compile("^\\d{6}$");

    private static final Pattern HK_SYMBOL = Pattern.compile("^\\d{1,5}$");

    private static final Pattern US_SYMBOL = Pattern.compile("^[A-Z][A-Z0-9.-]{0,14}$");

    private static final DateTimeFormatter MINUTE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final Map<String, IntervalSpec> INTERVAL_SPECS = createIntervalSpecs();

    @Override
    public GeneralResponse<StockTrackerQuoteVo> queryQuote(String symbol) {
        try {
            return GeneralResponse.success(fetchQuote(symbol));
        } catch (StockTrackerException e) {
            return new GeneralResponse<>(e.getApiCode().getCode(), e.getMessage());
        } catch (Exception e) {
            return new GeneralResponse<>(StockTrackerApiCodeEnum.SOURCE_FAILED);
        }
    }

    @Override
    public GeneralResponse<List<StockTrackerQuoteVo>> queryBatchQuotes(String symbols) {
        List<String> normalizedSymbols = parseBatchSymbols(symbols);
        if (normalizedSymbols.size() > MAX_BATCH_SYMBOLS) {
            return new GeneralResponse<>(StockTrackerApiCodeEnum.TOO_MANY_SYMBOLS);
        }

        List<StockTrackerQuoteVo> quotes = new ArrayList<>();
        for (String symbol : normalizedSymbols) {
            try {
                quotes.add(fetchQuote(symbol));
            } catch (StockTrackerException ignored) {
                // Ignore one bad symbol in batch mode; the caller still gets usable quotes for the rest.
            }
        }
        if (quotes.isEmpty()) {
            return new GeneralResponse<>(StockTrackerApiCodeEnum.NO_DATA);
        }
        return GeneralResponse.success(quotes);
    }

    @Override
    public GeneralResponse<StockTrackerCandleSeriesVo> queryCandles(String symbol, String interval, Integer limit,
                                                                    String endTime) {
        try {
            IntervalSpec intervalSpec = normalizeInterval(interval);
            int normalizedLimit = normalizeLimit(limit, intervalSpec);
            LocalDate endDate = normalizeEndDate(endTime, intervalSpec.interval());
            int fetchLimit = "intraday".equals(intervalSpec.interval())
                    ? normalizedLimit : Math.min(MAX_KLINE_LIMIT + 1, normalizedLimit + 1);
            StockTrackerCandleSeriesVo series = fetchCandles(symbol, intervalSpec, fetchLimit, endDate);
            applyCandlePage(series, normalizedLimit, intervalSpec.interval());
            return GeneralResponse.success(series);
        } catch (StockTrackerException e) {
            return new GeneralResponse<>(e.getApiCode().getCode(), e.getMessage());
        } catch (Exception e) {
            return new GeneralResponse<>(StockTrackerApiCodeEnum.SOURCE_FAILED);
        }
    }

    private StockTrackerQuoteVo fetchQuote(String rawSymbol) {
        StockSymbol stockSymbol = normalizeSymbol(rawSymbol);
        if ("US".equals(stockSymbol.market())) {
            return fetchTencentQuote(stockSymbol, null);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("secid", stockSymbol.secid());
        params.put("fltt", 2);
        params.put("invt", 2);
        params.put("fields", QUOTE_FIELDS);

        JSONObject data;
        try {
            data = requestEastMoneyData(QUOTE_URL, params);
        } catch (StockTrackerException e) {
            if (e.getApiCode() != StockTrackerApiCodeEnum.SOURCE_FAILED) {
                throw e;
            }
            return fetchTencentQuote(stockSymbol, "东方财富行情暂不可用，已自动切换到腾讯行情。");
        }
        StockTrackerQuoteVo vo = new StockTrackerQuoteVo();
        vo.setMarket(stockSymbol.market());
        vo.setExchange(stockSymbol.exchange());
        vo.setSecid(stockSymbol.secid());
        vo.setSymbol(stockSymbol.symbol());
        vo.setName(data.getStr("f58", stockSymbol.symbol()));
        vo.setCurrency(stockSymbol.currency());
        vo.setPrice(readDecimal(data, "f43"));
        vo.setHigh(readDecimal(data, "f44"));
        vo.setLow(readDecimal(data, "f45"));
        vo.setOpen(readDecimal(data, "f46"));
        vo.setVolume(readDecimal(data, "f47"));
        vo.setAmount(readDecimal(data, "f48"));
        vo.setPreviousClose(readDecimal(data, "f60"));
        vo.setChange(readDecimal(data, "f169"));
        vo.setChangePercent(readDecimal(data, "f170"));
        vo.setAsOf(parseEpochSecond(data.getObj("f86")));
        vo.setSource(SOURCE_NAME);
        vo.getWarnings().add("行情来自东方财富公开接口，仅供查看参考，不构成投资建议。");

        if (vo.getPrice() == null || StrUtil.isBlank(vo.getName())) {
            throw new StockTrackerException(StockTrackerApiCodeEnum.NO_DATA);
        }
        return vo;
    }

    private StockTrackerCandleSeriesVo fetchCandles(String rawSymbol, IntervalSpec intervalSpec, int limit,
                                                    LocalDate endDate) {
        StockSymbol stockSymbol = normalizeSymbol(rawSymbol);
        if ("US".equals(stockSymbol.market())) {
            return fetchYahooCandles(stockSymbol, intervalSpec, limit, endDate, null);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("secid", stockSymbol.secid());
        params.put("klt", intervalSpec.klt());
        params.put("fqt", 0);
        params.put("lmt", limit);
        params.put("end", endDate == null ? "20500101" : endDate.format(DateTimeFormatter.BASIC_ISO_DATE));
        params.put("fields1", KLINE_FIELDS_1);
        params.put("fields2", KLINE_FIELDS_2);

        JSONObject data;
        try {
            data = requestEastMoneyData(KLINE_URL, params);
        } catch (StockTrackerException e) {
            if (e.getApiCode() != StockTrackerApiCodeEnum.SOURCE_FAILED) {
                throw e;
            }
            if ("intraday".equals(intervalSpec.interval())) {
                return fetchTencentIntradayCandles(stockSymbol, intervalSpec, "东方财富分时暂不可用，已自动切换到腾讯行情。");
            }
            if (StrUtil.isBlank(intervalSpec.tencentKlineType())) {
                throw e;
            }
            return fetchTencentCandles(stockSymbol, intervalSpec, limit, endDate,
                    "东方财富K线暂不可用，已自动切换到腾讯行情。");
        }
        JSONArray rows = data.getJSONArray("klines");
        if (rows == null || rows.isEmpty()) {
            throw new StockTrackerException(StockTrackerApiCodeEnum.NO_DATA);
        }

        StockTrackerCandleSeriesVo vo = new StockTrackerCandleSeriesVo();
        vo.setMarket(stockSymbol.market());
        vo.setExchange(stockSymbol.exchange());
        vo.setSecid(stockSymbol.secid());
        vo.setSymbol(stockSymbol.symbol());
        vo.setName(data.getStr("name", stockSymbol.symbol()));
        vo.setInterval(intervalSpec.interval());
        vo.setIntervalLabel(intervalSpec.label());
        vo.setAdjust("none");
        vo.setSource(SOURCE_NAME);
        vo.setGeneratedAt(OffsetDateTime.now(CHINA_ZONE));
        vo.getWarnings().add("K线来自东方财富公开接口，价格为不复权数据。");

        for (Object rowObj : rows) {
            StockTrackerCandleSeriesVo.Candle candle = parseCandle(String.valueOf(rowObj), intervalSpec);
            if (candle != null) {
                vo.getCandles().add(candle);
            }
        }
        if (vo.getCandles().isEmpty()) {
            throw new StockTrackerException(StockTrackerApiCodeEnum.NO_DATA);
        }
        return vo;
    }

    private JSONObject requestEastMoneyData(String url, Map<String, Object> params) {
        try (HttpResponse response = HttpRequest.get(url)
                .form(params)
                .timeout(HTTP_TIMEOUT_MS)
                .header("Referer", "https://quote.eastmoney.com/")
                .header("User-Agent", BROWSER_USER_AGENT)
                .execute()) {
            if (response.getStatus() >= HttpStatus.HTTP_BAD_REQUEST) {
                throw new StockTrackerException(StockTrackerApiCodeEnum.SOURCE_FAILED);
            }
            JSONObject root = JSONUtil.parseObj(response.body());
            if (root.getInt("rc", -1) != 0) {
                throw new StockTrackerException(StockTrackerApiCodeEnum.SOURCE_FAILED);
            }
            JSONObject data = root.getJSONObject("data");
            if (data == null || data.isEmpty()) {
                throw new StockTrackerException(StockTrackerApiCodeEnum.NO_DATA);
            }
            return data;
        } catch (StockTrackerException e) {
            throw e;
        } catch (Exception e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                throw new StockTrackerException(StockTrackerApiCodeEnum.SOURCE_FAILED);
            }
            throw new StockTrackerException(StockTrackerApiCodeEnum.SOURCE_FAILED);
        }
    }

    private StockTrackerQuoteVo fetchTencentQuote(StockSymbol stockSymbol, String fallbackWarning) {
        String code = toTencentCode(stockSymbol);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("q", code);
        try (HttpResponse response = HttpRequest.get(TENCENT_QUOTE_URL)
                .form(params)
                .charset(CharsetUtil.CHARSET_GBK)
                .timeout(HTTP_TIMEOUT_MS)
                .header("Referer", "https://finance.qq.com/")
                .header("User-Agent", BROWSER_USER_AGENT)
                .execute()) {
            if (response.getStatus() >= HttpStatus.HTTP_BAD_REQUEST) {
                throw new StockTrackerException(StockTrackerApiCodeEnum.SOURCE_FAILED);
            }
            String body = response.body();
            int start = body.indexOf('"');
            int end = body.lastIndexOf('"');
            if (start < 0 || end <= start) {
                throw new StockTrackerException(StockTrackerApiCodeEnum.NO_DATA);
            }
            String[] values = body.substring(start + 1, end).split("~", -1);
            if (values.length < 36) {
                throw new StockTrackerException(StockTrackerApiCodeEnum.NO_DATA);
            }

            StockTrackerQuoteVo vo = new StockTrackerQuoteVo();
            vo.setMarket(stockSymbol.market());
            vo.setExchange(stockSymbol.exchange());
            vo.setSecid(stockSymbol.secid());
            vo.setSymbol(stockSymbol.symbol());
            vo.setName(StrUtil.blankToDefault(values[1], stockSymbol.symbol()));
            vo.setCurrency(stockSymbol.currency());
            vo.setPrice(parseDecimal(values[3]));
            vo.setPreviousClose(parseDecimal(values[4]));
            vo.setOpen(parseDecimal(values[5]));
            vo.setChange(parseDecimal(getArrayValue(values, 31)));
            vo.setChangePercent(parseDecimal(getArrayValue(values, 32)));
            vo.setHigh(parseDecimal(getArrayValue(values, 33)));
            vo.setLow(parseDecimal(getArrayValue(values, 34)));
            applyTencentVolumeAndAmount(vo, stockSymbol, getArrayValue(values, 35),
                    getArrayValue(values, 36), getArrayValue(values, 37));
            vo.setAsOf(parseTencentQuoteTime(getArrayValue(values, 30), stockSymbol.zoneId()));
            vo.setSource(TENCENT_SOURCE_NAME);
            if (StrUtil.isNotBlank(fallbackWarning)) {
                vo.getWarnings().add(fallbackWarning);
            }
            vo.getWarnings().add("行情来自腾讯公开接口，仅供查看参考，不构成投资建议。");
            if (vo.getPrice() == null || StrUtil.isBlank(vo.getName())) {
                throw new StockTrackerException(StockTrackerApiCodeEnum.NO_DATA);
            }
            return vo;
        } catch (StockTrackerException e) {
            throw e;
        } catch (Exception e) {
            throw new StockTrackerException(StockTrackerApiCodeEnum.SOURCE_FAILED);
        }
    }

    private StockTrackerCandleSeriesVo fetchTencentCandles(StockSymbol stockSymbol, IntervalSpec intervalSpec,
                                                          int limit, LocalDate endDate, String fallbackWarning) {
        String code = toTencentCode(stockSymbol);
        Map<String, Object> params = new LinkedHashMap<>();
        String formattedEnd = endDate == null ? "" : endDate.toString();
        params.put("param", code + "," + intervalSpec.tencentKlineType() + ",," + formattedEnd + "," + limit);
        try (HttpResponse response = HttpRequest.get(TENCENT_KLINE_URL)
                .form(params)
                .timeout(HTTP_TIMEOUT_MS)
                .header("Referer", "https://gu.qq.com/")
                .header("User-Agent", BROWSER_USER_AGENT)
                .execute()) {
            if (response.getStatus() >= HttpStatus.HTTP_BAD_REQUEST) {
                throw new StockTrackerException(StockTrackerApiCodeEnum.SOURCE_FAILED);
            }
            JSONObject root = JSONUtil.parseObj(response.body());
            if (root.getInt("code", -1) != 0) {
                throw new StockTrackerException(StockTrackerApiCodeEnum.SOURCE_FAILED);
            }
            JSONObject stockData = root.getJSONObject("data").getJSONObject(code);
            if (stockData == null) {
                throw new StockTrackerException(StockTrackerApiCodeEnum.NO_DATA);
            }
            JSONArray rows = stockData.getJSONArray(intervalSpec.tencentKlineType());
            if (rows == null || rows.isEmpty()) {
                throw new StockTrackerException(StockTrackerApiCodeEnum.NO_DATA);
            }

            StockTrackerCandleSeriesVo vo = new StockTrackerCandleSeriesVo();
            vo.setMarket(stockSymbol.market());
            vo.setExchange(stockSymbol.exchange());
            vo.setSecid(stockSymbol.secid());
            vo.setSymbol(stockSymbol.symbol());
            vo.setName(readTencentKlineName(stockData, code, stockSymbol.symbol()));
            vo.setInterval(intervalSpec.interval());
            vo.setIntervalLabel(intervalSpec.label());
            vo.setAdjust("none");
            vo.setSource(TENCENT_SOURCE_NAME);
            vo.setGeneratedAt(OffsetDateTime.now(CHINA_ZONE));
            if (StrUtil.isNotBlank(fallbackWarning)) {
                vo.getWarnings().add(fallbackWarning);
            }
            vo.getWarnings().add("K线来自腾讯公开接口，价格为不复权数据。");

            BigDecimal previousClose = null;
            for (Object rowObj : rows) {
                JSONArray row = JSONUtil.parseArray(rowObj);
                StockTrackerCandleSeriesVo.Candle candle = parseTencentKlineCandle(row, intervalSpec, previousClose);
                if (candle != null) {
                    previousClose = candle.getClose();
                    vo.getCandles().add(candle);
                }
            }
            if (vo.getCandles().isEmpty()) {
                throw new StockTrackerException(StockTrackerApiCodeEnum.NO_DATA);
            }
            return vo;
        } catch (StockTrackerException e) {
            throw e;
        } catch (Exception e) {
            throw new StockTrackerException(StockTrackerApiCodeEnum.SOURCE_FAILED);
        }
    }

    private StockTrackerCandleSeriesVo fetchTencentIntradayCandles(StockSymbol stockSymbol, IntervalSpec intervalSpec,
                                                                   String fallbackWarning) {
        String code = toTencentCode(stockSymbol);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("code", code);
        try (HttpResponse response = HttpRequest.get(TENCENT_MINUTE_URL)
                .form(params)
                .timeout(HTTP_TIMEOUT_MS)
                .header("Referer", "https://gu.qq.com/")
                .header("User-Agent", BROWSER_USER_AGENT)
                .execute()) {
            if (response.getStatus() >= HttpStatus.HTTP_BAD_REQUEST) {
                throw new StockTrackerException(StockTrackerApiCodeEnum.SOURCE_FAILED);
            }
            JSONObject root = JSONUtil.parseObj(response.body());
            if (root.getInt("code", -1) != 0) {
                throw new StockTrackerException(StockTrackerApiCodeEnum.SOURCE_FAILED);
            }
            JSONObject stockData = root.getJSONObject("data").getJSONObject(code);
            JSONObject minuteData = stockData == null ? null : stockData.getJSONObject("data");
            JSONArray rows = minuteData == null ? null : minuteData.getJSONArray("data");
            String tradeDate = readTencentMinuteTradeDate(minuteData == null ? null : minuteData.getStr("date"), stockData, code);
            if (rows == null || rows.isEmpty() || StrUtil.isBlank(tradeDate)) {
                throw new StockTrackerException(StockTrackerApiCodeEnum.NO_DATA);
            }

            StockTrackerCandleSeriesVo vo = new StockTrackerCandleSeriesVo();
            vo.setMarket(stockSymbol.market());
            vo.setExchange(stockSymbol.exchange());
            vo.setSecid(stockSymbol.secid());
            vo.setSymbol(stockSymbol.symbol());
            vo.setName(readTencentKlineName(stockData, code, stockSymbol.symbol()));
            vo.setInterval(intervalSpec.interval());
            vo.setIntervalLabel(intervalSpec.label());
            vo.setAdjust("none");
            vo.setSource(TENCENT_SOURCE_NAME);
            vo.setGeneratedAt(OffsetDateTime.now(CHINA_ZONE));
            if (StrUtil.isNotBlank(fallbackWarning)) {
                vo.getWarnings().add(fallbackWarning);
            }
            vo.getWarnings().add("当日分时来自腾讯公开接口，并按分钟价格序列换算为K线。");

            BigDecimal previousPrice = null;
            BigDecimal previousVolume = BigDecimal.ZERO;
            BigDecimal previousAmount = BigDecimal.ZERO;
            for (Object rowObj : rows) {
                StockTrackerCandleSeriesVo.Candle candle = parseTencentMinuteCandle(
                        String.valueOf(rowObj), tradeDate, previousPrice, previousVolume, previousAmount);
                if (candle != null) {
                    previousPrice = candle.getClose();
                    previousVolume = previousVolume.add(candle.getVolume() == null ? BigDecimal.ZERO : candle.getVolume());
                    previousAmount = previousAmount.add(candle.getAmount() == null ? BigDecimal.ZERO : candle.getAmount());
                    vo.getCandles().add(candle);
                }
            }
            if (vo.getCandles().isEmpty()) {
                throw new StockTrackerException(StockTrackerApiCodeEnum.NO_DATA);
            }
            return vo;
        } catch (StockTrackerException e) {
            throw e;
        } catch (Exception e) {
            throw new StockTrackerException(StockTrackerApiCodeEnum.SOURCE_FAILED);
        }
    }

    private StockTrackerCandleSeriesVo fetchYahooCandles(StockSymbol stockSymbol, IntervalSpec intervalSpec,
                                                         int limit, LocalDate endDate, String fallbackWarning) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("interval", toYahooInterval(intervalSpec));
        if ("intraday".equals(intervalSpec.interval())) {
            params.put("range", "1d");
        } else {
            LocalDate resolvedEnd = endDate == null ? LocalDate.now(stockSymbol.zoneId()) : endDate;
            LocalDate resolvedStart = calculateYahooStartDate(resolvedEnd, intervalSpec.interval(), limit);
            params.put("period1", resolvedStart.atStartOfDay(stockSymbol.zoneId()).toEpochSecond());
            params.put("period2", resolvedEnd.plusDays(1).atStartOfDay(stockSymbol.zoneId()).toEpochSecond());
        }
        try (HttpResponse response = HttpRequest.get(YAHOO_CHART_URL + stockSymbol.yahooSymbol())
                .form(params)
                .timeout(HTTP_TIMEOUT_MS)
                .header("User-Agent", YAHOO_USER_AGENT)
                .execute()) {
            if (response.getStatus() >= HttpStatus.HTTP_BAD_REQUEST) {
                throw new StockTrackerException(StockTrackerApiCodeEnum.SOURCE_FAILED);
            }
            JSONObject root = JSONUtil.parseObj(response.body());
            JSONObject chartRoot = root.getJSONObject("chart");
            JSONArray result = chartRoot == null ? null : chartRoot.getJSONArray("result");
            JSONObject data = result == null || result.isEmpty() ? null : result.getJSONObject(0);
            if (data == null) {
                throw new StockTrackerException(StockTrackerApiCodeEnum.NO_DATA);
            }

            JSONObject meta = data.getJSONObject("meta");
            JSONArray timestamps = data.getJSONArray("timestamp");
            JSONObject indicators = data.getJSONObject("indicators");
            JSONArray quoteArray = indicators == null ? null : indicators.getJSONArray("quote");
            JSONObject quote = quoteArray == null || quoteArray.isEmpty() ? null : quoteArray.getJSONObject(0);
            if (timestamps == null || timestamps.isEmpty() || quote == null) {
                throw new StockTrackerException(StockTrackerApiCodeEnum.NO_DATA);
            }

            StockTrackerCandleSeriesVo vo = new StockTrackerCandleSeriesVo();
            vo.setMarket(stockSymbol.market());
            vo.setExchange(stockSymbol.exchange());
            vo.setSecid(stockSymbol.secid());
            vo.setSymbol(stockSymbol.symbol());
            vo.setName(readYahooName(meta, stockSymbol.symbol()));
            vo.setInterval(intervalSpec.interval());
            vo.setIntervalLabel(intervalSpec.label());
            vo.setAdjust("none");
            vo.setSource("Yahoo Finance");
            vo.setGeneratedAt(OffsetDateTime.now(CHINA_ZONE));
            if (StrUtil.isNotBlank(fallbackWarning)) {
                vo.getWarnings().add(fallbackWarning);
            }
            vo.getWarnings().add("美股K线来自Yahoo Finance公开接口，价格为不复权数据。");

            JSONArray opens = quote.getJSONArray("open");
            JSONArray closes = quote.getJSONArray("close");
            JSONArray highs = quote.getJSONArray("high");
            JSONArray lows = quote.getJSONArray("low");
            JSONArray volumes = quote.getJSONArray("volume");
            ZoneId marketZone = parseZone(meta == null ? null : meta.getStr("exchangeTimezoneName"), stockSymbol.zoneId());
            BigDecimal previousClose = null;
            for (int i = 0; i < timestamps.size(); i++) {
                StockTrackerCandleSeriesVo.Candle candle = parseYahooCandle(
                        timestamps, opens, closes, highs, lows, volumes, i, intervalSpec, marketZone, previousClose);
                if (candle != null) {
                    previousClose = candle.getClose();
                    vo.getCandles().add(candle);
                }
            }
            if (vo.getCandles().size() > limit) {
                vo.setCandles(new ArrayList<>(vo.getCandles().subList(vo.getCandles().size() - limit, vo.getCandles().size())));
            }
            if (vo.getCandles().isEmpty()) {
                throw new StockTrackerException(StockTrackerApiCodeEnum.NO_DATA);
            }
            return vo;
        } catch (StockTrackerException e) {
            throw e;
        } catch (Exception e) {
            throw new StockTrackerException(StockTrackerApiCodeEnum.SOURCE_FAILED);
        }
    }

    private StockTrackerCandleSeriesVo.Candle parseCandle(String row, IntervalSpec intervalSpec) {
        String[] values = row.split(",");
        if (values.length < 6) {
            return null;
        }

        StockTrackerCandleSeriesVo.Candle candle = new StockTrackerCandleSeriesVo.Candle();
        candle.setTradeTime(values[0]);
        candle.setTime(parseTradeTime(values[0], intervalSpec.klt()));
        candle.setOpen(parseDecimal(values[1]));
        candle.setClose(parseDecimal(values[2]));
        candle.setHigh(parseDecimal(values[3]));
        candle.setLow(parseDecimal(values[4]));
        candle.setVolume(parseDecimal(values[5]));
        if (values.length > 6) {
            candle.setAmount(parseDecimal(values[6]));
        }
        if (values.length > 7) {
            candle.setAmplitude(parseDecimal(values[7]));
        }
        if (values.length > 8) {
            candle.setChangePercent(parseDecimal(values[8]));
        }
        if (values.length > 9) {
            candle.setChange(parseDecimal(values[9]));
        }
        if (values.length > 10) {
            candle.setTurnoverRate(parseDecimal(values[10]));
        }
        if (candle.getTime() == null || candle.getOpen() == null || candle.getClose() == null
                || candle.getHigh() == null || candle.getLow() == null) {
            return null;
        }
        return candle;
    }

    private StockTrackerCandleSeriesVo.Candle parseTencentKlineCandle(JSONArray row, IntervalSpec intervalSpec,
                                                                      BigDecimal previousClose) {
        if (row == null || row.size() < 6) {
            return null;
        }

        StockTrackerCandleSeriesVo.Candle candle = new StockTrackerCandleSeriesVo.Candle();
        candle.setTradeTime(row.getStr(0));
        candle.setTime(parseTradeTime(row.getStr(0), intervalSpec.klt()));
        candle.setOpen(parseDecimal(row.getObj(1)));
        candle.setClose(parseDecimal(row.getObj(2)));
        candle.setHigh(parseDecimal(row.getObj(3)));
        candle.setLow(parseDecimal(row.getObj(4)));
        candle.setVolume(parseDecimal(row.getObj(5)));
        if (previousClose != null && previousClose.signum() > 0 && candle.getClose() != null) {
            candle.setChange(candle.getClose().subtract(previousClose));
            candle.setChangePercent(candle.getChange()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(previousClose, 2, java.math.RoundingMode.HALF_UP));
        }
        if (previousClose != null && previousClose.signum() > 0 && candle.getHigh() != null && candle.getLow() != null) {
            candle.setAmplitude(candle.getHigh()
                    .subtract(candle.getLow())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(previousClose, 2, java.math.RoundingMode.HALF_UP));
        }
        if (candle.getTime() == null || candle.getOpen() == null || candle.getClose() == null
                || candle.getHigh() == null || candle.getLow() == null) {
            return null;
        }
        return candle;
    }

    private StockTrackerCandleSeriesVo.Candle parseTencentMinuteCandle(String row, String tradeDate,
                                                                       BigDecimal previousPrice,
                                                                       BigDecimal previousVolume,
                                                                       BigDecimal previousAmount) {
        String[] values = StrUtil.blankToDefault(row, "").split("\\s+");
        if (values.length < 4 || values[0].length() != 4) {
            return null;
        }
        BigDecimal price = parseDecimal(values[1]);
        BigDecimal cumulativeVolume = parseDecimal(values[2]);
        BigDecimal cumulativeAmount = parseDecimal(values[3]);
        if (price == null || cumulativeVolume == null || cumulativeAmount == null) {
            return null;
        }

        String tradeTime = formatTencentMinuteTradeTime(tradeDate, values[0]);
        if (tradeTime == null) {
            return null;
        }
        BigDecimal open = previousPrice == null ? price : previousPrice;

        StockTrackerCandleSeriesVo.Candle candle = new StockTrackerCandleSeriesVo.Candle();
        candle.setTradeTime(tradeTime);
        candle.setTime(parseTradeTime(tradeTime, 1));
        candle.setOpen(open);
        candle.setClose(price);
        candle.setHigh(open.max(price));
        candle.setLow(open.min(price));
        candle.setVolume(cumulativeVolume.subtract(previousVolume == null ? BigDecimal.ZERO : previousVolume).max(BigDecimal.ZERO));
        candle.setAmount(cumulativeAmount.subtract(previousAmount == null ? BigDecimal.ZERO : previousAmount).max(BigDecimal.ZERO));
        if (previousPrice != null && previousPrice.signum() > 0) {
            candle.setChange(price.subtract(previousPrice));
            candle.setChangePercent(candle.getChange()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(previousPrice, 2, java.math.RoundingMode.HALF_UP));
            candle.setAmplitude(candle.getHigh()
                    .subtract(candle.getLow())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(previousPrice, 2, java.math.RoundingMode.HALF_UP));
        }
        if (candle.getTime() == null) {
            return null;
        }
        return candle;
    }

    private StockTrackerCandleSeriesVo.Candle parseYahooCandle(JSONArray timestamps,
                                                               JSONArray opens,
                                                               JSONArray closes,
                                                               JSONArray highs,
                                                               JSONArray lows,
                                                               JSONArray volumes,
                                                               int index,
                                                               IntervalSpec intervalSpec,
                                                               ZoneId marketZone,
                                                               BigDecimal previousClose) {
        BigDecimal timestamp = parseDecimal(timestamps.getObj(index));
        BigDecimal open = readArrayDecimal(opens, index);
        BigDecimal close = readArrayDecimal(closes, index);
        BigDecimal high = readArrayDecimal(highs, index);
        BigDecimal low = readArrayDecimal(lows, index);
        if (timestamp == null || open == null || close == null || high == null || low == null) {
            return null;
        }

        StockTrackerCandleSeriesVo.Candle candle = new StockTrackerCandleSeriesVo.Candle();
        long epochSecond = timestamp.longValue();
        candle.setTime(epochSecond);
        candle.setTradeTime(formatYahooTradeTime(epochSecond, intervalSpec, marketZone));
        candle.setOpen(open);
        candle.setClose(close);
        candle.setHigh(high);
        candle.setLow(low);
        candle.setVolume(readArrayDecimal(volumes, index));
        if (previousClose != null && previousClose.signum() > 0) {
            candle.setChange(close.subtract(previousClose));
            candle.setChangePercent(candle.getChange()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(previousClose, 2, java.math.RoundingMode.HALF_UP));
            candle.setAmplitude(high.subtract(low)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(previousClose, 2, java.math.RoundingMode.HALF_UP));
        }
        return candle;
    }

    private List<String> parseBatchSymbols(String symbols) {
        String[] parts = symbols.split(",");
        Set<String> seen = new LinkedHashSet<>();
        for (String part : parts) {
            String normalized = normalizeSymbol(part).symbol();
            seen.add(normalized);
        }
        return new ArrayList<>(seen);
    }

    private StockSymbol normalizeSymbol(String rawSymbol) {
        String value = StrUtil.blankToDefault(rawSymbol, "").trim().toUpperCase(Locale.ROOT);
        value = value.replace(" ", "").replace("_", ".");
        if (value.matches("^[01]\\.\\d{6}$")) {
            String[] parts = value.split("\\.");
            String exchange = "1".equals(parts[0]) ? "SH" : "SZ";
            return createAStockSymbol(parts[1], exchange);
        }
        if (value.matches("^116\\.\\d{1,5}$")) {
            return createHongKongSymbol(value.substring(4));
        }
        if (value.matches("^(105|106|107)\\.[A-Z][A-Z0-9.-]{0,14}$")) {
            String[] parts = value.split("\\.", 2);
            return createUsSymbol(parts[1]);
        }

        if (value.matches("^SH\\d{6}$")) {
            value = value.substring(2) + ".SH";
        } else if (value.matches("^SZ\\d{6}$")) {
            value = value.substring(2) + ".SZ";
        } else if (value.matches("^HK\\d{1,5}$")) {
            value = value.substring(2) + ".HK";
        } else if (value.matches("^(US|NASDAQ|NYSE|AMEX)[:.][A-Z][A-Z0-9.-]{0,14}$")) {
            value = value.substring(value.indexOf(value.contains(":") ? ":" : ".") + 1) + ".US";
        }

        if (value.endsWith(".SH") || value.endsWith(".SZ")) {
            String exchangeHint = value.substring(value.length() - 2);
            value = value.substring(0, value.length() - 3);
            if (!SIX_DIGIT_SYMBOL.matcher(value).matches()) {
                throw new StockTrackerException(StockTrackerApiCodeEnum.INVALID_SYMBOL);
            }
            return createAStockSymbol(value, exchangeHint);
        }
        if (value.endsWith(".HK")) {
            return createHongKongSymbol(value.substring(0, value.length() - 3));
        }
        if (value.endsWith(".US") || value.endsWith(".NASDAQ") || value.endsWith(".NYSE") || value.endsWith(".AMEX")) {
            return createUsSymbol(value.substring(0, value.lastIndexOf(".")));
        }

        if (SIX_DIGIT_SYMBOL.matcher(value).matches()) {
            return createAStockSymbol(value, inferExchange(value));
        }
        if (HK_SYMBOL.matcher(value).matches()) {
            return createHongKongSymbol(value);
        }
        if (US_SYMBOL.matcher(value).matches()) {
            return createUsSymbol(value);
        }

        throw new StockTrackerException(StockTrackerApiCodeEnum.INVALID_SYMBOL);
    }

    private StockSymbol createAStockSymbol(String symbol, String exchange) {
        if ("SH".equals(exchange) && !isShanghaiSymbol(symbol)) {
            throw new StockTrackerException(StockTrackerApiCodeEnum.INVALID_SYMBOL);
        }
        if ("SZ".equals(exchange) && !isShenzhenSymbol(symbol)) {
            throw new StockTrackerException(StockTrackerApiCodeEnum.INVALID_SYMBOL);
        }
        String marketPrefix = "SH".equals(exchange) ? "1" : "0";
        return new StockSymbol(symbol, marketPrefix + "." + symbol, exchange, "A", "CNY",
                exchange.toLowerCase(Locale.ROOT) + symbol, symbol + "." + exchange, CHINA_ZONE);
    }

    private StockSymbol createHongKongSymbol(String symbol) {
        if (!HK_SYMBOL.matcher(symbol).matches()) {
            throw new StockTrackerException(StockTrackerApiCodeEnum.INVALID_SYMBOL);
        }
        String paddedSymbol = StrUtil.padPre(symbol, 5, '0');
        return new StockSymbol(paddedSymbol, "116." + paddedSymbol, "HK", "HK", "HKD",
                "hk" + paddedSymbol, paddedSymbol.substring(1) + ".HK", ZoneId.of("Asia/Hong_Kong"));
    }

    private StockSymbol createUsSymbol(String symbol) {
        String normalized = StrUtil.blankToDefault(symbol, "").toUpperCase(Locale.ROOT);
        if (!US_SYMBOL.matcher(normalized).matches()) {
            throw new StockTrackerException(StockTrackerApiCodeEnum.INVALID_SYMBOL);
        }
        return new StockSymbol(normalized, "105." + normalized, "US", "US", "USD",
                "us" + normalized, normalized, ZoneId.of("America/New_York"));
    }

    private String inferExchange(String symbol) {
        if (isShanghaiSymbol(symbol)) {
            return "SH";
        }
        if (isShenzhenSymbol(symbol)) {
            return "SZ";
        }
        throw new StockTrackerException(StockTrackerApiCodeEnum.INVALID_SYMBOL);
    }

    private boolean isShanghaiSymbol(String symbol) {
        return symbol.startsWith("600") || symbol.startsWith("601") || symbol.startsWith("603")
                || symbol.startsWith("605") || symbol.startsWith("688") || symbol.startsWith("689")
                || symbol.startsWith("501") || symbol.startsWith("502")
                || symbol.startsWith("510") || symbol.startsWith("511") || symbol.startsWith("512")
                || symbol.startsWith("513") || symbol.startsWith("515") || symbol.startsWith("516")
                || symbol.startsWith("517") || symbol.startsWith("518") || symbol.startsWith("520")
                || symbol.startsWith("588") || symbol.startsWith("589");
    }

    private boolean isShenzhenSymbol(String symbol) {
        return symbol.startsWith("000") || symbol.startsWith("001") || symbol.startsWith("002")
                || symbol.startsWith("003") || symbol.startsWith("300") || symbol.startsWith("301")
                || symbol.startsWith("159") || symbol.startsWith("160") || symbol.startsWith("161")
                || symbol.startsWith("162") || symbol.startsWith("163") || symbol.startsWith("164")
                || symbol.startsWith("165") || symbol.startsWith("166") || symbol.startsWith("167")
                || symbol.startsWith("168") || symbol.startsWith("169");
    }

    private IntervalSpec normalizeInterval(String interval) {
        String key = StrUtil.blankToDefault(interval, "intraday").trim().toLowerCase(Locale.ROOT);
        if ("1m".equals(key)) {
            key = "intraday";
        }
        IntervalSpec intervalSpec = INTERVAL_SPECS.get(key);
        if (intervalSpec == null) {
            throw new StockTrackerException(StockTrackerApiCodeEnum.UNSUPPORTED_INTERVAL);
        }
        return intervalSpec;
    }

    private int normalizeLimit(Integer limit, IntervalSpec intervalSpec) {
        if (limit == null) {
            return intervalSpec.defaultLimit();
        }
        return Math.max(20, Math.min(MAX_KLINE_LIMIT, limit));
    }

    LocalDate normalizeEndDate(String endTime, String interval) {
        if (StrUtil.isBlank(endTime) || "intraday".equals(interval)) {
            return null;
        }
        try {
            return LocalDate.parse(endTime.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            throw new StockTrackerException(StockTrackerApiCodeEnum.INVALID_END_TIME);
        }
    }

    void applyCandlePage(StockTrackerCandleSeriesVo series, int limit, String interval) {
        List<StockTrackerCandleSeriesVo.Candle> candles = series.getCandles();
        boolean pageable = !"intraday".equals(interval);
        boolean hasMoreBefore = pageable && candles.size() > limit;
        if (hasMoreBefore) {
            series.setCandles(new ArrayList<>(candles.subList(candles.size() - limit, candles.size())));
            candles = series.getCandles();
        }
        series.setHasMoreBefore(hasMoreBefore);
        if (candles.isEmpty()) {
            return;
        }
        StockTrackerCandleSeriesVo.Candle oldest = candles.get(0);
        StockTrackerCandleSeriesVo.Candle newest = candles.get(candles.size() - 1);
        series.setOldestTime(oldest.getTradeTime());
        series.setNewestTime(newest.getTradeTime());
        if (hasMoreBefore) {
            try {
                LocalDate oldestDate = LocalDate.parse(oldest.getTradeTime().substring(0, 10));
                series.setNextEndTime(oldestDate.minusDays(1).toString());
            } catch (Exception ignored) {
                series.setHasMoreBefore(false);
            }
        }
    }

    private BigDecimal readDecimal(JSONObject data, String key) {
        return parseDecimal(data.getObj(key));
    }

    private BigDecimal parseDecimal(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (StrUtil.isBlank(text) || "-".equals(text)) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal readArrayDecimal(JSONArray array, int index) {
        if (array == null || index < 0 || index >= array.size()) {
            return null;
        }
        return parseDecimal(array.getObj(index));
    }

    private OffsetDateTime parseEpochSecond(Object value) {
        BigDecimal seconds = parseDecimal(value);
        if (seconds == null || seconds.signum() <= 0) {
            return OffsetDateTime.now(CHINA_ZONE);
        }
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(seconds.longValue()), CHINA_ZONE);
    }

    private Long parseTradeTime(String text, int klt) {
        try {
            if (klt < 100 && text.contains(" ")) {
                LocalDateTime dateTime = LocalDateTime.parse(text, MINUTE_TIME_FORMATTER);
                return dateTime.atZone(CHINA_ZONE).toEpochSecond();
            }
            LocalDate date = LocalDate.parse(text.substring(0, 10));
            return date.atStartOfDay(CHINA_ZONE).toEpochSecond();
        } catch (Exception e) {
            return null;
        }
    }

    private OffsetDateTime parseTencentQuoteTime(String text, ZoneId zoneId) {
        if (StrUtil.isBlank(text)) {
            return OffsetDateTime.now(zoneId);
        }
        try {
            String value = text.trim();
            LocalDateTime dateTime;
            if (value.matches("^\\d{14}.*")) {
                dateTime = LocalDateTime.parse(value.substring(0, 14), DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            } else {
                dateTime = LocalDateTime.parse(value.substring(0, 19).replace("/", "-"),
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
            return dateTime.atZone(zoneId).toOffsetDateTime();
        } catch (Exception e) {
            return OffsetDateTime.now(zoneId);
        }
    }

    private String readTencentMinuteTradeDate(String tradeDate, JSONObject stockData, String code) {
        if (StrUtil.isNotBlank(tradeDate)) {
            return tradeDate;
        }
        JSONObject quoteMap = stockData == null ? null : stockData.getJSONObject("qt");
        JSONArray quote = quoteMap == null ? null : quoteMap.getJSONArray(code);
        String quoteTime = quote == null ? null : quote.getStr(30);
        if (StrUtil.isBlank(quoteTime) || quoteTime.length() < 10) {
            return null;
        }
        return quoteTime.substring(0, 10).replace("-", "").replace("/", "");
    }

    private void applyTencentVolumeAndAmount(StockTrackerQuoteVo vo, StockSymbol stockSymbol,
                                             String dealSummary, String volume, String amountText) {
        vo.setVolume(parseDecimal(volume));
        String[] parts = StrUtil.blankToDefault(dealSummary, "").split("/");
        if (parts.length >= 3) {
            vo.setAmount(parseDecimal(parts[2]));
            return;
        }
        BigDecimal amount = parseDecimal(amountText);
        if (amount != null) {
            vo.setAmount("A".equals(stockSymbol.market()) ? amount.multiply(BigDecimal.valueOf(10000)) : amount);
        }
    }

    private String readTencentKlineName(JSONObject stockData, String code, String fallback) {
        JSONObject quoteMap = stockData.getJSONObject("qt");
        if (quoteMap == null) {
            return fallback;
        }
        JSONArray quote = quoteMap.getJSONArray(code);
        if (quote == null || quote.size() < 2) {
            return fallback;
        }
        return StrUtil.blankToDefault(quote.getStr(1), fallback);
    }

    private String readYahooName(JSONObject meta, String fallback) {
        if (meta == null) {
            return fallback;
        }
        return StrUtil.blankToDefault(meta.getStr("longName"), StrUtil.blankToDefault(meta.getStr("shortName"), fallback));
    }

    private String toYahooInterval(IntervalSpec intervalSpec) {
        return switch (intervalSpec.interval()) {
            case "intraday" -> "1m";
            case "weekly" -> "1wk";
            case "monthly" -> "1mo";
            default -> "1d";
        };
    }

    LocalDate calculateYahooStartDate(LocalDate endDate, String interval, int limit) {
        return switch (interval) {
            case "weekly" -> endDate.minusWeeks(limit + 4L);
            case "monthly" -> endDate.minusMonths(limit + 2L);
            default -> endDate.minusDays(limit * 2L + 30L);
        };
    }

    private String formatYahooTradeTime(long epochSecond, IntervalSpec intervalSpec, ZoneId marketZone) {
        LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), marketZone);
        if ("intraday".equals(intervalSpec.interval())) {
            return dateTime.format(MINUTE_TIME_FORMATTER);
        }
        return dateTime.toLocalDate().toString();
    }

    private ZoneId parseZone(String zoneName, ZoneId fallback) {
        if (StrUtil.isBlank(zoneName)) {
            return fallback;
        }
        try {
            return ZoneId.of(zoneName);
        } catch (Exception e) {
            return fallback;
        }
    }

    private String toTencentCode(StockSymbol stockSymbol) {
        return stockSymbol.tencentCode();
    }

    private String formatTencentMinuteTradeTime(String tradeDate, String minuteText) {
        try {
            LocalDate date = LocalDate.parse(tradeDate, DateTimeFormatter.BASIC_ISO_DATE);
            return date + " " + minuteText.substring(0, 2) + ":" + minuteText.substring(2, 4);
        } catch (Exception e) {
            return null;
        }
    }

    private String getArrayValue(String[] values, int index) {
        if (index < 0 || index >= values.length) {
            return null;
        }
        return values[index];
    }

    private static Map<String, IntervalSpec> createIntervalSpecs() {
        Map<String, IntervalSpec> specs = new LinkedHashMap<>();
        specs.put("intraday", new IntervalSpec("intraday", "当日分时", 1, 240, null));
        specs.put("daily", new IntervalSpec("daily", "日K", 101, 180, "day"));
        specs.put("weekly", new IntervalSpec("weekly", "周K", 102, 120, "week"));
        specs.put("monthly", new IntervalSpec("monthly", "月K", 103, 120, "month"));
        return specs;
    }

    private record StockSymbol(String symbol,
                               String secid,
                               String exchange,
                               String market,
                               String currency,
                               String tencentCode,
                               String yahooSymbol,
                               ZoneId zoneId) {
    }

    private record IntervalSpec(String interval, String label, int klt, int defaultLimit, String tencentKlineType) {
    }

    private static class StockTrackerException extends RuntimeException {

        private final StockTrackerApiCodeEnum apiCode;

        StockTrackerException(StockTrackerApiCodeEnum apiCode) {
            super(apiCode.getMessage());
            this.apiCode = apiCode;
        }

        StockTrackerApiCodeEnum getApiCode() {
            return apiCode;
        }
    }
}
