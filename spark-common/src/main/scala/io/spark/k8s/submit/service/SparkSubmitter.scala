package io.spark.k8s.submit.service

import io.fabric8.kubernetes.client.KubernetesClientException
import io.spark.k8s.submit.api.{ErrorCode, HttpStatus}
import io.spark.k8s.submit.model.{SparkSubmitRequest, SparkSubmitResponse}
import io.spark.k8s.submit.util.PodTemplateUtils
import io.spark.k8s.submit.{LogPrefix, SparkConstants, SparkSubmitException}
import org.apache.commons.lang3.StringUtils
import org.apache.spark.SparkConf
import org.apache.spark.deploy.K8sSparkSubmitArgsParser
import org.apache.spark.deploy.k8s.submit.K8sSparkClient
import org.apache.spark.deploy.k8s.submit.K8sSparkClient._
import org.slf4j.{Logger, LoggerFactory}

import java.io.IOException
import java.net.{ConnectException, SocketTimeoutException}
import java.nio.file.Path
import java.util.{Arrays => JavaArrays}

/**
 * Core service for submitting Spark jobs to Kubernetes.
 *
 * Responsibilities:
 * - Parses spark-submit CLI arguments via [[K8sSparkSubmitArgsParser]]
 * - Manages pod templates (driver/executor) as temp files for K8s submission
 * - Delegates K8s resource creation to [[K8sSparkClient]]
 * - Handles duplicate submission on K8s 409 Conflict (same submission-id → return existing pod)
 * - Maps K8s client exceptions to [[SparkSubmitException]] with structured error codes (see [[resolveErrorCode]])
 *
 * Supports dry-run mode (?dryRun=true) for K8s server-side validation without persisting resources.
 */
class SparkSubmitter(k8sProvider: KubernetesClientProvider) {

  private val log: Logger = LoggerFactory.getLogger(getClass)

  // Initialize on construction
  PodTemplateUtils.cleanupOldTemplateDirs()

  def submitJob(request: SparkSubmitRequest, dryRun: Boolean = false): SparkSubmitResponse = {
    val args = K8sSparkSubmitArgsParser.parseArgs(request.sparkSubmitArgs)
    val conf = args.sparkConf

    // Get app name (already set by Spark's parser, defaults to mainClass or primaryResource filename)
    val appName = conf.get(SparkConstants.AppName)
    log.debug(s"Submitting: app=$appName, ns=${conf.get(K8sNamespaceKey, K8sNamespaceDefault)}, dryRun=$dryRun")

    // Tag driver pod with submission-id for duplicate submission detection
    conf.set(SparkConstants.SubmissionIdLabelConfKey, request.submissionId)

    val templateDirOpt = createPodTemplates(request, request.submissionId, conf)
    try {
      val r = K8sSparkClient.submit(args, k8sProvider.client, dryRun)
      SparkSubmitResponse.success(request.submissionId, appName, r.sparkAppId, r.driverPodName, r.driverPodUid, r.namespace)
    } catch {
      case k: KubernetesClientException if k.getCode == HttpStatus.Conflict =>
        handleConflict(request, conf, appName)
      case e: Exception => throw wrapAsSparkSubmitException(e, dryRun)
    } finally {
      templateDirOpt.foreach(PodTemplateUtils.deleteTempDir)
    }
  }

  /**
   * Handles K8s 409 Conflict by checking if the existing pod belongs to the same submission.
   * Same submission-id → duplicate submission (return existing pod details).
   * Different submission-id or missing label → genuine conflict (throw error).
   */
  private def handleConflict(request: SparkSubmitRequest, conf: SparkConf, appName: String): SparkSubmitResponse = {
    val ns = conf.get(K8sNamespaceKey, K8sNamespaceDefault)
    val submissionId = request.submissionId

    import scala.jdk.CollectionConverters._

    val existingPod = k8sProvider.client.pods().inNamespace(ns)
      .withLabel(SparkConstants.SubmissionIdLabel, submissionId)
      .list().getItems.asScala.headOption
      .getOrElse(throw SparkSubmitException.of(ErrorCode.DriverPodAlreadyExists, "Driver pod already exists for a different submission"))

    val sparkAppId = existingPod.getMetadata.getLabels.get(SparkConstants.SparkAppSelectorLabel)

    log.info(s"${LogPrefix.Success} Duplicate submission: submissionId=$submissionId, pod=${existingPod.getMetadata.getName}")

    SparkSubmitResponse.duplicateSubmission(
      submissionId, appName, sparkAppId,
      existingPod.getMetadata.getName,
      existingPod.getMetadata.getUid,
      ns
    )
  }

