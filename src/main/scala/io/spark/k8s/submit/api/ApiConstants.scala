package io.spark.k8s.submit.api

/** HTTP status codes used across the API. */
object HttpStatus {
  val Ok = 200
  val Created = 201
  val BadRequest = 400
  val Unauthorized = 401
  val MethodNotAllowed = 405
  val UnsupportedMediaType = 415
  val UnprocessableEntity = 422
  val TooManyRequests = 429
  val InternalServerError = 500
  val ServiceUnavailable = 503
}

/** API endpoint paths. */
object ApiPaths {
  val Base = "/api/v1"
  val SparkSubmit = "/spark-submit"
  val Metrics = "/metrics"
  val Health = "/healthz"
}

/**
 * HTTP media type constants for servlet responses.
 * Matches Apache Spark's pattern of using string literals.
 */
object MediaType {
  val ApplicationJson: String = "application/json"
  val TextPlain: String = "text/plain"
  val PrometheusText: String = "text/plain; version=0.0.4; charset=utf-8"
}

/** Error code strings for API responses. */
object ErrorCode {
  val BadRequest = "BAD_REQUEST"
  val SubmissionFailed = "SUBMISSION_FAILED"
  val UnsupportedMediaType = "UNSUPPORTED_MEDIA_TYPE"
  val InternalError = "INTERNAL_SERVER_ERROR"
  val ServiceUnavailable = "SERVICE_UNAVAILABLE"
}

/** User-facing API response messages. */
object Messages {
  final val SubmitSuccess = "Spark driver pod created successfully"
  final val MalformedRequest = "Malformed request body"
  final val InvalidJobConfig = "Invalid job configuration"
  final val ContentTypeMustBeJson = "Content-Type must be application/json"
  final val UnexpectedError = "An unexpected error occurred"
  final val CannotCreateTemplateDir = "Cannot create template directory"
}
