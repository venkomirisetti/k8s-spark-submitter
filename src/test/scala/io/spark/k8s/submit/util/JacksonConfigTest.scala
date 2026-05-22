package io.spark.k8s.submit.util

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Test case class for Jackson serialization tests. */
case class TestCase(name: String, value: Int)

/** Unit tests for Jackson ObjectMapper with Scala module. */
class JacksonConfigTest extends AnyFlatSpec with Matchers {

  private def createMapper(): ObjectMapper = {
    val mapper = new ObjectMapper()
    mapper.registerModule(DefaultScalaModule)
    mapper
  }

  "ObjectMapper with DefaultScalaModule" should "create ObjectMapper with Scala module" in {
    val mapper = createMapper()

    mapper should not be null
    mapper shouldBe a[ObjectMapper]
  }

  it should "serialize Scala case classes" in {
    val mapper = createMapper()

    val name = "test"
    val value = 42
    val testObj = TestCase(name, value)

    val json = mapper.writeValueAsString(testObj)
    json should include(name)
    json should include(value.toString)
  }

  it should "deserialize to Scala case classes" in {
    val mapper = createMapper()

    val name = "test"
    val value = 42
    val json = s"""{"name":"$name","value":$value}"""

    val result = mapper.readValue(json, classOf[TestCase])
    result.name shouldBe name
    result.value shouldBe value
  }
}
