package io.spark.k8s.submit.model

import io.spark.k8s.submit.api.ErrorCode
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class ErrorResponseTest extends AnyFlatSpec with Matchers {

  "ErrorResponse.of" should "create error with ISO-8601 timestamp" in {
    val before = Instant.now
    val response = ErrorResponse.of("sub-123", 400, ErrorCode.BadRequest, "bad input")
    val after = Instant.now

    response.submissionId shouldBe "sub-123"
    response.status shouldBe 400
    response.errorCode shouldBe ErrorCode.BadRequest
    response.message shouldBe "bad input"

    val timestamp = Instant.parse(response.timestamp)
    timestamp should (be >= before and be <= after)
  }

  it should "handle null submissionId" in {
    val response = ErrorResponse.of(null, 422, ErrorCode.DriverPodAlreadyExists, "already exists")
    response.submissionId shouldBe null
  }

  it should "create error with various error codes" in {
    val response = ErrorResponse.of("sub-456", 503, ErrorCode.SubmitterOverloaded, "retry later")

    response.status shouldBe 503
    response.errorCode shouldBe ErrorCode.SubmitterOverloaded
    response.message shouldBe "retry later"
    noException should be thrownBy Instant.parse(response.timestamp)
  }
}
