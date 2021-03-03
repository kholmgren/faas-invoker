FROM amazoncorretto:11
COPY build/libs/faas-invoker-1.0.jar /
CMD java $JAVA_OPTS -jar /faas-invoker-1.0.jar
