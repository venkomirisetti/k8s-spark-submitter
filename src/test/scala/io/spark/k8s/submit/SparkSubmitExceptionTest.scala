package io.spark.k8s.submit

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SparkSubmitExceptionTest extends AnyFlatSpec with Matchers {

  "SparkSubmitException" should "create exception with message only" in {
    val exception = SparkSubmitException("msg")

    exception.getMessage shouldBe "msg"
    exception.details shouldBe None
    exception.cause shouldBe None
    exception.isValidationError shouldBe false
  }

  it should "create exception with message and details" in {
    val exception = SparkSubmitException("msg", details = Some("info"))

    exception.getMessage shouldBe "msg"
    exception.getDetails shouldBe "info"
    exception.details shouldBe Some("info")
  }

  it should "create exception with message and cause" in {
    val cause = new RuntimeException("root")
    val exception = SparkSubmitException("msg", cause = Some(cause))

    exception.getMessage shouldBe "msg"
    exception.getCause shouldBe cause
  }

  "SparkSubmitException.validation" should "create validation error with details string" in {
    val exception = SparkSubmitException.validation("msg", "detail")

    exception.getMessage shouldBe "msg"
    exception.getDetails shouldBe "detail"
    exception.isValidationError shouldBe true
    exception.getCause shouldBe null
  }

  it should "create validation error with null details" in {
    val exception = SparkSubmitException.validation("msg", null.asInstanceOf[String])

    exception.getMessage shouldBe "msg"
    exception.details shouldBe None
    exception.isValidationError shouldBe true
  }

  it should "create validation error with cause throwable" in {
    val cause = new IllegalArgumentException("bad")
    val exception = SparkSubmitException.validation("msg", cause)

    exception.getMessage shouldBe "msg"
    exception.getCause shouldBe cause
    exception.getDetails shouldBe "bad"
    exception.isValidationError shouldBe true
  }

  "SparkSubmitException.submission" should "create submission error with details string" in {
    val exception = SparkSubmitException.submission("msg", "detail")

    exception.getMessage shouldBe "msg"
    exception.getDetails shouldBe "detail"
    exception.isValidationError shouldBe false
    exception.getCause shouldBe null
  }

  it should "create submission error with null details" in {
    val exception = SparkSubmitException.submission("msg", null.asInstanceOf[String])

    exception.getMessage shouldBe "msg"
    exception.details shouldBe None
    exception.isValidationError shouldBe false
  }

  it should "create submission error with cause throwable" in {
    val cause = new RuntimeException("err")
    val exception = SparkSubmitException.submission("msg", cause)

    exception.getMessage shouldBe "msg"
    exception.getCause shouldBe cause
    exception.getDetails shouldBe "err"
    exception.isValidationError shouldBe false
  }

  "SparkSubmitException.retryable" should "create transient error with details string" in {
    val exception = SparkSubmitException.retryable("msg", "detail")

    exception.getMessage shouldBe "msg"
    exception.getDetails shouldBe "detail"
    exception.isTransient shouldBe true
    exception.isValidationError shouldBe false
    exception.getCause shouldBe null
  }

  it should "create transient error with cause throwable" in {
    val cause = new RuntimeException("err")
    val exception = SparkSubmitException.retryable("msg", cause)

    exception.getMessage shouldBe "msg"
    exception.getCause shouldBe cause
    exception.getDetails shouldBe "err"
    exception.isTransient shouldBe true
  }

  "SparkSubmitException.isTransient" should "return true only for transient errors" in {
    SparkSubmitException.retryable("a", "b").isTransient shouldBe true
    SparkSubmitException.submission("a", "b").isTransient shouldBe false
    SparkSubmitException.validation("a", "b").isTransient shouldBe false
  }

  "SparkSubmitException.isValidationError" should "return true for validation errors" in {
    SparkSubmitException.validation("a", "b").isValidationError shouldBe true
  }

  it should "return false for submission errors" in {
    SparkSubmitException.submission("a", "b").isValidationError shouldBe false
  }

  it should "be throwable as RuntimeException" in {
    val exception = SparkSubmitException.validation("a", "b")
    exception shouldBe a[RuntimeException]

    val caught = intercept[SparkSubmitException] { throw exception }
    caught.getMessage shouldBe "a"
  }

  it should "propagate cause through exception chain" in {
    val cause = new IllegalStateException("root")
    val exception = SparkSubmitException.validation("msg", cause)

    exception.getCause shouldBe cause
    exception.getCause.getMessage shouldBe "root"
  }

  it should "handle empty details" in {
    val exception = SparkSubmitException("msg", details = Some(""))

    exception.getDetails shouldBe ""
    exception.details shouldBe Some("")
  }
}
