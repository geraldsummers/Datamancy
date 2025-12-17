# Build stage
FROM gradle:8.10.2-jdk21-alpine AS build
WORKDIR /workspace
COPY . /workspace
RUN gradle :control-panel:shadowJar --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
ENV PANEL_PORT=8097
WORKDIR /app
COPY --from=build /workspace/src/control-panel/build/libs/control-panel.jar /app/control-panel.jar
EXPOSE 8097
ENTRYPOINT ["sh", "-c", "java -jar /app/control-panel.jar"]
