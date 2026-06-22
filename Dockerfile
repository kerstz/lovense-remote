# Image du relais Edge2 Remote. Contexte de build = racine du repo (le wrapper
# Gradle, settings, le catalogue de versions et :relay sont tous nécessaires).
# Build du relais seul (RELAY_ONLY=1 → :app exclu → pas de SDK Android requis).
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
ENV RELAY_ONLY=1
RUN ./gradlew :relay:installDist --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/relay/build/install/relay /app
ENV PORT=8080
EXPOSE 8080
CMD ["/app/bin/relay"]
