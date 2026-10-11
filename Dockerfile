FROM node:22-alpine AS frontend
WORKDIR /build
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build
FROM maven:3.9.11-eclipse-temurin-21 AS backend
WORKDIR /build
COPY backend/pom.xml ./
RUN mvn -B dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /build/dist ./src/main/resources/static
RUN mvn -B verify
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S kmr && adduser -S kmr -G kmr
COPY --from=backend /build/target/kmr-market-1.0.0.jar app.jar
USER kmr
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
