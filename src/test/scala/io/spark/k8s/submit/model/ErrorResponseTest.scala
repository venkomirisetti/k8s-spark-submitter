package io.spark.k8s.submit.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class ErrorResponseTest extends AnyFlatSpec with Matchers {

  "ErrorResponse.badRequest" should "create error with ISO-8601 timestamp" in {
    val before = Instant.now
    val response = ErrorResponse.badRequest("msg", "detail")
    val after = Instant.now

    response.message shouldBe "msg"
    response.details shouldBe "detail"

    val timestamp = Instant.parse(response.timestamp)
    timestamp should (be >= before and be <= after)
  }

  it should "handle null details" in {
    val response = ErrorResponse.badRequest("msg", null)
    response.details shouldBe null
  }

  "ErrorResponse.internalError" should "create error with null details" in {
    val response = ErrorResponse.internalError("msg")

    response.message shouldBe "msg"
    response.details shouldBe null
    noException should be thrownBy Instant.parse(response.timestamp)
  }

  "ErrorResponse.of" should "create error with custom status and error code" in {
    val response = ErrorResponse.of(422, "ERR", "msg", "detail")

    response.status shouldBe 422
    response.error shouldBe "ERR"
    response.message shouldBe "msg"
    response.details shouldBe "detail"
    noException should be thrownBy Instant.parse(response.timestamp)
  }
}
