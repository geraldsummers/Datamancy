FROM eclipse-temurin:21-jre-alpine

# Install curl for healthcheck
RUN apk add --no-cache curl

WORKDIR /app

# Copy the fat JAR
COPY kotlin.src/tx-gateway/build/libs/tx-gateway-*-all.jar /app/tx-gateway.jar

# Create non-root user
RUN addgroup -g 1000 txgateway && \
    adduser -u 1000 -G txgateway -s /bin/sh -D txgateway

USER txgateway

EXPOSE 8080

CMD ["java", "-jar", "tx-gateway.jar"]
