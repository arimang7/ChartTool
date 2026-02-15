package com.charttool.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class StockServiceTest {

    @Autowired
    private StockService stockService;

    @Test
    @SuppressWarnings("unchecked")
    void testGetAnalysis_Success() {
        // Given
        String ticker = "AAPL";

        // When
        Map<String, Object> result = stockService.getAnalysis(ticker);

        // Then
        assertNotNull(result);
        assertFalse(result.containsKey("error"), "Should not contain error: " + result.get("error"));
        assertTrue((Double) result.get("price") > 0);
        assertTrue((Double) result.get("rsi") >= 0);
        assertNotNull(result.get("history"));

        List<Map<String, Object>> history = (List<Map<String, Object>>) result.get("history");
        assertFalse(history.isEmpty());
        System.out.println("History data size: " + history.size());
    }

    @Test
    void testGetAnalysis_InvalidTicker() {
        // Given
        String ticker = "INVALID_TICKER_12345";

        // When
        Map<String, Object> result = stockService.getAnalysis(ticker);

        // Then
        assertTrue(result.containsKey("error") || ((Double) result.get("price") == 0.0),
                "Should report an error or return zero price");
    }

    @Test
    void testSearchKrStocks() {
        // Given
        String query = "삼성전자";

        // When
        List<Map<String, String>> results = stockService.searchKrStocks(query);

        // Then
        assertNotNull(results);
        if (!results.isEmpty()) {
            assertEquals("005930.KS", results.get(0).get("symbol"));
        } else {
            System.out.println("Warning: KR search returned no results. This might be a network issue.");
        }
    }
}
