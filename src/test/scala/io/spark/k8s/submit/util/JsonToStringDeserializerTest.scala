package io.spark.k8s.submit.util

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class JsonToStringDeserializerTest extends AnyFlatSpec with Matchers {

  private val mapper = {
    val m = new ObjectMapper()
    m.registerModule(DefaultScalaModule)
    m
  }

  private def deserialize(json: String): String = {
    val deserializer = new JsonToStringDeserializer()
    val parser = mapper.getFactory.createParser(json)
    parser.nextToken()
    deserializer.deserialize(parser, mapper.getDeserializationContext)
  }

  "JsonToStringDeserializer" should "deserialize JSON object to string" in {
    val json = """{"a":"b"}"""
    val result = deserialize(json)

    result should not be null
    mapper.readTree(result).get("a").asText() shouldBe "b"
  }

  it should "return null for null JSON" in {
    deserialize("null") shouldBe null
  }

  it should "handle empty JSON object" in {
    deserialize("{}") shouldBe "{}"
  }

  it should "handle nested JSON" in {
    val json = """{"x":{"y":"z"},"items":[]}"""
    val result = deserialize(json)

    val parsed = mapper.readTree(result)
    parsed.get("x").get("y").asText() shouldBe "z"
    parsed.get("items").isArray shouldBe true
  }

  it should "handle JSON array" in {
    val json = """[{"id":1},{"id":2}]"""
    val result = deserialize(json)

    val parsed = mapper.readTree(result)
    parsed.isArray shouldBe true
    parsed.size() shouldBe 2
  }

  it should "handle JSON string value" in {
    deserialize(""""hello"""") shouldBe "hello"
  }

  it should "handle JSON number value" in {
    deserialize("42") shouldBe "42"
  }

  it should "handle JSON boolean value" in {
    deserialize("true") shouldBe "true"
  }
}
