package io.spark.k8s.submit.model

import com.fasterxml.jackson.annotation.{JsonInclude, JsonProperty}

import java.time.Instant
import scala.beans.BeanProperty

/** API error response. */
@JsonInclude(JsonInclude.Include.NON_NULL)
case class ErrorResponse(
    @JsonProperty("submission_id") @BeanProperty submissionId: String,
    @JsonProperty("status") @BeanProperty status: Int,
    @JsonProperty("error_code") @BeanProperty errorCode: String,
    @JsonProperty("message") @BeanProperty message: String,
    @JsonProperty("timestamp") @BeanProperty timestamp: String
)

object ErrorResponse {
  private def now: String = Instant.now.toString

  def of(submissionId: String, status: Int, errorCode: String, message: String): ErrorResponse =
    ErrorResponse(submissionId, status, errorCode, message, now)
}
