package com.charttool.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class StockControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "test@gmail.com")
    void testIndexPage_AuthorizedUser() throws Exception {
        mockMvc.perform(get("/").param("ticker", "AAPL"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(model().attributeExists("ticker"))
                .andExpect(model().attributeExists("data"))
                .andExpect(model().attribute("ticker", "AAPL"))
                .andExpect(content().string(containsString("(AAPL) 실시간 차트")));
    }

    @Test
    void testIndexPage_GuestUser() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(model().attribute("userEmail", "Guest User"))
                .andExpect(content().string(containsString("Guest")));
    }

    @Test
    void testApiAiAnalysis() throws Exception {
        mockMvc.perform(get("/api/ai-analysis").param("ticker", "TSLA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report").exists())
                .andExpect(jsonPath("$.confidenceScore").exists());
    }

    @Test
    void testApiSearch_US() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "Apple").param("market", "US"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$[0].symbol").exists());
    }

    @Test
    void testApiSearch_KR() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "삼성전자").param("market", "KR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$[0].symbol").value("005930.KS"));
    }
}
