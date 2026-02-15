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

import java.util.Map;

@Controller
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    @GetMapping("/")
    public String index(Model model, @AuthenticationPrincipal OAuth2User principal,
            @RequestParam(required = false) String ticker,
            @RequestParam(required = false, defaultValue = "US") String market) throws Exception {

        String userEmail = (principal != null) ? principal.getAttribute("email") : "Guest User";
        model.addAttribute("userEmail", userEmail);
        model.addAttribute("market", market);

        if (ticker != null && !ticker.trim().isEmpty()) {
            String searchTicker = ticker.trim();

            // KR 시장이고 입력이 종목명이면 코드로 변환 시도
            if ("KR".equals(market) && !searchTicker.matches("\\d{6}")) {
                String code = stockService.findKoreanStockCode(searchTicker);
                if (code != null) {
                    searchTicker = code;
                }
            }

            model.addAttribute("ticker", searchTicker);

            // 통화 기호 설정
            String currencySymbol = "$";
            if ("KR".equals(market)) {
                currencySymbol = "₩";
            } else if ("HK".equals(market)) {
                currencySymbol = "HK$";
            } else if ("US".equals(market)) {
                currencySymbol = "$";
            } else {
                // 마켓이 명시되지 않은 경우 티커 형식으로 추측
                if (searchTicker.matches("\\d{6}") || searchTicker.endsWith(".KS") || searchTicker.endsWith(".KQ")) {
                    currencySymbol = "₩";
                } else if (searchTicker.endsWith(".HK")) {
                    currencySymbol = "HK$";
                }
            }
            model.addAttribute("currencySymbol", currencySymbol);

            var analysis = stockService.getAnalysis(searchTicker);
            if (analysis.containsKey("error")) {
                model.addAttribute("error", analysis.get("error"));
            }
            model.addAttribute("data", analysis);
        } else {
            model.addAttribute("ticker", "");
            model.addAttribute("currencySymbol", "$");
            model.addAttribute("data", Map.of());
        }

        return "index";
    }

    @GetMapping("/api/ai-analysis")
    @ResponseBody
    public Map<String, Object> getAiAnalysis(@RequestParam String ticker) throws Exception {
        // AI 분석 버튼 클릭 시 최신 데이터를 다시 가져와서 분석
        var analysis = stockService.getAnalysis(ticker);
        String report = stockService.getGeminiStrategy(ticker, analysis);
        return Map.of(
                "report", report,
                "confidenceScore", analysis.getOrDefault("confidenceScore", 0),
                "patternDetails", analysis.getOrDefault("patternDetails", Map.of()),
                "targets", analysis.getOrDefault("targets", Map.of()));
    }

    @PostMapping("/api/send-telegram")
    @ResponseBody
    public Map<String, Object> sendToTelegram(@RequestBody Map<String, String> payload) {
        String ticker = payload.get("ticker");
        String report = payload.get("report");
        boolean success = stockService.sendAiReportToTelegram(ticker, report);
        return Map.of("success", success);
    }

    @GetMapping("/api/search")
    @ResponseBody
    public java.util.List<Map<String, String>> searchStocks(@RequestParam String q,
            @RequestParam(defaultValue = "US") String market) {
        if ("US".equals(market)) {
            return stockService.searchUsStocks(q);
        } else if ("KR".equals(market)) {
            return stockService.searchKrStocks(q);
        } else {
            return stockService.searchHkStocks(q);
        }
    }
}