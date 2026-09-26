FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline

COPY . .
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 10001 luvax \
    && mkdir -p /var/log/luvax \
    && chown -R luvax:luvax /var/log/luvax /app

COPY --from=build /workspace/target/*.jar /app/app.jar

EXPOSE 8080

# The base application.yaml activates the dev profile, which serves Swagger anonymously,
# marks the refresh cookie non-Secure, points mail at localhost, and trusts only loopback as
# a proxy. An image started without an explicit profile must therefore fail safe rather than
# silently run development settings against real users. Override with SPRING_PROFILES_ACTIVE
# when a different profile is genuinely wanted.
ENV SPRING_PROFILES_ACTIVE=prod

# curl is installed above for exactly this. start-period covers Flyway migration and context
# startup, which took roughly 20s on a warm local machine.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8081/actuator/health || exit 1

USER luvax

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
