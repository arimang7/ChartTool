package com.charttool.controller;

import com.charttool.service.StockService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;

/**
 * Controller for handling stock market requests and AI visualization.
 */
@Controller
public class StockController {

    /** Core business logic service for stock analysis. */
    private final StockService stockService;

    /** Default confidence score for DCF analysis. */
    private static final int DEFAULT_DCF_SCORE = 90;

    /** Mock duration for patterns. */
    private static final long PATTERN_DUR = 100L;

    /**
     * Initializes the controller with the required StockService.
     *
     * @param stockServiceInput The stock service instance.
     */
    public StockController(final StockService stockServiceInput) {
        this.stockService = stockServiceInput;
    }

    /**
     * Renders the main index page with initial stock dashboard data.
     *
     * @param model      UI Model to pass data to the view.
     * @param oauth2User Currently authenticated user via OAuth2.
     * @param ticker     Target stock ticker symbol (optional).
     * @param marker     Target market (e.g., US, KR) (optional).
     * @return The "index" template name.
     */
    @GetMapping("/")
    public final String index(final Model model,
            @AuthenticationPrincipal final OAuth2User oauth2User,
            @RequestParam(required = false) // split
            final String ticker,
            @RequestParam(required = false, defaultValue = "US") // split
            final String marker) {

        String mail = "Guest User";
        if (oauth2User != null) {
            String attrMail = oauth2User.getAttribute("email");
            if (attrMail != null) {
                mail = attrMail;
            }
        }
        model.addAttribute("userEmail", mail);
        model.addAttribute("market", marker);

        if (ticker != null && !ticker.trim().isEmpty()) {
            String processedTicker = ticker.trim();

            if ("KR".equals(marker) && !processedTicker.matches("\\d{6}")) {
                String resolvedCode = stockService.findKoreanStockCode(
                        processedTicker);
                if (resolvedCode != null) {
                    processedTicker = resolvedCode;
                }
            }

            model.addAttribute("ticker", processedTicker);
            // .KS나 .KQ로 끝나면 한국 원화로 표시 (시장 선택과 상관없이)
            String sys = (processedTicker.endsWith(".KS") || processedTicker.endsWith(".KQ")) ? "₩"
                    : ("KR".equals(marker) ? "₩" : "$");
            model.addAttribute("currencySymbol", sys);
            model.addAttribute("data",
                    stockService.getAnalysis(processedTicker));
        } else {
            model.addAttribute("ticker", "");
            model.addAttribute("currencySymbol", "$");
            model.addAttribute("data", stockService.getEmptyAnalysis());
        }

        return "index";
    }

    /**
     * REST endpoint to retrieve deep AI-based strategy analysis.
     *
     * @param ticker The target stock symbol.
     * @return A map containing strategy and confidence metrics.
     */
    @GetMapping("/api/ai-analysis")
    @ResponseBody
    public final Map<String, Object> getAiAnalysis(
            @RequestParam final String ticker) {
        Map<String, Object> analysisData = stockService.getAnalysis(ticker);
        String name = (String) analysisData.getOrDefault("name", ticker);
        Map<String, Object> strategy = stockService.getGeminiStrategyWithTime(
                ticker, name, analysisData);
        return wrapAnalysisResult(analysisData, strategy);
    }

    /**
     * REST endpoint to retrieve Professional DCF analysis.
     *
     * @param ticker The target stock symbol.
     * @return A map containing strategy and metrics.
     */
    @GetMapping("/api/dcf-analysis")
    @ResponseBody
    public final Map<String, Object> getDcfAnalysis(
            @RequestParam final String ticker) {
        Map<String, Object> analysisData = stockService.getAnalysis(ticker);
        String name = (String) analysisData.getOrDefault("name", ticker);
        Map<String, Object> strategy = stockService.getDcfStrategy(
                ticker, name, analysisData);
        return wrapAnalysisResult(analysisData, strategy);
    }

    /**
     * Wraps analysis strategy with timing data for frontend consumption.
     *
     * @param analysisData Raw technical data.
     * @param strategy     AI generated strategy.
     * @return Formatted result map.
     */
    private Map<String, Object> wrapAnalysisResult(
            final Map<String, Object> analysisData,
            final Map<String, Object> strategy) {
        Map<String, Object> result = new java.util.HashMap<>(strategy);

        if (!result.containsKey("confidenceScore")) {
            result.put("confidenceScore", DEFAULT_DCF_SCORE);
        }

        Map<String, Object> durations = Map.of(
                "yfinance", analysisData.getOrDefault("yfDur", 0L),
                "pattern", PATTERN_DUR,
                "gemini", strategy.getOrDefault("duration", 0L));
        result.put("durations", durations);

        // Copy technical details if present for chart integration
        if (analysisData.containsKey("patternDetails")) {
            result.put("patternDetails", analysisData.get("patternDetails"));
        }
        if (analysisData.containsKey("pattern")) {
            result.put("pattern", analysisData.get("pattern"));
        }

        return result;
    }

    /**
     * REST endpoint to dispatch a generated AI report to Telegram.
     *
     * @param payload Request body containing ticker and report text.
     * @return A status map indicating success or failure.
     */
    @PostMapping("/api/send-telegram")
    @ResponseBody
    public final Map<String, Object> sendToTelegram(
            @RequestBody final Map<String, String> payload) {
        String ticker = payload.get("ticker");
        String report = payload.get("report");
        boolean ok = stockService.sendAiReportToTelegram(
                ticker, report);
        return Map.of("success", ok);
    }

    /**
     * REST endpoint to search for stocks across markets.
     *
     * @param q      Search term (name or ticker).
     * @param market Target market designation.
     * @return A list of matching stock results.
     */
    @GetMapping("/api/search")
    @ResponseBody
    public final List<Map<String, String>> searchStocks(
            @RequestParam(name = "q") final String q,
            @RequestParam(defaultValue = "US") final String market) {
        if ("US".equals(market)) {
            return stockService.searchUsStocks(q);
        } else if ("KR".equals(market)) {
            return stockService.searchKrStocks(q);
        } else {
            return stockService.searchHkStocks(q);
        }
    }
}
