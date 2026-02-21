import sys
import yfinance as yf
import json
import pandas as pd
import traceback
import requests
import xml.etree.ElementTree as ET

# Rate limit mitigation for Render: use a browser-like User-Agent
SESSION = requests.Session()
SESSION.headers.update({
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
                  'AppleWebKit/537.36 (KHTML, like Gecko) '
                  'Chrome/124.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8',
    'Accept-Language': 'en-US,en;q=0.9,ko;q=0.8',
    'Cache-Control': 'no-cache',
    'Pragma': 'no-cache'
})

def establish_session():
    """Establish a session by visiting Yahoo Finance homepage."""
    try:
        # Pre-visit to get cookies/crumbs
        SESSION.get('https://finance.yahoo.com', timeout=5)
        SESSION.get('https://fc.yahoo.com', timeout=5)
    except:
        pass

def fetch_naver_data(ticker_digits, count=250):
    """Fetch OHLCV data from Naver Finance XML endpoint."""
    try:
        url = f"https://fchart.stock.naver.com/sise.naver?symbol={ticker_digits}&timeframe=day&count={count}&requestType=0"
        response = SESSION.get(url, timeout=10)
        if response.status_code != 200:
            return None
        
        root = ET.fromstring(response.text)
        items = root.findall('.//item')
        
        data_list = []
        for item in items:
            # data="Date|Open|High|Low|Close|Volume"
            parts = item.get('data').split('|')
            if len(parts) >= 6:
                d_str = parts[0]
                data_list.append({
                    "date": f"{d_str[:4]}-{d_str[4:6]}-{d_str[6:]}",
                    "open": float(parts[1]),
                    "high": float(parts[2]),
                    "low": float(parts[3]),
                    "close": float(parts[4]),
                    "volume": int(parts[5])
                })
        return data_list
    except:
        return None

def fetch_stock_data(ticker, period="1y"):
    establish_session()
    try:
        data_list = []
        company_name = ticker
        original_ticker = ticker
        
        # 0. Handle Korean tickers (6-digit numbers)
        is_kr = ticker.isdigit() and len(ticker) == 6
        if is_kr:
            # Try Naver Finance first for KR stocks as it's more stable
            data_list = fetch_naver_data(ticker)
            if data_list:
                # If Naver works, we still want the company name from yfinance if possible
                try:
                    stock = yf.Ticker(ticker + ".KS")
                    company_name = stock.info.get("longName") or stock.info.get("shortName") or ticker
                except:
                    pass
                return {
                    "name": company_name,
                    "history": data_list,
                    "news": [] # News is optional
                }

        # 1. Fallback/Primary to yfinance
        if ticker.isdigit() and len(ticker) == 6:
            ticker = ticker + ".KS"
            
        stock = yf.Ticker(ticker)
        df = stock.history(period=period, interval="1d")
        
        if df.empty and is_kr:
            # If KOSPI failed, try KOSDAQ
            ticker = original_ticker + ".KQ"
            stock = yf.Ticker(ticker)
            df = stock.history(period=period, interval="1d")

        if df.empty:
            return {"error": f"No data found for ticker {original_ticker}"}
            
        # 2. Transform price data
        for index, row in df.iterrows():
            date_str = index.strftime('%Y-%m-%d')
            data_list.append({
                "date": date_str,
                "open": float(row['Open']),
                "high": float(row['High']),
                "low": float(row['Low']),
                "close": float(row['Close']),
                "volume": int(row['Volume'])
            })

        # 3. Fetch recent news/reports
        news_list = []
        try:
            news = stock.news
            for item in news[:5]:
                content = item.get("content", {})
                title = content.get("title") or item.get("title")
                publisher = content.get("provider", {}).get("displayName") or item.get("publisher")
                link = None
                if content.get("clickThroughUrl"):
                    link = content["clickThroughUrl"].get("url")
                if not link and content.get("canonicalUrl"):
                    link = content["canonicalUrl"].get("url")
                if not link:
                    link = item.get("link")
                
                news_list.append({
                    "title": title,
                    "publisher": publisher,
                    "link": link,
                    "type": content.get("contentType") or item.get("type")
                })
        except:
            pass
            
        return {
            "name": stock.info.get("longName") or stock.info.get("shortName") or original_ticker,
            "history": data_list,
            "news": news_list
        }
    except Exception as e:
        return {"error": str(e), "trace": traceback.format_exc()}

if __name__ == "__main__":
    ticker = sys.argv[1] if len(sys.argv) > 1 else "AAPL"
    period = sys.argv[2] if len(sys.argv) > 2 else "1y"
    result = fetch_stock_data(ticker, period)
    print(json.dumps(result))
