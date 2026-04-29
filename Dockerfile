# syntax=docker/dockerfile:1.6
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Use BuildKit cache for Maven so rebuilds do not re-download everything.
ARG MAVEN_REPO=/root/.m2/repository

COPY pom.xml .
COPY .mvn .mvn
COPY mvnw mvnw
COPY mvnw.cmd mvnw.cmd
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests -Dmaven.repo.local=${MAVEN_REPO} dependency:go-offline

COPY src src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -Dmaven.repo.local=${MAVEN_REPO} clean package -Dmaven.test.skip=true

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       tesseract-ocr \
       tesseract-ocr-eng \
       tesseract-ocr-fra \
       curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar /app/app.jar

RUN mkdir -p /app/uploads /app/uploads_banking /app/logs

ENV JAVA_OPTS="-Xms512m -Xmx1024m" \
    BACKEND_PORT=8089 \
    UPLOAD_DIR=/app/uploads \
    UPLOAD_BANK_DIR=/app/uploads_banking \
    TESSERACT_PATH=/usr/bin/tesseract \
    TESSERACT_DATAPATH=/usr/share/tesseract-ocr/5/tessdata \
    TESSERACT_LANGUAGE=eng+fra

EXPOSE 8089

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl --fail --silent http://localhost:8089/actuator/health > /dev/null || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
