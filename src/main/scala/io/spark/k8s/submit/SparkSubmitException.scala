package io.spark.k8s.submit

import io.spark.k8s.submit.api.ErrorCode

/**
 * Unified exception for Spark submission errors.
 *
 * Carries a structured error code (from [[ErrorCode]]) and a user-facing message.
 * Each error code is assigned at the point of failure — see [[SparkSubmitter.resolveErrorCode]],
 * [[K8sSparkSubmitArgsParser]], and [[PodTemplateUtils]] for specific mappings.
 *
 * HTTP status is derived from the error code via [[ErrorCode.httpStatusFor]].
 */
case class SparkSubmitException(
    errorCode: String,
    message: String,
    cause: Option[Throwable] = None
) extends RuntimeException(message, cause.orNull)

object SparkSubmitException {

  def of(errorCode: String, msg: String, cause: Throwable = null): SparkSubmitException =
    SparkSubmitException(errorCode, msg, Option(cause))
}
