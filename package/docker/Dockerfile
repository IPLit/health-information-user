FROM adoptopenjdk/openjdk11:jre-11.0.19_7-alpine

RUN mkdir -p /tmpHiu
RUN chmod -R 777 /tmpHiu
VOLUME /tmpHiu

COPY build/libs/*-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"] 
