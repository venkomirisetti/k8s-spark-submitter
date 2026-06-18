package io.spark.k8s.submit

import io.spark.k8s.submit.api.ErrorCode
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SparkSubmitExceptionTest extends AnyFlatSpec with Matchers {

  "SparkSubmitException" should "create exception with errorCode and message" in {
    val exception = SparkSubmitException(ErrorCode.InternalError, "something went wrong")

    exception.errorCode shouldBe ErrorCode.InternalError
    exception.getMessage shouldBe "something went wrong"
    exception.cause shouldBe None
    exception.getCause shouldBe null
  }

  it should "create exception with cause" in {
    val cause = new RuntimeException("root")
    val exception = SparkSubmitException(ErrorCode.InternalError, "msg", Some(cause))

    exception.errorCode shouldBe ErrorCode.InternalError
    exception.getMessage shouldBe "msg"
    exception.getCause shouldBe cause
  }

  "SparkSubmitException.of" should "create exception with errorCode and message" in {
    val exception = SparkSubmitException.of(ErrorCode.InvalidSparkSubmitArgs, "bad args")

    exception.errorCode shouldBe ErrorCode.InvalidSparkSubmitArgs
    exception.getMessage shouldBe "bad args"
    exception.getCause shouldBe null
  }

  it should "create exception with cause" in {
    val cause = new IllegalArgumentException("invalid")
    val exception = SparkSubmitException.of(ErrorCode.InvalidSparkSubmitArgs, "bad args", cause)

    exception.errorCode shouldBe ErrorCode.InvalidSparkSubmitArgs
    exception.getMessage shouldBe "bad args"
    exception.getCause shouldBe cause
  }

  it should "handle null cause" in {
    val exception = SparkSubmitException.of(ErrorCode.InternalError, "msg", null)

    exception.getCause shouldBe null
    exception.cause shouldBe None
  }

  it should "be throwable as RuntimeException" in {
    val exception = SparkSubmitException.of(ErrorCode.InternalError, "not allowed")
    exception shouldBe a[RuntimeException]

    val caught = intercept[SparkSubmitException] { throw exception }
    caught.errorCode shouldBe ErrorCode.InternalError
    caught.getMessage shouldBe "not allowed"
  }

  it should "propagate cause through exception chain" in {
    val cause = new IllegalStateException("root")
    val exception = SparkSubmitException.of(ErrorCode.InternalError, "msg", cause)

    exception.getCause shouldBe cause
    exception.getCause.getMessage shouldBe "root"
  }
}
