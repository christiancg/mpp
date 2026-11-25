ARG BUILDPLATFORM
FROM --platform=$BUILDPLATFORM maven:3.10.0-eclipse-temurin-27-alpine AS build

ARG REVISION=1.0-SNAPSHOT

COPY src /app/src
COPY pom.xml /app

WORKDIR /app
RUN mvn clean package -DskipTests -Drevision=${REVISION}

FROM eclipse-temurin:27-jdk-alpine AS jre

RUN jlink \
        --add-modules java.base,java.management,java.net.http,jdk.crypto.ec,jdk.unsupported \
        --strip-debug \
        --no-man-pages \
        --no-header-files \
        --compress=zip-9 \
        --output /jre

FROM alpine:3.21

ARG PORT=8989
ENV PORT=${PORT}
ARG HTTP_PORT=8990
ENV HTTP_PORT=${HTTP_PORT}

RUN apk add --no-cache libstdc++

ENV JAVA_HOME=/opt/jre
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=jre /jre /opt/jre
COPY --from=build /app/target/mpp.jar /app/app.jar

WORKDIR /app

COPY mpp.cfg /app
COPY --chmod=755 docker-entrypoint.sh /app/docker-entrypoint.sh
COPY --chmod=755 healthcheck.sh /app/healthcheck.sh

RUN addgroup -S mpp && adduser -S -G mpp mpp \
    && mkdir -p /app/db /app/files /app/logs /app/certs \
    && chown -R mpp:mpp /app

USER mpp

VOLUME /app/db
VOLUME /app/files
VOLUME /app/logs

EXPOSE ${PORT}
EXPOSE ${HTTP_PORT}

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 CMD ["/app/healthcheck.sh"]

ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=70", "-XX:G1PeriodicGCInterval=60000", "-Djdk.nio.maxCachedBufferSize=262144", "-jar", "app.jar"]
