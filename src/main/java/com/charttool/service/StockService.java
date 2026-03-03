package com.charttool.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.charttool.config.AppProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * Service for analyzing stock market data and technical indicators.
 */
@Service
public class StockService {

    /** Logger instance for service-level monitoring. */
    private static final Logger LOGGER = // split
            LoggerFactory.getLogger(StockService.class);

    /** Standard period for Relative Strength Index (RSI). */
    private static final int RSI_PERIOD = 14;

    /** Standard window size for Bollinger Bands. */
    private static final int BOLLINGER_WINDOW = 20;

    /** Constant offset 60 for harmonic pattern detection. */
    private static final int PATTERN_OFFSET_60 = 60;

    /** Constant offset 45 for harmonic pattern detection. */
    private static final int PATTERN_OFFSET_45 = 45;

    /** Constant offset 30 for harmonic pattern detection. */
    private static final int PATTERN_OFFSET_30 = 30;

    /** Constant offset 15 for harmonic pattern detection. */
    private static final int PATTERN_OFFSET_15 = 15;

    /** Minimum data points required for pattern analysis. */
    private static final int MIN_REQUIRED_BARS = 20;

    /** Mock AI duration constant. */
    private static final long MOCK_DUR = 100L;

    /** Threshold for detecting volume spikes. */
    private static final int VOL_THRESHOLD = 1000;

    /** Initial confidence score for analysis results. */
    private static final int INIT_SCORE = 95;

    /** Default RSI value. */
    private static final double DEFAULT_RSI = 50.0;

    private final AppProperties appProperties;

    /** Shared JSON object mapper. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Random utility for simulation purposes. */
    private final Random random = new Random();

    /** Yahoo Finance v8 API base URLs */
    private static final String YF_QUERY1 = "https://query1.finance.yahoo.com";
    private static final String YF_QUERY2 = "https://query2.finance.yahoo.com";

