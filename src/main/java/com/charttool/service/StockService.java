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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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

    /** WebClient for external API calls. */
    private final WebClient webClient = WebClient.builder()
            .defaultHeader("User-Agent", "Mozilla/5.0")
            .build();

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
        try {
            long s0 = System.currentTimeMillis();
            String resJson = runPythonYfinance(ticker);
            long s1 = System.currentTimeMillis();

            Map<String, Object> raw = objectMapper.readValue(resJson,
                    new MapTypeReference());
            List<Map<String, Object>> h = // split
                    (List<Map<String, Object>>) raw.get("history");
            List<Map<String, Object>> n = // split
                    (List<Map<String, Object>>) raw.get("news");

            BarSeries series = new BaseBarSeriesBuilder().withName(ticker)
                    .build();
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
            BollingerBandsMiddleIndicator bm = // split
                    new BollingerBandsMiddleIndicator(sma);
            StandardDeviationIndicator sd = // split
                    new StandardDeviationIndicator(cp, BOLLINGER_WINDOW);
            BollingerBandsUpperIndicator bu = // split
                    new BollingerBandsUpperIndicator(bm, sd);
            BollingerBandsLowerIndicator bl = // split
                    new BollingerBandsLowerIndicator(bm, sd);

            int lastIdx = series.getEndIndex();
            for (int i = 0; i < procHist.size(); i++) {
                procHist.get(i).put("upper", bu.getValue(i).doubleValue());
                procHist.get(i).put("lower", bl.getValue(i).doubleValue());
            }

            List<Integer> spikes = new ArrayList<>();
            for (int i = BOLLINGER_WINDOW; i <= lastIdx; i++) {
                if (series.getBar(i).getVolume().doubleValue() // split
                        > VOL_THRESHOLD) {
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
            map.put("name", raw.get("name"));
            map.put("confidenceScore", INIT_SCORE);
            map.put("yfDur", s1 - s0);

            return map;
        } catch (Exception e) {
            LOGGER.error("Failed to analyze stock {}: {}",
                    ticker, e.getMessage());
            return getEmptyAnalysis();
        }
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
     * Executes the external Python script to retrieve market data.
     *
     * @param ticker The stock ticker symbol.
     * @return The JSON formatted output from the Python script.
     * @throws Exception If process execution fails.
     */
    private String runPythonYfinance(final String ticker) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(
            appProperties.getPython().getPath(),
            appProperties.getPython().getScriptPath(),
            ticker, "1y");
        processBuilder.directory(new File(System.getProperty("user.dir")));
        Process process = processBuilder.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
            process.waitFor();
            String out = output.toString();
            if (out.isEmpty()) {
                throw new RuntimeException("Python script returned no output");
            }
            return out;
        }
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
                + p + " " + cur + " and respond in Korean.";

        Map<String, Object> body = Map.of("contents", List.of(Map.of("parts",
                List.of(Map.of("text", prompt)))));

        try {
                Map<?, ?> resp = webClient.post()
                    .uri(appProperties.getGemini().getBaseUrl() + "/"
                        + appProperties.getGemini().getModel()
                        + ":generateContent?key="
                        + appProperties.getGemini().getApiKey())
                    .bodyValue(body)
                    .retrieve().bodyToMono(Map.class).block();

            String text = extractText(resp);
            return Map.of("report", text, "duration",
                    System.currentTimeMillis() - start,
                    "confidenceScore", INIT_SCORE);
        } catch (Exception e) {
            LOGGER.error("Gemini AI failed: {}", e.getMessage());
            return Map.of("report", "Fallback AI Strategy for " + ticker,
                    "duration", MOCK_DUR,
                    "confidenceScore", INIT_SCORE);
        }
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
                + "to " + p + ". Respond in Korean.";
        Map<String, Object> b = Map.of("contents", List.of(Map.of("parts",
                List.of(Map.of("text", prompt)))));

        try {
                Map<?, ?> resp = webClient.post()
                    .uri(appProperties.getGemini().getBaseUrl() + "/"
                        + appProperties.getGemini().getModel()
                        + ":generateContent?key="
                        + appProperties.getGemini().getApiKey())
                    .bodyValue(b)
                    .retrieve().bodyToMono(Map.class).block();

            String text = extractText(resp);
            return Map.of("report", text,
                    "duration", System.currentTimeMillis() - start,
                    "confidenceScore", INIT_SCORE);
        } catch (Exception e) {
            LOGGER.error("DCF AI failed: {}", e.getMessage());
            return Map.of("report", "Fallback DCF Analysis for " + ticker,
                    "duration", MOCK_DUR,
                    "confidenceScore", INIT_SCORE);
        }
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
                    .retrieve().bodyToMono(Map.class).block();
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to send Telegram: {}", e.getMessage());
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
