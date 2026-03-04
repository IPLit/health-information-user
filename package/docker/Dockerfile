FROM adoptopenjdk/openjdk11:jre-11.0.19_7-alpine

RUN mkdir -p /tmp
RUN chmod -R 777 /tmp
VOLUME /tmp

COPY build/libs/*-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"] 
