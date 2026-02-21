# 1. 빌드 스테이지
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# 2. 실행 스테이지
FROM eclipse-temurin:21-jre
WORKDIR /app

# --- Python 및 필수 라이브러리 설치 ---
RUN apt-get update && apt-get install -y python3 python3-pip python3-venv && \
    python3 -m venv /opt/venv && \
    /opt/venv/bin/pip install --no-cache-dir yfinance pandas && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# --- 환경 변수 설정 (Spring Boot가 인식) ---
ENV PATH="/opt/venv/bin:$PATH"
# Java의 @Value("${app.python.path}") 오버라이드
ENV APP_PYTHON_PATH=/opt/venv/bin/python3
# Java의 @Value("${app.python.script-path}") 오버라이드
ENV APP_PYTHON_SCRIPT_PATH=/app/yfinance_adapter.py

# 빌드된 JAR 복사
COPY --from=build /app/target/chart-tool-0.0.1-SNAPSHOT.jar app.jar
# Python 스크립트 복사
COPY src/main/resources/yfinance_adapter.py /app/yfinance_adapter.py

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]