# 多阶段构建：Maven 构建 + JRE 运行
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/royalty-settlement-1.0.0.jar app.jar
ENV DB_HOST=postgres DB_PORT=5432 DB_NAME=ccdev DB_USER=ccdev DB_PASSWORD=ccdev DB_SCHEMA=royalty \
    PAYMENT_DIR=/app/data/payment-files RECEIPT_DIR=/app/data/receipts
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
