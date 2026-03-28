FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl

WORKDIR /app

COPY stack.kotlin/alpha-execution-agent/build/libs/alpha-execution-agent-*-all.jar /app/alpha-execution-agent.jar

RUN addgroup -g 1000 app &&     adduser -u 1000 -G app -s /bin/sh -D app

USER app

EXPOSE 8080

CMD ["java", "-jar", "alpha-execution-agent.jar"]
