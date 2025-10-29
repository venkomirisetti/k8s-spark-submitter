package io.spark.k8s.submit.api

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import io.spark.k8s.submit.LogPrefix
import io.spark.k8s.submit.model.ErrorResponse
import org.slf4j.Logger

import java.time.Instant
import jakarta.servlet.http.{HttpServlet, HttpServletResponse}
import scala.util.Try

/**
 * Trait providing JSON serialization support for servlets.
 */
trait JsonSupport { self: HttpServlet =>

  protected val objectMapper: ObjectMapper = new ObjectMapper()
    .registerModule(DefaultScalaModule)
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

  /**
   * Send a JSON response with the given status and body.
   */
  protected def sendJson(resp: HttpServletResponse, status: Int, body: Any): Unit = {
    resp.setContentType(MediaType.ApplicationJson)
    resp.setCharacterEncoding("UTF-8")
    resp.setStatus(status)
    objectMapper.writeValue(resp.getOutputStream, body)
  }

  /**
   * Parse JSON request body into a case class.
   */
  protected def parseJson[T](json: String, clazz: Class[T]): Try[T] = {
    Try(objectMapper.readValue(json, clazz))
  }

  /**
   * Parse JSON from input stream into a case class.
   */
  protected def parseJsonStream[T](stream: java.io.InputStream, clazz: Class[T]): Try[T] = {
    Try(objectMapper.readValue(stream, clazz))
  }
}

/**
 * Trait providing error response support for servlets.
 */
trait ErrorSupport { self: JsonSupport =>

  protected def log: Logger

  /**
   * Send an error response with the given status, error code, message, and optional details.
   */
  protected def sendError(
      resp: HttpServletResponse,
      status: Int,
      errorCode: String,
      message: String,
      details: String = null): Unit = {
    val errorResponse = ErrorResponse(
      timestamp = Instant.now.toString,
      status = status,
      error = errorCode,
      message = message,
      details = Option(details).orNull
    )
    sendJson(resp, status, errorResponse)
  }

  /**
   * Log a submission failure.
   */
  protected def logSubmissionFailure(errorType: String, message: String, details: String): Unit = {
    log.error(s"${LogPrefix.Error} [$errorType] $message $details")
  }
}