  /** Submit with raw spark-submit args (like SparkSubmit.Main). */
  def submitJob(args: Array[String]): SparkSubmitResponse = {
    submitJob(SparkSubmitRequest(sparkSubmitArgs = JavaArrays.asList(args: _*)))
  }

  private def createPodTemplates(request: SparkSubmitRequest, submissionId: String, conf: SparkConf): Option[Path] = {
    val hasTemplates = request.driverTemplate.exists(_.nonEmpty) || request.executorTemplate.exists(_.nonEmpty)
    if (!hasTemplates) return None

    val templateDir = PodTemplateUtils.createTemplateDirForSubmission(submissionId)
    configurePodTemplate(request.driverTemplate, K8sDriverTemplateKey, SparkConstants.DriverPodTemplate, templateDir, conf)
    configurePodTemplate(request.executorTemplate, K8sExecutorTemplateKey, SparkConstants.ExecutorPodTemplate, templateDir, conf)

    conf.setIfMissing(K8sSparkClient.K8sDriverTemplateContainerNameKey, SparkConstants.DriverContainerName)
    conf.setIfMissing(K8sSparkClient.K8sExecutorTemplateContainerNameKey, SparkConstants.ExecutorContainerName)

    Some(templateDir)
  }

  private def configurePodTemplate(template: Option[String], confKey: String, fileName: String,
                                   dir: Path, conf: SparkConf): Unit =
    template.foreach { content =>
      val path = PodTemplateUtils.writeTempFile(content, fileName, dir)
      path.foreach(p => conf.set(confKey, p.toAbsolutePath.toString))
    }

  /** Extracts a clean error message from the cause and wraps it as a SparkSubmitException with a resolved error code. */
  private def wrapAsSparkSubmitException(e: Exception, isDryRun: Boolean): SparkSubmitException = {
    val errPrefix = if (isDryRun) s"${LogPrefix.DryRun} " else StringUtils.EMPTY
    val msg = e match {
      case k: KubernetesClientException if k.getStatus != null && k.getStatus.getMessage != null =>
        k.getStatus.getMessage
      case _ =>
        Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
    }
    val errorCode = resolveErrorCode(e)
    SparkSubmitException.of(errorCode, s"${errPrefix}Failed to submit: $msg", e)
  }

  /**
   * Maps K8s client and network exceptions to structured error codes:
   *
   * Exception                          Error Code
   * ---------                          ----------
   * K8s 429 TooManyRequests            SUBMITTER_OVERLOADED
   * K8s 401, 403, 5xx (cluster errors) INTERNAL_SERVER_ERROR
   * K8s 404 Not Found                  INVALID_SPARK_SUBMIT_ARGS
   * K8s 422 Unprocessable              SUBMISSION_FAILED
   * Other K8s errors                   INTERNAL_SERVER_ERROR
   * IOException (network/transient)    SUBMITTER_OVERLOADED
   * Unknown                            INTERNAL_SERVER_ERROR
   */
  private def resolveErrorCode(e: Exception): String = e match {
    case k: KubernetesClientException if k.getCode == HttpStatus.TooManyRequests =>
      ErrorCode.SubmitterOverloaded
    case _: ConnectException | _: SocketTimeoutException | _: IOException =>
      ErrorCode.SubmitterOverloaded
    case k: KubernetesClientException if k.getCode == HttpStatus.Unauthorized
      || k.getCode == HttpStatus.Forbidden || k.getCode >= HttpStatus.InternalServerError =>
      ErrorCode.InternalError
    case k: KubernetesClientException => k.getCode match {
      case HttpStatus.NotFound            => ErrorCode.InvalidSparkSubmitArgs
      case HttpStatus.UnprocessableEntity => ErrorCode.SubmissionFailed
      case _                              => ErrorCode.InternalError
    }
    case _ =>
      ErrorCode.InternalError
  }
}
