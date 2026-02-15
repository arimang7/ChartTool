# 1. 빌드 스테이지
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# 2. 실행 스테이지
FROM eclipse-temurin:21-jre
WORKDIR /app
# 빌드 스테이지에서 생성된 jar 파일을 복사 (파일명 주의)
COPY --from=build /app/target/chart-tool-0.0.1-SNAPSHOT.jar app.jar

# 포트 설정 (Render는 기본적으로 8080 사용)
EXPOSE 8080

# 실행 명령
ENTRYPOINT ["java", "-jar", "app.jar"]