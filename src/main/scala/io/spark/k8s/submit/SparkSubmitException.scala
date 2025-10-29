package io.spark.k8s.submit

/**
 * Unified exception for Spark submission errors.
 *
 * `errorType` encodes the HTTP contract that maps to operator circuit breaker behavior:
 *
 * ErrorType          HTTP   Operator action
 * ---------          ----   ------------------------------------------------
 * ValidationFailed   400    Pass back to caller (client-side error)
 * SubmissionFailed   422    Pass back to caller (cluster/admission said no)
 * Transient          503    Retry, trip breaker on repeated failures
 * (apiserver flake, 401 token race, 429, upstream 5xx)
 *
 * A 500 is produced by the generic handler for unhandled exceptions only - those
 * signal a bug in the submitter itself or non-recoverable environment problems.
 */
case class SparkSubmitException(
                                 message: String,
                                 errorType: SparkSubmitException.ErrorType = SparkSubmitException.SubmissionFailed,
                                 details: Option[String] = None,
                                 cause: Option[Throwable] = None
                               ) extends RuntimeException(message, cause.orNull) {
  def getDetails: String = details.orNull

  def isValidationError: Boolean = errorType == SparkSubmitException.ValidationFailed

  def isTransient: Boolean = errorType == SparkSubmitException.Transient
}

object SparkSubmitException {
  sealed trait ErrorType

  private case object ValidationFailed extends ErrorType // 400

  private case object SubmissionFailed extends ErrorType // 422

  private case object Transient extends ErrorType // 503

  // Convenience constructors
  def validation(msg: String, details: String = null): SparkSubmitException =
    SparkSubmitException(msg, ValidationFailed, Option(details))

  def validation(msg: String, cause: Throwable): SparkSubmitException =
    SparkSubmitException(msg, ValidationFailed, Option(cause.getMessage), Option(cause))

  def submission(msg: String, details: String = null): SparkSubmitException =
    SparkSubmitException(msg, SubmissionFailed, Option(details))

  def submission(msg: String, cause: Throwable): SparkSubmitException =
    SparkSubmitException(msg, SubmissionFailed, Option(cause.getMessage), Option(cause))

  /**
   * Transient upstream failure the caller should retry. Maps to HTTP 503 so the
   * operator's circuit breaker can distinguish "try again" (503) from "give up"
   * (4xx) and "crash" (500).
   *
   * Named `retryable` rather than `transient` because the latter is a Java
   * reserved keyword and can't be called from Java source.
   */
  def retryable(msg: String, details: String = null): SparkSubmitException =
    SparkSubmitException(msg, Transient, Option(details))

  def retryable(msg: String, cause: Throwable): SparkSubmitException =
    SparkSubmitException(msg, Transient, Option(cause.getMessage), Option(cause))
}
