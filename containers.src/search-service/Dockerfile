FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache wget

RUN addgroup -S datamancer && adduser -S datamancer -G datamancer

WORKDIR /app

COPY kotlin.src/search-service/build/libs/search-service-1.0-SNAPSHOT-all.jar /app/app.jar

RUN chown -R datamancer:datamancer /app

USER datamancer

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 -O /dev/null http://localhost:8098/health || exit 1

EXPOSE 8098
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
