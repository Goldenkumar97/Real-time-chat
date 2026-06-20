FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY src/Server.java src/Server.java
RUN javac src/Server.java -d out

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/out .
COPY frontend/ frontend/
CMD ["java", "Server"]
