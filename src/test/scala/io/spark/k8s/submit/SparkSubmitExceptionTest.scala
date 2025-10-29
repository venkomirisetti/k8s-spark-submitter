package io.spark.k8s.submit

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** SparkSubmitException
 * Unit tests for SparkSubmitException.
 */
class SparkSubmitExceptionTest extends AnyFlatSpec with Matchers {

  "SparkSubmitException" should "create exception with message only" in {
    val message = "Test error"
    val exception = SparkSubmitException(message)

    exception.getMessage shouldBe message
    exception.details shouldBe None
    exception.cause shouldBe None
    exception.isValidationError shouldBe false
  }

  it should "create exception with message and details" in {
    val message = "Test error"
    val details = "Additional info"
    val exception = SparkSubmitException(message, details = Some(details))

    exception.getMessage shouldBe message
    exception.getDetails shouldBe details
    exception.details shouldBe Some(details)
  }

  it should "create exception with message and cause" in {
    val message = "Test error"
    val causeMessage = "Root cause"
    val cause = new RuntimeException(causeMessage)
    val exception = SparkSubmitException(message, cause = Some(cause))

    exception.getMessage shouldBe message
    exception.getCause shouldBe cause
  }

  "SparkSubmitException.validation" should "create validation error with message and details string" in {
    val message = "Invalid config"
    val details = "Missing field 'name'"
    val exception = SparkSubmitException.validation(message, details)

    exception.getMessage shouldBe message
    exception.getDetails shouldBe details
    exception.details shouldBe Some(details)
    exception.isValidationError shouldBe true
    exception.getCause shouldBe null
  }

  it should "create validation error with message and null details" in {
    val message = "Invalid config"
    val exception = SparkSubmitException.validation(message, null.asInstanceOf[String])

    exception.getMessage shouldBe message
    exception.getDetails shouldBe null
    exception.details shouldBe None
    exception.isValidationError shouldBe true
  }

  it should "create validation error with message and cause throwable" in {
    val message = "Validation failed"
    val causeMessage = "Invalid argument"
    val cause = new IllegalArgumentException(causeMessage)
    val exception = SparkSubmitException.validation(message, cause)

    exception.getMessage shouldBe message
    exception.getCause shouldBe cause
    exception.getDetails shouldBe causeMessage
    exception.details shouldBe Some(causeMessage)
    exception.isValidationError shouldBe true
  }

  "SparkSubmitException.submission" should "create submission error with message and details string" in {
    val message = "Submit failed"
    val details = "Connection timeout"
    val exception = SparkSubmitException.submission(message, details)

    exception.getMessage shouldBe message
    exception.getDetails shouldBe details
    exception.details shouldBe Some(details)
    exception.isValidationError shouldBe false
    exception.getCause shouldBe null
  }

  it should "create submission error with message and null details" in {
    val message = "Submit failed"
    val exception = SparkSubmitException.submission(message, null.asInstanceOf[String])

    exception.getMessage shouldBe message
    exception.getDetails shouldBe null
    exception.details shouldBe None
    exception.isValidationError shouldBe false
  }

  it should "create submission error with message and cause throwable" in {
    val message = "Submit failed"
    val causeMessage = "Network error"
    val cause = new RuntimeException(causeMessage)
    val exception = SparkSubmitException.submission(message, cause)

    exception.getMessage shouldBe message
    exception.getCause shouldBe cause
    exception.getDetails shouldBe causeMessage
    exception.details shouldBe Some(causeMessage)
    exception.isValidationError shouldBe false
  }

  "SparkSubmitException.retryable" should "create transient error with message and details string" in {
    val message = "Upstream unavailable"
    val details = "apiserver 503"
    val exception = SparkSubmitException.retryable(message, details)

    exception.getMessage shouldBe message
    exception.getDetails shouldBe details
    exception.details shouldBe Some(details)
    exception.isTransient shouldBe true
    exception.isValidationError shouldBe false
    exception.getCause shouldBe null
  }

  it should "create transient error with message and cause throwable" in {
    val message = "Upstream unavailable"
    val causeMessage = "Connection refused"
    val cause = new RuntimeException(causeMessage)
    val exception = SparkSubmitException.retryable(message, cause)

    exception.getMessage shouldBe message
    exception.getCause shouldBe cause
    exception.getDetails shouldBe causeMessage
    exception.details shouldBe Some(causeMessage)
    exception.isTransient shouldBe true
  }

  "SparkSubmitException.isTransient" should "return true only for transient errors" in {
    SparkSubmitException.retryable("t", "d").isTransient shouldBe true
    SparkSubmitException.submission("s", "d").isTransient shouldBe false
    SparkSubmitException.validation("v", "d").isTransient shouldBe false
  }

  "SparkSubmitException.isValidationError" should "return true for validation errors" in {
    val message = "Error"
    val details = "Details"
    val validationError = SparkSubmitException.validation(message, details)

    validationError.isValidationError shouldBe true
  }

  it should "return false for submission errors" in {
    val message = "Error"
    val details = "Details"
    val submissionError = SparkSubmitException.submission(message, details)

    submissionError.isValidationError shouldBe false
  }

  "SparkSubmitException" should "be throwable as RuntimeException" in {
    val message = "Test"
    val details = "Details"
    val exception = SparkSubmitException.validation(message, details)

    exception shouldBe a[RuntimeException]

    // Verify it can be thrown and caught
    val caught = intercept[SparkSubmitException] {
      throw exception
    }
    caught.getMessage shouldBe message
  }

  it should "propagate cause through exception chain" in {
    val message = "Wrapper"
    val causeMessage = "Root problem"
    val rootCause = new IllegalStateException(causeMessage)
    val exception = SparkSubmitException.validation(message, rootCause)

    exception.getMessage shouldBe message
    exception.getCause shouldBe rootCause
    exception.getCause.getMessage shouldBe causeMessage
  }

  it should "handle exception with empty details" in {
    val message = "Error"
    val emptyDetails = ""
    val exception = SparkSubmitException(message, details = Some(emptyDetails))

    exception.getMessage shouldBe message
    exception.getDetails shouldBe emptyDetails
    exception.details shouldBe Some(emptyDetails)
  }

}
