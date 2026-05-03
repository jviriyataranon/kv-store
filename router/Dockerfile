FROM eclipse-temurin:25-jdk AS builder
WORKDIR /build

COPY .mvn .mvn/
COPY mvnw router/pom.xml ./
COPY router/src src/

RUN ./mvnw package -DskipTests -q

FROM eclipse-temurin:25-jdk
WORKDIR /app

EXPOSE 6999

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=70.0 -XX:+UseG1GC"

COPY --from=builder /build/target/*.jar app.jar

ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar app.jar"]
