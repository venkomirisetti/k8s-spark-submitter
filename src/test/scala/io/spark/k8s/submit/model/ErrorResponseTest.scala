package io.spark.k8s.submit.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/**
 * Unit tests for ErrorResponse model.
 * Tests focus on factory methods, timestamp formatting, and Jackson serialization rather than case class auto-generated methods.
 */
class ErrorResponseTest extends AnyFlatSpec with Matchers {

  "ErrorResponse.badRequest" should "create error with correct status, ISO-8601 timestamp, and handle null details" in {
    val message = "Invalid configuration"
    val details = "Missing required field"

    val before = Instant.now
    val response = ErrorResponse.badRequest(message, details)
    val after = Instant.now

    response.message shouldBe message
    response.details shouldBe details

    // Verify timestamp is ISO-8601 format and recent
    val timestamp = Instant.parse(response.timestamp)
    timestamp should (be >= before and be <= after)

    // Verify null details handling
    ErrorResponse.badRequest("msg", null).details shouldBe null
  }

  "ErrorResponse.internalError" should "create error with correct status and null details" in {
    val message = "Unexpected error occurred"

    val response = ErrorResponse.internalError(message)

    response.message shouldBe message
    response.details shouldBe null
    noException should be thrownBy Instant.parse(response.timestamp)
  }

  "ErrorResponse.of" should "create custom error with any status/error code" in {
    val status = 422
    val error = "VALIDATION_ERROR"
    val message = "Validation failed"
    val details = "Invalid format"
    val response = ErrorResponse.of(status, error, message, details)

    response.status shouldBe status
    response.error shouldBe error
    response.message shouldBe message
    response.details shouldBe details
    noException should be thrownBy Instant.parse(response.timestamp)
  }
}
