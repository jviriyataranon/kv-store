FROM eclipse-temurin:25-jdk AS builder
WORKDIR /build

COPY .mvn .mvn/
COPY mvnw node/pom.xml ./
COPY node/src src/

RUN ./mvnw package -DskipTests -q

FROM eclipse-temurin:25-jdk
WORKDIR /app

EXPOSE 7001
EXPOSE 9010

ENV JMX_HOST=localhost
ENV JMX_PORT=9010
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=70.0 -XX:+UseG1GC"

COPY --from=builder /build/target/*.jar app.jar

ENTRYPOINT ["sh", "-c", "exec java \
  -Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port=${JMX_PORT} \
  -Dcom.sun.management.jmxremote.rmi.port=${JMX_PORT} \
  -Dcom.sun.management.jmxremote.ssl=false \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Djava.rmi.server.hostname=${JMX_HOST} \
  ${JAVA_OPTS} \
  -jar app.jar"]
