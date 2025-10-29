package io.spark.k8s.submit.util

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class JsonToStringDeserializerTest extends AnyFlatSpec with Matchers {

  // Constants for validating proper JSON output (not Java toString representation)
  private val InvalidJavaObjectChar = "@"
  private val InvalidJavaObjectString = "Node"

  "JsonToStringDeserializer" should "deserialize JSON object to string" in {
    val mapper = new ObjectMapper()
    mapper.registerModule(DefaultScalaModule)

    val name = "test"
    val json = s"""{"metadata":{"name":"$name"}}"""
    val deserializer = new JsonToStringDeserializer()

    val parser = mapper.getFactory.createParser(json)
    parser.nextToken()

    val context = mapper.getDeserializationContext
    val result = deserializer.deserialize(parser, context)

    result should not be null
    // Verify output is valid JSON by parsing it back
    val parsedBack = mapper.readTree(result)
    parsedBack.get("metadata").get("name").asText() shouldBe name
    // Verify it's proper JSON, not Java object toString representation
    result should not include (InvalidJavaObjectChar)
    result should not include (InvalidJavaObjectString)
  }

  it should "return null for null JSON" in {
    val mapper = new ObjectMapper()
    mapper.registerModule(DefaultScalaModule)

    val json = "null"
    val deserializer = new JsonToStringDeserializer()

    val parser = mapper.getFactory.createParser(json)
    parser.nextToken()

    val context = mapper.getDeserializationContext
    val result = deserializer.deserialize(parser, context)

    result shouldBe null
  }

  it should "handle empty JSON object" in {
    val mapper = new ObjectMapper()
    mapper.registerModule(DefaultScalaModule)

    val emptyJson = "{}"
    val deserializer = new JsonToStringDeserializer()

    val parser = mapper.getFactory.createParser(emptyJson)
    parser.nextToken()

    val context = mapper.getDeserializationContext
    val result = deserializer.deserialize(parser, context)

    result shouldBe emptyJson
  }

  it should "handle complex nested JSON" in {
    val mapper = new ObjectMapper()
    mapper.registerModule(DefaultScalaModule)

    val name = "test"
    val appLabel = "spark"
    val json = s"""{"metadata":{"name":"$name","labels":{"app":"$appLabel"}},"spec":{"containers":[]}}"""
    val deserializer = new JsonToStringDeserializer()

    val parser = mapper.getFactory.createParser(json)
    parser.nextToken()

    val context = mapper.getDeserializationContext
    val result = deserializer.deserialize(parser, context)

    result should not be null
    // Verify output is valid JSON by parsing it back
    val parsedBack = mapper.readTree(result)
    parsedBack.get("metadata").get("name").asText() shouldBe name
    parsedBack.get("metadata").get("labels").get("app").asText() shouldBe appLabel
    parsedBack.get("spec").get("containers").isArray shouldBe true
    // Verify it's proper JSON, not Java object toString representation
    result should not include (InvalidJavaObjectChar)
    result should not include (InvalidJavaObjectString)
  }

  it should "handle JSON array" in {
    val mapper = new ObjectMapper()
    mapper.registerModule(DefaultScalaModule)

    val name1 = "test1"
    val name2 = "test2"
    val json = s"""[{"name":"$name1"},{"name":"$name2"}]"""
    val deserializer = new JsonToStringDeserializer()

    val parser = mapper.getFactory.createParser(json)
    parser.nextToken()

    val context = mapper.getDeserializationContext
    val result = deserializer.deserialize(parser, context)

    result should not be null
    // Verify output is valid JSON by parsing it back
    val parsedBack = mapper.readTree(result)
    parsedBack.isArray shouldBe true
    parsedBack.size() shouldBe 2
    parsedBack.get(0).get("name").asText() shouldBe name1
    parsedBack.get(1).get("name").asText() shouldBe name2
    // Verify it's proper JSON, not Java object toString representation
    result should not include (InvalidJavaObjectChar)
    result should not include (InvalidJavaObjectString)
  }

  it should "handle JSON string value" in {
    val mapper = new ObjectMapper()
    mapper.registerModule(DefaultScalaModule)

    val stringValue = "simple string"
    val json = s""""$stringValue""""
    val deserializer = new JsonToStringDeserializer()

    val parser = mapper.getFactory.createParser(json)
    parser.nextToken()

    val context = mapper.getDeserializationContext
    val result = deserializer.deserialize(parser, context)

    result shouldBe stringValue
  }

  it should "handle JSON number value" in {
    val mapper = new ObjectMapper()
    mapper.registerModule(DefaultScalaModule)

    val numberValue = "123"
    val deserializer = new JsonToStringDeserializer()

    val parser = mapper.getFactory.createParser(numberValue)
    parser.nextToken()

    val context = mapper.getDeserializationContext
    val result = deserializer.deserialize(parser, context)

    result shouldBe numberValue
  }

  it should "handle JSON boolean value" in {
    val mapper = new ObjectMapper()
    mapper.registerModule(DefaultScalaModule)

    val booleanValue = "true"
    val deserializer = new JsonToStringDeserializer()

    val parser = mapper.getFactory.createParser(booleanValue)
    parser.nextToken()

    val context = mapper.getDeserializationContext
    val result = deserializer.deserialize(parser, context)

    result shouldBe booleanValue
  }
}
