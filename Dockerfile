ARG SPARK_IMAGE
FROM ${SPARK_IMAGE}

ARG SPARK_MODULE

ENV APP_JAR=/opt/k8s-spark-submitter/app.jar \
    MAIN_CLASS=io.spark.k8s.submit.SparkSubmitServer

COPY ${SPARK_MODULE}/target/spark-submitter-*-jar-with-dependencies.jar ${APP_JAR}

ENTRYPOINT ["/usr/bin/tini", "-s", "--", "sh", "-c", \
  "exec java ${JAVA_OPTS:-} -cp ${APP_JAR}:${SPARK_HOME}/jars/* ${MAIN_CLASS} \"$@\""]
