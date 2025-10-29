ARG SPARK_IMAGE=docker.io/library/spark:4.0.1
FROM ${SPARK_IMAGE}

ARG spark_uid=7448
ARG workdir=/opt/k8s-spark-submitter
ARG app_jar=${workdir}/app.jar

ENV SPARK_HOME=/opt/spark \
    APP_JAR=${app_jar}

USER root

WORKDIR ${workdir}
COPY target/k8s-spark-submitter-*.jar ${app_jar}
RUN echo '7448:x:7448:0:anonymous uid:/opt/spark:/bin/false' >> /etc/passwd

USER ${spark_uid}

ENTRYPOINT ["/usr/bin/tini", "-s", "--", "sh", "-c", \
  "exec java ${JAVA_OPTS:-} -Dloader.path=${SPARK_HOME}/jars -jar ${APP_JAR} \"$@\""]
