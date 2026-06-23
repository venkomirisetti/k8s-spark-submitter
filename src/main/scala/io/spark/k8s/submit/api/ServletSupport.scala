package io.spark.k8s.submit.api

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import io.spark.k8s.submit.{LogPrefix, SparkSubmitException}
import io.spark.k8s.submit.model.ErrorResponse
import jakarta.servlet.http.{HttpServlet, HttpServletResponse}
import org.slf4j.Logger

import scala.util.Try

/**
 * Trait providing JSON serialization support for servlets.
 */
trait JsonSupport {
  self: HttpServlet =>

  private val objectMapper: ObjectMapper = new ObjectMapper()
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
  protected def parseJson[T](json: String, clazz: Class[T]): Try[T] =
    Try(objectMapper.readValue(json, clazz))

  /**
   * Parse JSON from input stream into a case class.
   */
  protected def parseJsonStream[T](stream: java.io.InputStream, clazz: Class[T]): Try[T] =
    Try(objectMapper.readValue(stream, clazz))
}

/**
 * Trait providing error response support for servlets.
 */
trait ErrorSupport {
  self: JsonSupport =>

  protected def log: Logger

  /**
   * Send an error response from a SparkSubmitException. HTTP status is derived from the error code.
   */
  protected def sendErrorResponse(resp: HttpServletResponse, ex: SparkSubmitException, submissionId: String): Unit =
    sendErrorResponse(resp, ex.errorCode, ex.getMessage, submissionId)

  /**
   * Send an error response. HTTP status is derived from the error code.
   */
  protected def sendErrorResponse(resp: HttpServletResponse, errorCode: String,
                                  message: String, submissionId: String = null): Unit = {
    val status = httpStatusFor(errorCode)
    val errorResponse = ErrorResponse.of(submissionId, status, errorCode, message)
    sendJson(resp, status, errorResponse)
  }

  private def httpStatusFor(errorCode: String): Int = errorCode match {
    case ErrorCode.BadRequest | ErrorCode.InvalidSparkSubmitArgs => HttpStatus.BadRequest
    case ErrorCode.UnsupportedMediaType => HttpStatus.UnsupportedMediaType
    case ErrorCode.MethodNotAllowed => HttpStatus.MethodNotAllowed
    case ErrorCode.DriverPodAlreadyExists => HttpStatus.Conflict
    case ErrorCode.SubmissionFailed => HttpStatus.UnprocessableEntity
    case ErrorCode.SubmitterOverloaded => HttpStatus.ServiceUnavailable
    case _ => HttpStatus.InternalServerError
  }

  /**
   * Log a submission failure with stack trace.
   */
  protected def logSubmissionFailure(cause: Throwable): Unit = {
    log.error(s"${LogPrefix.Error} ${cause.getMessage}", cause)
  }
}
