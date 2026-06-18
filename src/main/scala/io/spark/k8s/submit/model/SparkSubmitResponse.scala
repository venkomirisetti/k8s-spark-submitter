package io.spark.k8s.submit.model

import com.fasterxml.jackson.annotation.{JsonInclude, JsonProperty}
import io.spark.k8s.submit.api.Messages

import java.time.Instant
import scala.beans.BeanProperty

/** Spark job submission response. */
@JsonInclude(JsonInclude.Include.NON_NULL)
case class SparkSubmitResponse(
    @JsonProperty("submission_id") @BeanProperty submissionId: String,
    @JsonProperty("app_name") @BeanProperty appName: String,
    @JsonProperty("message") @BeanProperty message: String,
    @JsonProperty("submitted_at") @BeanProperty submittedAt: String,
    @JsonProperty("spark_app_id") @BeanProperty sparkAppId: String,
    @JsonProperty("driver_pod_name") @BeanProperty driverPodName: String,
    @JsonProperty("driver_pod_uid") @BeanProperty driverPodUid: String,
    @JsonProperty("namespace") @BeanProperty namespace: String,
    @JsonProperty("idempotent_replay") @BeanProperty idempotentReplay: Boolean = false
)

object SparkSubmitResponse {
  def success(submissionId: String, appName: String, sparkAppId: String, driverPodName: String, driverPodUid: String, namespace: String): SparkSubmitResponse =
    SparkSubmitResponse(submissionId, appName, Messages.SubmitSuccess, Instant.now.toString, sparkAppId, driverPodName, driverPodUid, namespace)

  def idempotentReplay(submissionId: String, appName: String, sparkAppId: String, driverPodName: String, driverPodUid: String, namespace: String): SparkSubmitResponse =
    SparkSubmitResponse(submissionId, appName, Messages.IdempotentReplay, Instant.now.toString, sparkAppId, driverPodName, driverPodUid, namespace, idempotentReplay = true)
}
