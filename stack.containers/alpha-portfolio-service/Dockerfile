FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl

WORKDIR /app

COPY stack.kotlin/alpha-portfolio-service/build/libs/alpha-portfolio-service-*-all.jar /app/alpha-portfolio-service.jar

RUN addgroup -g 1000 app &&     adduser -u 1000 -G app -s /bin/sh -D app

USER app

EXPOSE 8080

CMD ["java", "-jar", "alpha-portfolio-service.jar"]
