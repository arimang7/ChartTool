import sys
import yfinance as yf
import json
import pandas as pd
import traceback
import requests

# Rate limit mitigation for Render: use a browser-like User-Agent
SESSION = requests.Session()
SESSION.headers.update({
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
                  'AppleWebKit/537.36 (KHTML, like Gecko) '
                  'Chrome/121.0.0.0 Safari/537.36'
})

def fetch_stock_data(ticker, period="1y"):
    try:
        # 0. Handle Korean tickers (6-digit numbers)
        original_ticker = ticker
        if ticker.isdigit() and len(ticker) == 6:
            # Try KOSPI (.KS) first, then KOSDAQ (.KQ)
            ticker = original_ticker + ".KS"
            stock = yf.Ticker(ticker, session=SESSION)
            df = stock.history(period=period, interval="1d")
            
            if df.empty:
                ticker = original_ticker + ".KQ"
                stock = yf.Ticker(ticker, session=SESSION)
                df = stock.history(period=period, interval="1d")
        else:
            stock = yf.Ticker(ticker, session=SESSION)
            df = stock.history(period=period, interval="1d")
        
        if df.empty:
            return {"error": f"No data found for ticker {original_ticker}"}
            
        # 2. Transform price data
        data_list = []
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
            for item in news[:5]: # Take top 5 news
                content = item.get("content", {})
                
                # Try new structure first, then fallback to old one
                title = content.get("title") or item.get("title")
                publisher = content.get("provider", {}).get("displayName") or item.get("publisher")
                
                # Link can be in clickThroughUrl or canonicalUrl
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
            pass # News fetching is optional
            
        return {
            "name": stock.info.get("longName") or stock.info.get("shortName") or original_ticker,
            "history": data_list,
            "news": news_list
        }
    except Exception as e:
        return {"error": str(e), "trace": traceback.format_exc()}

if __name__ == "__main__":
    # Get arguments
    ticker = sys.argv[1] if len(sys.argv) > 1 else "AAPL"
    period = sys.argv[2] if len(sys.argv) > 2 else "1y"
    
    # Fetch and print as JSON
    result = fetch_stock_data(ticker, period)
    print(json.dumps(result))
