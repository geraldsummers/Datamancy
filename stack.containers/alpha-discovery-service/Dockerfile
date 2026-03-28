FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl

WORKDIR /app

COPY stack.kotlin/alpha-discovery-service/build/libs/alpha-discovery-service-*-all.jar /app/alpha-discovery-service.jar

RUN addgroup -g 1000 app &&     adduser -u 1000 -G app -s /bin/sh -D app

USER app

EXPOSE 8080

CMD ["java", "-jar", "alpha-discovery-service.jar"]
