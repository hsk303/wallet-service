# ---- Build stage ----
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /build
# Cache dependency resolution separately from source changes
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-jammy AS runtime

# curl only, for the HEALTHCHECK below - not needed by the app itself
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r wallet && useradd -r -g wallet -d /app -s /sbin/nologin wallet

WORKDIR /app
COPY --from=build /build/target/wallet-service-0.0.1-SNAPSHOT.jar app.jar
RUN chown -R wallet:wallet /app

USER wallet
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD curl -sf http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
