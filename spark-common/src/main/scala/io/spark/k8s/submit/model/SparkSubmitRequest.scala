package io.spark.k8s.submit.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.spark.k8s.submit.util.JsonToStringDeserializer

import java.util.UUID
import scala.beans.BeanProperty

/** Spark job submission request. */
case class SparkSubmitRequest(
    @JsonProperty("submission_id")
    @BeanProperty submissionId: String = s"G-${UUID.randomUUID()}",

    @JsonProperty("spark_submit_args")
    @BeanProperty sparkSubmitArgs: java.util.List[String],

    @JsonProperty("driver_pod_template")
    @JsonDeserialize(using = classOf[JsonToStringDeserializer])
    @BeanProperty driverPodTemplate: String = null,

    @JsonProperty("executor_pod_template")
    @JsonDeserialize(using = classOf[JsonToStringDeserializer])
    @BeanProperty executorPodTemplate: String = null
) {
  def driverTemplate: Option[String] = Option(driverPodTemplate).filter(_.nonEmpty)
  def executorTemplate: Option[String] = Option(executorPodTemplate).filter(_.nonEmpty)
}
