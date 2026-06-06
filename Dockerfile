# ── Stage 1: build ────────────────────────────────────────────────────────────
# Full Maven + JDK image. We use it to compile and package, then throw it away.
FROM maven:3.9-eclipse-temurin-19 AS build

WORKDIR /app

# Copy the dependency manifest first. Docker caches each layer separately, so
# as long as pom.xml doesn't change, `mvn dependency:go-offline` is cached and
# subsequent builds skip the slow Maven download step entirely.
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Now copy source and build. -DskipTests because CI already ran them.
COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: run ──────────────────────────────────────────────────────────────
# Minimal JRE image — no compiler, no Maven, no source code.
FROM eclipse-temurin:19-jre

WORKDIR /app

# Copy only the fat JAR produced by Stage 1.
COPY --from=build /app/target/shop-hub-*.jar app.jar

# The port your Spring Boot app listens on.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