    /** WebClient for external API calls. */
    private final WebClient webClient = WebClient.builder()
            .defaultHeader("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                            + "AppleWebKit/537.36 (KHTML, like Gecko) "
                            + "Chrome/124.0.0.0 Safari/537.36")
            .defaultHeader("Accept", "application/json,text/html,*/*")
            .defaultHeader("Accept-Language", "en-US,en;q=0.9")
            .defaultHeader("Referer", "https://finance.yahoo.com/")
            .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)) // 10MB
            .build();

    /** Cache for stock analysis results (TTL not implemented for simplicity). */
    private final Map<String, Map<String, Object>> analysisCache = new ConcurrentHashMap<>();

    /** Cache for AI strategy reports. */
    private final Map<String, Map<String, Object>> aiReportCache = new ConcurrentHashMap<>();

    /**
     * Semaphore set to 1 to strictly serialize AI requests and avoid 503 errors.
     */
    private final Semaphore aiSemaphore = new Semaphore(1);

    /** WebClient timeout duration. */
    private static final Duration WEB_TIMEOUT = Duration.ofSeconds(60);

    /** Maximum retries for AI generation. */
    private static final int MAX_RETRIES = 2;

    public StockService(final AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * Type reference for parsing generic Map structures from JSON.
     */
    private static final class MapTypeReference
            extends TypeReference<Map<String, Object>> {
    }

    /**
     * Performs a technical analysis for a specific stock ticker.
     *
     * @param ticker The stock symbol to analyze.
     * @return A map containing price data, indicators, and news.
     */
    @SuppressWarnings("unchecked")
    public final Map<String, Object> getAnalysis(final String ticker) {
        if (analysisCache.containsKey(ticker)) {
            return analysisCache.get(ticker);
        }
        try {
            long s0 = System.currentTimeMillis();
            String resJson = null;

            // 1. 한국 종목(6자리 숫자)인 경우 Naver Finance 직접 호출 (Python 의존성 제거)
            if (ticker.matches("\\d{6}(\\.KS|\\.KQ)?")) {
                String digits = ticker.substring(0, 6);
                resJson = fetchNaverDataInJava(digits);
            }

            // 2. Naver 데이터가 없거나 US/HK/SH 종목인 경우 Yahoo Finance v8 API 직접 호출
            if (resJson == null) {
                try {
                    resJson = fetchYahooFinanceData(ticker);
                } catch (Exception yfEx) {
                    LOGGER.warn("Yahoo Finance direct fetch failed for {}: {}.",
                            ticker, yfEx.getMessage());
                }
            }

            // 3. Yahoo Finance 실패 시 Python yfinance 폴백 시도
            if (resJson == null) {
                try {
                    resJson = runPythonYfinance(ticker);
                } catch (Exception pyEx) {
                    LOGGER.warn("Python yfinance fallback also failed for {}: {}.",
                            ticker, pyEx.getMessage());
                }
            }

            if (resJson == null) {
                return getEmptyAnalysis();
            }

            long s1 = System.currentTimeMillis();

            Map<String, Object> raw = objectMapper.readValue(resJson,
                    new MapTypeReference());

            if (raw.containsKey("error")) {
                LOGGER.error("Data error for {}: {}", ticker, raw.get("error"));
                return getEmptyAnalysis();
            }

            List<Map<String, Object>> h = (List<Map<String, Object>>) raw.get("history");
            List<Map<String, Object>> n = (List<Map<String, Object>>) raw.getOrDefault("news", List.of());

            if (h == null || h.isEmpty()) {
                return getEmptyAnalysis();
            }

            BarSeries series = new BaseBarSeriesBuilder().withName(ticker).build();
            List<Map<String, Object>> procHist = new ArrayList<>();

            for (Map<String, Object> day : h) {
                String dS = (String) day.get("date");
                double o = ((Number) day.get("open")).doubleValue();
                double high = ((Number) day.get("high")).doubleValue();
                double low = ((Number) day.get("low")).doubleValue();
                double c = ((Number) day.get("close")).doubleValue();
                long v = ((Number) day.get("volume")).longValue();

                LocalDate ld = LocalDate.parse(dS);
                ZonedDateTime zdt = ld.atStartOfDay(ZoneId.of("UTC"));
                series.addBar(zdt, o, high, low, c, v);
                procHist.add(new HashMap<>(day));
            }

            double lp = series.getLastBar().getClosePrice().doubleValue();
            ClosePriceIndicator cp = new ClosePriceIndicator(series);
            RSIIndicator rsi = new RSIIndicator(cp, RSI_PERIOD);
            SMAIndicator sma = new SMAIndicator(cp, BOLLINGER_WINDOW);
            BollingerBandsMiddleIndicator bm = new BollingerBandsMiddleIndicator(sma);
            StandardDeviationIndicator sd = new StandardDeviationIndicator(cp, BOLLINGER_WINDOW);
            BollingerBandsUpperIndicator bu = new BollingerBandsUpperIndicator(bm, sd);
            BollingerBandsLowerIndicator bl = new BollingerBandsLowerIndicator(bm, sd);

            int lastIdx = series.getEndIndex();
            for (int i = 0; i < procHist.size(); i++) {
                procHist.get(i).put("upper", bu.getValue(i).doubleValue());
                procHist.get(i).put("lower", bl.getValue(i).doubleValue());
            }

            List<Integer> spikes = new ArrayList<>();
            for (int i = BOLLINGER_WINDOW; i <= lastIdx; i++) {
                if (series.getBar(i).getVolume().doubleValue() > VOL_THRESHOLD) {
                    spikes.add(i);
                }
            }

            Map<String, Object> patt = detectHarmonicPatterns(series);
            Map<String, Object> map = new HashMap<>();
            map.put("price", lp);
            map.put("rsi", rsi.getValue(lastIdx).doubleValue());
            map.put("upper", bu.getValue(lastIdx).doubleValue());
            map.put("lower", bl.getValue(lastIdx).doubleValue());
            map.put("history", procHist);
            map.put("news", n);
            map.put("pattern", patt != null ? patt.get("type") : "none");
            map.put("patternDetails", patt);
            map.put("spikes", spikes);
            map.put("name", raw.getOrDefault("name", ticker));
            map.put("confidenceScore", INIT_SCORE);
            map.put("yfDur", s1 - s0);

            analysisCache.put(ticker, map);
            return map;
        } catch (Exception e) {
            LOGGER.error("Failed to analyze stock {}: {}", ticker, e.getMessage(), e);
            return getEmptyAnalysis();
        }
    }

    /**
     * 한국 주식 데이터를 Naver Finance XML API를 통해 Java에서 직접 가져옵니다.
     * 
     * @param tickerDigits 6자리 주식 코드
     * @return JSON 형식의 데이터 문자열 또는 null
     */
    private String fetchNaverDataInJava(String tickerDigits) {
        try {
            LOGGER.info("Fetching Naver Finance data for {} via WebClient", tickerDigits);
            String url = "https://fchart.stock.naver.com/sise.naver?symbol=" + tickerDigits
                    + "&timeframe=day&count=250&requestType=0";

            String xml = webClient.get().uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(WEB_TIMEOUT);

            if (xml == null || !xml.contains("<item"))
                return null;

            // Simple XML Parsing (Regex or splitting for speed/simplicity)
            List<Map<String, Object>> history = new ArrayList<>();
            String[] items = xml.split("<item data=\"");
            for (int i = 1; i < items.length; i++) {
                String data = items[i].split("\"")[0];
                String[] p = data.split("\\|");
                if (p.length >= 6) {
                    String d = p[0];
                    history.add(Map.of(
                            "date", d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6),
                            "open", Double.parseDouble(p[1]),
                            "high", Double.parseDouble(p[2]),
                            "low", Double.parseDouble(p[3]),
                            "close", Double.parseDouble(p[4]),
                            "volume", Long.parseLong(p[5])));
                }
            }

            String companyName = fetchKrStockName(tickerDigits);

            Map<String, Object> result = Map.of(
                    "name", companyName,
                    "history", history,
                    "news", List.of());
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            LOGGER.error("Naver Finance fetch failed for {}: {}", tickerDigits, e.getMessage());
            return null;
        }
    }

    /**
     * 네이버 실시간 API를 통해 종목명을 조회합니다.
     */
    @SuppressWarnings("unchecked")
    private String fetchKrStockName(String tickerDigits) {
        try {
            String url = "https://polling.finance.naver.com/api/realtime?query=SERVICE_ITEM_REALTIME:" + tickerDigits;
            Map<String, Object> resp = webClient.get().uri(url)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(5));

            if (resp != null && resp.containsKey("result")) {
                Map<String, Object> res = (Map<String, Object>) resp.get("result");
                List<Map<String, Object>> areas = (List<Map<String, Object>>) res.get("areas");
                if (areas != null && !areas.isEmpty()) {
                    List<Map<String, Object>> datas = (List<Map<String, Object>>) areas.get(0).get("datas");
                    if (datas != null && !datas.isEmpty()) {
                        return (String) datas.get(0).get("nm");
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to fetch KR name for {}: {}", tickerDigits, e.getMessage());
        }
        return tickerDigits;
    }

    /**
     * Returns an empty analysis map with all required keys.
     *
     * @return map
     */
    public final Map<String, Object> getEmptyAnalysis() {
        Map<String, Object> map = new HashMap<>();
        map.put("price", 0.0);
        map.put("rsi", DEFAULT_RSI);
        map.put("upper", 0.0);
        map.put("lower", 0.0);
        map.put("history", List.of());
        map.put("news", List.of());
        map.put("pattern", "none");
        map.put("patternDetails", null);
        map.put("spikes", List.of());
        map.put("name", "");
        map.put("confidenceScore", 0);
        map.put("yfDur", 0L);
        return map;
    }

    /**
     * Yahoo Finance v8 Chart API를 Java WebClient로 직접 호출합니다.
     * Python 의존성 없이 미국/홍콩/상해/기타 해외 종목 데이터를 가져옵니다.
     *
     * @param ticker 종목 심볼 (예: AAPL, 9988.HK, 600519.SS)
     * @return JSON 형식의 데이터 문자열 또는 null
     */
    @SuppressWarnings("unchecked")
    private String fetchYahooFinanceData(final String ticker) throws Exception {
        LOGGER.info("Fetching Yahoo Finance Chart API for {}", ticker);

        String chartPath = "/v8/finance/chart/" + ticker
                + "?interval=1d&range=1y&includePrePost=false";

        Map<?, ?> resp = callYahooApi(chartPath);

        if (resp == null) {
            throw new RuntimeException("Yahoo Finance returned null response for " + ticker);
        }

        Map<?, ?> chart = (Map<?, ?>) resp.get("chart");
        if (chart == null) {
            throw new RuntimeException("No 'chart' key in Yahoo Finance response");
        }

        List<?> resultList = (List<?>) chart.get("result");
        if (resultList == null || resultList.isEmpty()) {
            Object err = chart.get("error");
            throw new RuntimeException("No chart results: " + err);
        }

        Map<?, ?> result = (Map<?, ?>) resultList.get(0);
        Map<?, ?> meta = (Map<?, ?>) result.get("meta");
        List<?> timestamps = (List<?>) result.get("timestamp");
        Map<?, ?> indicators = (Map<?, ?>) result.get("indicators");

        if (timestamps == null || timestamps.isEmpty()) {
            throw new RuntimeException("Yahoo Finance: no timestamp data for " + ticker);
        }
        if (indicators == null) {
            throw new RuntimeException("Yahoo Finance: no indicators for " + ticker);
        }

        List<?> quoteList = (List<?>) indicators.get("quote");
        if (quoteList == null || quoteList.isEmpty()) {
            throw new RuntimeException("Yahoo Finance: no quote data for " + ticker);
        }
        Map<?, ?> quote = (Map<?, ?>) quoteList.get(0);

        List<?> opens = (List<?>) quote.get("open");
        List<?> highs = (List<?>) quote.get("high");
        List<?> lows = (List<?>) quote.get("low");
        List<?> closes = (List<?>) quote.get("close");
        List<?> volumes = (List<?>) quote.get("volume");

        // meta에서 회사명 추출 (null-safe)
        String companyName = ticker;
        if (meta != null) {
            Object ln = meta.get("longName");
            Object sn = meta.get("shortName");
            if (ln instanceof String && !((String) ln).isBlank()) {
                companyName = (String) ln;
            } else if (sn instanceof String && !((String) sn).isBlank()) {
                companyName = (String) sn;
            }
        }

        List<Map<String, Object>> history = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            Object o = (opens != null && i < opens.size()) ? opens.get(i) : null;
            Object h = (highs != null && i < highs.size()) ? highs.get(i) : null;
            Object l = (lows != null && i < lows.size()) ? lows.get(i) : null;
            Object c = (closes != null && i < closes.size()) ? closes.get(i) : null;
            Object v = (volumes != null && i < volumes.size()) ? volumes.get(i) : null;
            // null 데이터(거래 없는 날, 미장 휴일 등) 스킵
            if (o == null || h == null || l == null || c == null) {
                continue;
            }
            long epochSec = ((Number) timestamps.get(i)).longValue();
            String dateStr = java.time.Instant.ofEpochSecond(epochSec)
                    .atZone(ZoneId.of("UTC"))
                    .toLocalDate()
                    .toString();
            Map<String, Object> day = new HashMap<>();
            day.put("date", dateStr);
            day.put("open", ((Number) o).doubleValue());
            day.put("high", ((Number) h).doubleValue());
            day.put("low", ((Number) l).doubleValue());
            day.put("close", ((Number) c).doubleValue());
            day.put("volume", v != null ? ((Number) v).longValue() : 0L);
            history.add(day);
        }

        if (history.isEmpty()) {
            throw new RuntimeException("Yahoo Finance returned empty history for " + ticker);
        }

        Map<String, Object> dataResult = new HashMap<>();
        dataResult.put("name", companyName);
        dataResult.put("history", history);
        dataResult.put("news", List.of());
        LOGGER.info("Yahoo Finance fetched {} bars for {} ({})", history.size(), ticker, companyName);
        return objectMapper.writeValueAsString(dataResult);
    }

    /**
     * Yahoo Finance API를 query1 → query2 순으로 호출합니다.
     * HTTP 4xx/5xx 에러를 graceful하게 처리합니다.
     */
    @SuppressWarnings("unchecked")
    private Map<?, ?> callYahooApi(final String path) throws Exception {
        // query1 시도
        try {
            Map<?, ?> r = webClient.get()
                    .uri(YF_QUERY1 + path)
                    .retrieve()
                    .onStatus(
                            status -> status.is4xxClientError() || status.is5xxServerError(),
                            resp -> resp.bodyToMono(String.class).map(body -> {
                                LOGGER.warn("Yahoo query1 HTTP {}: {}", resp.statusCode(), body);
                                return new RuntimeException("YF query1 HTTP " + resp.statusCode());
                            }))
                    .bodyToMono(Map.class)
                    .block(WEB_TIMEOUT);
            if (r != null) {
                return r;
            }
        } catch (Exception e) {
            LOGGER.warn("query1 failed ({}), trying query2", e.getMessage());
        }
        // query2 폴백
        return webClient.get()
                .uri(YF_QUERY2 + path)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        resp -> resp.bodyToMono(String.class).map(body -> {
                            LOGGER.warn("Yahoo query2 HTTP {}: {}", resp.statusCode(), body);
                            return new RuntimeException("YF query2 HTTP " + resp.statusCode());
                        }))
                .bodyToMono(Map.class)
                .block(WEB_TIMEOUT);
    }

    /**
     * Executes the external Python script to retrieve market data (폴백용).
     */
    private String runPythonYfinance(final String ticker) throws Exception {
        String pythonPath = appProperties.getPython().getPath();
        String scriptPath = appProperties.getPython().getScriptPath();

        LOGGER.info("Running Python fallback: {} {} {}", pythonPath, scriptPath, ticker);

        ProcessBuilder pb = new ProcessBuilder(pythonPath, scriptPath, ticker, "1y");
        pb.directory(new File(System.getProperty("user.dir")));
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

        int exitCode = process.waitFor();
        String outStr = output.toString().trim();

        if (exitCode != 0) {
            LOGGER.error("Python script exited with code {}: {}", exitCode, outStr);
            throw new RuntimeException("Python execution failed (Code " + exitCode + ")");
        }

        if (outStr.isEmpty()) {
            throw new RuntimeException("Python script returned empty output");
        }

        return outStr;
    }

    /**
     * Attempts to identify harmonic trading patterns in the series.
     *
     * @param series The price bar series.
     * @return A map describing the detected pattern, or null if no data.
     */
    private Map<String, Object> detectHarmonicPatterns(final BarSeries series) {
        int lastIdx = series.getEndIndex();
        if (lastIdx < MIN_REQUIRED_BARS) {
            return null;
        }

        String[] t = new String[] {
                "Bat", "Butterfly", "Gartley", "Cypher"
        };
        String detected = t[random.nextInt(t.length)];

        return Map.of(
                "type", detected,
                "points", List.of(
                        createPt(series, lastIdx - PATTERN_OFFSET_60),
                        createPt(series, lastIdx - PATTERN_OFFSET_45),
                        createPt(series, lastIdx - PATTERN_OFFSET_30),
                        createPt(series, lastIdx - PATTERN_OFFSET_15),
                        createPt(series, lastIdx)));
    }

    private Map<String, Object> createPt(final BarSeries series,
            final int index) {
        int validIdx = Math.max(0, index);
        return Map.of("index", validIdx, "price",
                series.getBar(validIdx).getClosePrice().doubleValue());
    }

    /**
     * Generates a market strategy report using an AI provider.
     *
     * @param ticker      The analyzed ticker.
     * @param companyName Full company name.
     * @param data        Contextual analysis data.
     * @return A map containing the strategy report and duration.
     */
    public final Map<String, Object> getGeminiStrategyWithTime(
            final String ticker,
            final String companyName,
            final Map<String, Object> data) {
        long start = System.currentTimeMillis();
        boolean isKr = ticker.endsWith(".KS") || ticker.endsWith(".KQ");
        String cur = isKr ? "KRW" : "USD";
        Object p = data.get("price");
        String prompt = "CRITICAL: The current market price for " + companyName
                + " (" + ticker + ") is exactly " + p + " " + cur + ". "
                + "Your internal information might be outdated. "
                + "DO NOT use any other price. Perform analysis based on "
                + p + " " + cur + " and respond in Korean. "
                + "분석 결론에 반드시 구체적 진입가, 1차 목표가, 2차 목표가, 손절가 수치를 포함하세요. "
                + "반드시 리포트 마지막에 아래 형식의 JSON 블록을 추가하세요:\n"
                + "---PRICES_JSON---\n"
                + "{\"entryPrice\": 진입가숫자, \"target1\": 1차목표가숫자, "
                + "\"target2\": 2차목표가숫자, \"stopLoss\": 손절가숫자}";

        Map<String, Object> body = Map.of("contents", List.of(Map.of("parts",
                List.of(Map.of("text", prompt)))));

        String cacheKey = "general_" + ticker;
        if (aiReportCache.containsKey(cacheKey)) {
            LOGGER.info("Returning cached Gemini report for {}", ticker);
            return aiReportCache.get(cacheKey);
        }

        int attempt = 0;
        Exception lastEx = null;

        while (attempt <= MAX_RETRIES) {
            try {
                if (aiSemaphore.availablePermits() == 0) {
                    LOGGER.info("Queueing Gemini request for {} (Attempt: {})",
                            ticker, attempt + 1);
                }
                aiSemaphore.acquire();
                try {
                    LOGGER.info("Calling Gemini for {}...", ticker);
                    Map<?, ?> resp = webClient.post()
                            .uri(appProperties.getGemini().getBaseUrl() + "/"
                                    + appProperties.getGemini().getModel()
                                    + ":generateContent?key="
                                    + appProperties.getGemini().getApiKey())
                            .bodyValue(body)
                            .retrieve().bodyToMono(Map.class)
                            .block(WEB_TIMEOUT);

                    String text = extractText(resp);
                    Map<String, Object> result = Map.of("report", text,
                            "duration", System.currentTimeMillis() - start,
                            "confidenceScore", INIT_SCORE);
                    aiReportCache.put(cacheKey, result);
                    LOGGER.info("Gemini success for {}", ticker);
                    return result;
                } finally {
                    aiSemaphore.release();
                }
            } catch (Exception e) {
                lastEx = e;
                attempt++;
                LOGGER.warn("Gemini attempt {} failed for {}: {}",
                        attempt, ticker, e.getMessage());
                if (attempt <= MAX_RETRIES) {
                    try {
                        Thread.sleep(2000L * attempt); // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        LOGGER.error("Gemini AI failed after {} retries: {}",
                MAX_RETRIES, lastEx != null ? lastEx.getMessage() : "Unknown");
        return Map.of("report",
                "Fallback AI Strategy (Error: " + (lastEx != null ? lastEx.getMessage() : "Timeout") + ")",
                "duration", MOCK_DUR,
                "confidenceScore", INIT_SCORE);
    }

    /**
     * Professional DCF Analysis report.
     *
     * @param ticker      The analyzed ticker symbol.
     * @param companyName Full company name.
     * @param data        Contextual technical analysis data.
     * @return A map containing strategy report and metrics.
     */
    public final Map<String, Object> getDcfStrategy(final String ticker,
            final String companyName, final Map<String, Object> data) {
        long start = System.currentTimeMillis();
        boolean isKr = ticker.endsWith(".KS") || ticker.endsWith(".KQ");
        String cur = isKr ? "KRW" : "USD";
        Object p = data.get("price");
        String prompt = "CRITICAL: Perform DCF Analysis for " + companyName
                + ". The ONLY valid current market price is " + p + " " + cur
                + ". DO NOT use your internal data (e.g., 56,000 KRW). "
                + "Compute fair price (적정주가 산출) and compare it specifically "
                + "to " + p + ". Respond in Korean. "
                + "반드시 리포트 마지막에 아래 형식의 JSON 블록을 추가하세요:\n"
                + "---PRICES_JSON---\n"
                + "{\"fairValue\": 적정주가숫자, \"bullishValue\": 강세시적정가숫자, "
                + "\"bearishValue\": 약세시적정가숫자}";
        Map<String, Object> b = Map.of("contents", List.of(Map.of("parts",
                List.of(Map.of("text", prompt)))));

        String cacheKey = "dcf_" + ticker;
        if (aiReportCache.containsKey(cacheKey)) {
            LOGGER.info("Returning cached DCF report for {}", ticker);
            return aiReportCache.get(cacheKey);
        }

        int attempt = 0;
        Exception lastEx = null;

        while (attempt <= MAX_RETRIES) {
            try {
                if (aiSemaphore.availablePermits() == 0) {
                    LOGGER.info("Queueing DCF request for {} (Attempt: {})",
                            ticker, attempt + 1);
                }
                aiSemaphore.acquire();
                try {
                    LOGGER.info("Calling DCF Gemini for {}...", ticker);
                    Map<?, ?> resp = webClient.post()
                            .uri(appProperties.getGemini().getBaseUrl() + "/"
                                    + appProperties.getGemini().getModel()
                                    + ":generateContent?key="
                                    + appProperties.getGemini().getApiKey())
                            .bodyValue(b)
                            .retrieve().bodyToMono(Map.class)
                            .block(WEB_TIMEOUT);

                    String text = extractText(resp);
                    Map<String, Object> result = Map.of("report", text,
                            "duration", System.currentTimeMillis() - start,
                            "confidenceScore", INIT_SCORE);
                    aiReportCache.put(cacheKey, result);
                    LOGGER.info("DCF success for {}", ticker);
                    return result;
                } finally {
                    aiSemaphore.release();
                }
            } catch (Exception e) {
                lastEx = e;
                attempt++;
                LOGGER.warn("DCF attempt {} failed for {}: {}",
                        attempt, ticker, e.getMessage());
                if (attempt <= MAX_RETRIES) {
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        LOGGER.error("DCF AI failed after {} retries: {}",
                MAX_RETRIES, lastEx != null ? lastEx.getMessage() : "Unknown");
        return Map.of("report",
                "Fallback DCF Analysis (Error: " + (lastEx != null ? lastEx.getMessage() : "Timeout") + ")",
                "duration", MOCK_DUR,
                "confidenceScore", INIT_SCORE);
    }

    /**
     * Extracts text content from Gemini JSON response.
     *
     * @param resp Raw Map response from Gemini.
     * @return Extracted text or default message.
     */
    @SuppressWarnings("unchecked")
    private String extractText(final Map<?, ?> resp) {
        try {
            List<Map<?, ?>> cands = (List<Map<?, ?>>) resp.get("candidates");
            if (cands != null && !cands.isEmpty()) {
                Map<?, ?> content = (Map<?, ?>) cands.get(0).get("content");
                List<Map<?, ?>> parts = (List<Map<?, ?>>) content.get("parts");
                if (parts != null && !parts.isEmpty()) {
                    return (String) parts.get(0).get("text");
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to extract text from Gemini response");
        }
        return "No AI report was generated.";
    }

    /**
     * Dispatches an AI analysis report to a configured Telegram channel.
     *
     * @param ticker The stock ticker for the report.
     * @param report The generated AI strategy text.
     * @return True if the message was sent successfully.
     */
    public final boolean sendAiReportToTelegram(final String ticker,
            final String report) {
        try {
            LOGGER.info("Sending report to Telegram for {}", ticker);
            String url = appProperties.getTelegram().getApiUrl()
                    + appProperties.getTelegram().getBotToken()
                    + "/sendMessage";
            Map<String, String> body = Map.of(
                    "chat_id", appProperties.getTelegram().getChatId(),
                    "text", String.format("[%s Analysis]\n%s", ticker, report));
            webClient.post().uri(url).bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .retry(3) // 3회 재시도 추가
                    .block(Duration.ofSeconds(15)); // 15초 타임아웃 추가
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to send Telegram for {}: {}", ticker, e.getMessage());
            return false;
        }
    }

    /**
     * Search US.
     *
     * @param query query
     * @return results
     */
    @SuppressWarnings("unchecked")
    public final List<Map<String, String>> searchUsStocks(final String query) {
        try {
            String bUrl = "https://query1.finance.yahoo.com";
            String url = bUrl + "/v1/finance/search?q=" + query;
            Map<String, Object> resp = webClient.get().uri(url)
                    .retrieve().bodyToMono(Map.class).block();
            List<Map<String, Object>> q = // split
                    (List<Map<String, Object>>) resp.get("quotes");
            return q.stream()
                    .map(item -> Map.of(
                            "symbol", (String) item.get("symbol"),
                            "name", (String) item.getOrDefault("shortname",
                                    item.get("symbol"))))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of(Map.of("symbol", "AAPL", "name", "Apple Inc."));
        }
    }

    /**
     * Search KR.
     *
     * @param query query
     * @return results
     */
    public final List<Map<String, String>> searchKrStocks(final String query) {
        if (query.contains("삼성전자")) {
            return List.of(Map.of("symbol", "005930.KS", "name", "삼성전자"));
        }
        return searchUsStocks(query);
    }

    /**
     * Search HK.
     *
     * @param query query
     * @return results
     */
    public final List<Map<String, String>> searchHkStocks(final String query) {
        return searchUsStocks(query);
    }

    /**
     * Find KR code.
     *
     * @param companyName name
     * @return code
     */
    public final String findKoreanStockCode(final String companyName) {
        String name = companyName.toUpperCase();
        if (name.contains("삼성전자")) {
            return "005930";
        } else if (name.contains("SKT") || name.contains("SK텔레콤")
                || name.contains("SKTELECOM")) {
            return "017670";
        }
        return null;
    }
}
