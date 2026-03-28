FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl

WORKDIR /app

COPY stack.kotlin/alpha-dataset-service/build/libs/alpha-dataset-service-*-all.jar /app/alpha-dataset-service.jar

RUN addgroup -g 1000 app &&     adduser -u 1000 -G app -s /bin/sh -D app

USER app

EXPOSE 8080

CMD ["java", "-jar", "alpha-dataset-service.jar"]
