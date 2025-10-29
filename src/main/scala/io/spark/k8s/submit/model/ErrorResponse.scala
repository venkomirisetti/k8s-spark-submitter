package io.spark.k8s.submit.model

import com.fasterxml.jackson.annotation.{JsonInclude, JsonProperty}
import io.spark.k8s.submit.api.{ErrorCode, HttpStatus}

import java.time.Instant
import scala.beans.BeanProperty

/** API error response. */
@JsonInclude(JsonInclude.Include.NON_NULL)
case class ErrorResponse(
    @JsonProperty("timestamp") @BeanProperty timestamp: String,
    @JsonProperty("status") @BeanProperty status: Int,
    @JsonProperty("error") @BeanProperty error: String,
    @JsonProperty("message") @BeanProperty message: String,
    @JsonProperty("details") @BeanProperty details: String
)

object ErrorResponse {
  private def now: String = Instant.now.toString

  def badRequest(message: String, details: String): ErrorResponse =
    ErrorResponse(now, HttpStatus.BadRequest, ErrorCode.BadRequest, message, details)

  def internalError(message: String): ErrorResponse =
    ErrorResponse(now, HttpStatus.InternalServerError, ErrorCode.InternalError, message, null)

  def of(status: Int, error: String, message: String, details: String): ErrorResponse =
    ErrorResponse(now, status, error, message, details)
}
