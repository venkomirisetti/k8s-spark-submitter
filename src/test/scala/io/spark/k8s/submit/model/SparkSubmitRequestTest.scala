package io.spark.k8s.submit.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.{Arrays => JavaArrays}

/**
 * Unit tests for SparkSubmitRequest model.
 * Tests focus on custom business logic (template filtering) rather than case class auto-generated methods.
 */
class SparkSubmitRequestTest extends AnyFlatSpec with Matchers {

  "SparkSubmitRequest" should "expose BeanProperty accessors for Jackson serialization" in {
    val args = JavaArrays.asList("--master", "k8s://localhost", "--class", "Main", "app.jar")
    val driverTemplate = "{\"metadata\":{\"name\":\"driver\"}}"
    val executorTemplate = "{\"metadata\":{\"name\":\"executor\"}}"

    val request = SparkSubmitRequest(args, driverTemplate, executorTemplate)

    // Verify BeanProperty getters (required for Jackson)
    request.getSparkSubmitArgs shouldBe args
    request.getDriverPodTemplate shouldBe driverTemplate
    request.getExecutorPodTemplate shouldBe executorTemplate
  }

  it should "filter templates via driverTemplate/executorTemplate methods" in {
    val args = JavaArrays.asList("--master", "k8s://localhost")
    val template = "{\"metadata\":{\"name\":\"driver\"}}"
    val emptyString = ""
    val whitespace = "  "

    // Test filtering: Some(value), None for null, None for empty
    SparkSubmitRequest(args, template, template).driverTemplate shouldBe Some(template)
    SparkSubmitRequest(args, null, null).driverTemplate shouldBe None
    SparkSubmitRequest(args, emptyString, emptyString).executorTemplate shouldBe None

    // Edge case: whitespace is NOT filtered (nonEmpty returns true)
    SparkSubmitRequest(args, whitespace, null).driverTemplate shouldBe Some(whitespace)
  }
}
