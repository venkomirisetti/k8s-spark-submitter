package io.spark.k8s.submit.api

/** HTTP status codes used across the API. */
object HttpStatus {
  val Ok = 200
  val Created = 201
  val BadRequest = 400
  val Unauthorized = 401
  val Forbidden = 403
  val NotFound = 404
  val MethodNotAllowed = 405
  val UnsupportedMediaType = 415
  val Conflict = 409
  val UnprocessableEntity = 422
  val TooManyRequests = 429
  val InternalServerError = 500
  val ServiceUnavailable = 503
}

/** Versioned API endpoint paths. */
object ApiPaths {
  val Base = "/api/v1"
  val SparkSubmit = "/spark-submit"
}

/** Infrastructure endpoint paths (unversioned, probe port). */
object ManagementPaths {
  val Base = "/"
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
  val UnsupportedMediaType = "UNSUPPORTED_MEDIA_TYPE"
  val InvalidSparkSubmitArgs = "INVALID_SPARK_SUBMIT_ARGS"
  val MethodNotAllowed = "METHOD_NOT_ALLOWED"
  val InvalidPodTemplate = "INVALID_POD_TEMPLATE"
  val DriverPodAlreadyExists = "DRIVER_POD_ALREADY_EXISTS"
  val SubmitterOverloaded = "SUBMITTER_OVERLOADED"
  val InternalError = "INTERNAL_SERVER_ERROR"

}

/** User-facing API response messages. */
object Messages {
  final val SubmitSuccess = "Spark driver pod created successfully"
  final val DuplicateSubmission = "Driver pod already exists for this submission"
  final val MalformedRequest = "Malformed request body"
  final val ContentTypeMustBeJson = "Content-Type must be application/json"
  final val UnexpectedError = "An unexpected error occurred"
}

