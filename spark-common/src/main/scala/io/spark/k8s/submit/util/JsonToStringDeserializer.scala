package io.spark.k8s.submit.util

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.{DeserializationContext, JsonDeserializer, JsonNode, ObjectMapper}

/** Deserializes JSON objects to String for opaque handling. */
class JsonToStringDeserializer extends JsonDeserializer[String] {
  private val mapper = new ObjectMapper()

  override def deserialize(p: JsonParser, ctxt: DeserializationContext): String =
    Option(p.readValueAsTree[JsonNode]())
      .filterNot(n => n.isNull || n.isMissingNode)
      .map(n => if (n.isTextual) n.asText() else mapper.writeValueAsString(n))
      .orNull
}
