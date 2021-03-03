FROM amazoncorretto:11
COPY build/libs/faas-invoker-1.0.jar /
CMD java -jar /faas-invoker-1.0.jar
