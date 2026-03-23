FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl

WORKDIR /app

COPY stack.kotlin/alpha-analytics-service/build/libs/alpha-analytics-service-*-all.jar /app/alpha-analytics-service.jar

RUN addgroup -g 1000 alphaanalytics && \
    adduser -u 1000 -G alphaanalytics -s /bin/sh -D alphaanalytics

USER alphaanalytics

EXPOSE 8080

CMD ["java", "-jar", "alpha-analytics-service.jar"]
