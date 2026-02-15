# ChartTool (Java/Spring Boot)

A Spring Boot-based stock analysis and notification system. This project provides technical analysis and AI-driven investment strategy reports using Google Gemini and Telegram integration.

## 📁 Directory Structure

```text
java/
├── pom.xml                             # Maven configuration
├── doc/                                # Project documentation (Korean)
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/charttool/
│   │   │       ├── ChartToolApplication.java   # Application entry point
│   │   │       ├── config/
│   │   │       │   └── SecurityConfig.java     # Security & OAuth2 setup
│   │   │       ├── controller/
│   │   │       │   └── StockController.java    # Web/API controllers
│   │   │       └── service/
│   │   │           └── StockService.java       # Business logic (Analysis, AI, API)
│   │   └── resources/
│   │       ├── application.yml                 # Main configuration
│   │       ├── application-local.yml           # Local secrets (Git ignored)
│   │       └── templates/
│   │           └── index.html                  # Main UI (Thymeleaf)
│   └── test/                                   # Unit and Integration tests
```

## 🚀 Key Components

### 1. Backend (Java/Spring Boot)

- **StockService**:
  - Collects stock data by calling a Python adapter (`yfinance`).
  - Analyzes technical indicators (RSI, Bollinger Bands, Volume spikes) using `ta4j`.
  - Detects Harmonic Patterns (Mock implementation current).
  - Generates investment strategy reports via **Google Gemini AI**.
  - Sends reports to users via **Telegram Bot API**.
- **StockController**:
  - Manages web routing and provides REST APIs for AI analysis and Telegram notifications.
- **SecurityConfig**:
  - Supports Google OAuth2 login and guest access.

### 2. Data Collection (Python)

- **yfinance_adapter.py**:
  - Fetches historical price data and news from Yahoo Finance using the `yfinance` library.

### 3. Frontend (Thymeleaf/HTML)

- **Interactive UI**:
  - Visualizes stock charts and technical indicators.
  - Buttons for triggering AI synthesis and Telegram delivery.

## 🛠 Prerequisites

- **Java**: JDK 21 or higher.
- **Python**: 3.10+ (located in `.venv` at the project root).
  - Required packages: `yfinance`, `pandas`.
- **API Keys**:
  - **Google Cloud**: OAuth2 Client credentials for Google login.
  - **Google AI Studio**: Gemini API Key.
  - **Telegram**: `bot-token` and `chat-id` from BotFather.

## ⚙️ Configuration

Set the environment variables or update `src/main/resources/application.yml`:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID}
            client-secret: ${GOOGLE_CLIENT_SECRET}

app:
  gemini:
    api-key: ${GEMINI_API_KEY}
  telegram:
    bot-token: ${TELEGRAM_BOT_TOKEN}
    chat-id: ${TELEGRAM_CHAT_ID}
```

## 🏃 How to Run

### Via Command Line

```bash
# Build the project
mvn clean install

# Run the application
mvn spring-boot:run
```

### Via IDE

- Run the `main` method in `com.charttool.ChartToolApplication`.

## ✨ Features

1. **Dashboard**: Accessible at `http://localhost:8080`.
2. **Stock Search**: Search by ticker symbols (e.g., `AAPL`, `NVDA`, `005930.KS`).
3. **Technical Charts**: Real-time visualization of price, RSI, and Bollinger Bands.
4. **AI Analysis**: Get detailed reports and trade signals from Gemini AI.
5. **Smart Alerts**: Send the analysis report directly to your Telegram.

## 📡 API Specification

- `GET /?ticker={ticker}`: Loads the main page with basic analysis.
- `GET /api/ai-analysis?ticker={ticker}`: Generates a Gemini AI analysis report (JSON).
- `POST /api/send-telegram`: Sends the AI report to Telegram.
  - Body: `{"ticker": "...", "report": "..."}`
