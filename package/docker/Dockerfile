FROM adoptopenjdk/openjdk11:jre-11.0.19_7-alpine

# RUN apk add acl

RUN mkdir -p /tmpHiuData
RUN chmod -R 777 /tmpHiuData
# RUN setfacl -d -R -m g::rwx /tmpHiuData
# RUN setfacl -d -R -m o::rwx /tmpHiuData

VOLUME /tmpHiuData

COPY build/libs/*-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"] 
