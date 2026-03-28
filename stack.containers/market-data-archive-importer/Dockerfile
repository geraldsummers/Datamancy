FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl

WORKDIR /app

COPY stack.kotlin/market-data-archive-importer/build/libs/market-data-archive-importer-*-all.jar /app/market-data-archive-importer.jar

RUN addgroup -g 1000 app &&     adduser -u 1000 -G app -s /bin/sh -D app

USER app

EXPOSE 8080

CMD ["java", "-jar", "market-data-archive-importer.jar"]
