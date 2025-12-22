# Build stage
FROM gradle:8.10.2-jdk21-alpine AS build
WORKDIR /workspace
COPY . /workspace
RUN gradle :control-panel:shadowJar --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
ENV PANEL_PORT=8097

# Install wget for healthcheck
RUN apk add --no-cache wget

WORKDIR /app
COPY --from=build /workspace/src/control-panel/build/libs/control-panel.jar /app/control-panel.jar

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 -O /dev/null http://localhost:8097/health || exit 1

EXPOSE 8097
ENTRYPOINT ["sh", "-c", "java -jar /app/control-panel.jar"]
