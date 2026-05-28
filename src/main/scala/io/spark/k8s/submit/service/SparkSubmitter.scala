package io.spark.k8s.submit.service

import io.fabric8.kubernetes.client.KubernetesClientException
import io.spark.k8s.submit.api.{HttpStatus, Messages}
import io.spark.k8s.submit.model.{SparkSubmitRequest, SparkSubmitResponse}
import io.spark.k8s.submit.util.PodTemplateUtils
import io.spark.k8s.submit.{LogPrefix, SparkConstants, SparkSubmitException}
import org.apache.commons.lang3.StringUtils
import org.apache.spark.deploy.K8sSparkSubmitArgsParser
import org.apache.spark.deploy.k8s.submit.K8sSparkClient
import org.apache.spark.deploy.k8s.submit.K8sSparkClient._
import org.apache.spark.{SparkConf, SparkException}
import org.slf4j.{Logger, LoggerFactory}

import java.io.IOException
import java.net.{ConnectException, SocketTimeoutException}
import java.nio.file.Path
import java.util.{UUID, Arrays => JavaArrays}

/**
 * Core service for submitting Spark jobs to Kubernetes.
 *
 * Responsibilities:
 * - Parses spark-submit CLI arguments via [[K8sSparkSubmitArgsParser]]
 * - Manages pod templates (driver/executor) as temp files for K8s submission
 * - Delegates K8s resource creation to [[K8sSparkClient]]
 * - Translates raw infrastructure exceptions into [[SparkSubmitException]] with retry semantics:
 *   - 401, 429, 5xx, network errors → retryable (503)
 *   - 403, 4xx, parse errors → terminal (400/422)
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

    val templateDirOpt = createPodTemplates(request, conf)
    try {
      val r = K8sSparkClient.submit(args, k8sProvider.client, dryRun)
      SparkSubmitResponse.success(appName, r.sparkAppId, r.driverPodName, r.driverPodUid, r.namespace)
    } catch {
      case e: Exception => throw wrapAsSparkSubmitException(e, dryRun)
    } finally {
      templateDirOpt.foreach(PodTemplateUtils.deleteTempDir)
    }
  }

  /** Submit with raw spark-submit args (like SparkSubmit.Main). */
  def submitJob(args: Array[String]): SparkSubmitResponse = {
    submitJob(SparkSubmitRequest(JavaArrays.asList(args: _*), null, null))
  }

  private def createPodTemplates(request: SparkSubmitRequest, conf: SparkConf): Option[Path] = {
    val hasTemplates = request.driverTemplate.exists(_.nonEmpty) || request.executorTemplate.exists(_.nonEmpty)
    if (!hasTemplates) return None

    // Use epoch (nanoseconds) for unique, chronologically-sortable directory name
    val templateDir = PodTemplateUtils.createTemplateDirForSubmission(s"submission_${System.nanoTime()}_${UUID.randomUUID()}")
    configurePodTemplate(request.driverTemplate, K8sDriverTemplateKey, SparkConstants.DriverPodTemplate, templateDir, conf)
    configurePodTemplate(request.executorTemplate, K8sExecutorTemplateKey, SparkConstants.ExecutorPodTemplate, templateDir, conf)
    Some(templateDir)
  }

  private def configurePodTemplate(template: Option[String], confKey: String, fileName: String,
                                   dir: Path, conf: SparkConf): Unit =
    template.foreach { content =>
      val path = PodTemplateUtils.writeTempFile(content, fileName, dir)
      path.foreach(p => conf.set(confKey, p.toAbsolutePath.toString))
    }

  /**
   * Wraps a submission failure into a SparkSubmitException with appropriate retry semantics.
   *
   * Mapping:
   * - IllegalArgumentException, SparkException → Validation error (400)
   * - K8s API 401, 429, 5xx, network errors → Retryable (503)
   * - K8s API 403, 4xx → Submission error (422)
   * - Unknown exceptions → Submission error (422)
   */
  private def wrapAsSparkSubmitException(e: Exception, isDryRun: Boolean): SparkSubmitException = {
    val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
    val errPrefix = if (isDryRun) s"${LogPrefix.DryRun} " else StringUtils.EMPTY

    e match {
      case _: IllegalArgumentException | _: SparkException =>
        SparkSubmitException.validation(s"$errPrefix${Messages.InvalidJobConfig}", e)
      case _ if isRetryable(e) =>
        SparkSubmitException.retryable(s"${errPrefix}Failed to submit: $msg", e)
      case _ =>
        SparkSubmitException.submission(s"${errPrefix}Failed to submit: $msg", e)
    }
  }

  /**
   * Determines if a failure is retryable (transient).
   *
   * Retryable:
   * - K8s API 401 Unauthorized (ServiceAccount token projection lag)
   * - K8s API 429 Too Many Requests (rate limit)
   * - K8s API 5xx Server Errors (ApiServer issues)
   * - Network failures (timeout, connection refused, IO errors)
   *
   * Non-retryable (treated as user/submission errors):
   * - K8s API 4xx (validation, RBAC denials)
   * - Unknown exceptions (conservative default)
   */
  private def isRetryable(e: Throwable): Boolean = e match {
    case k: KubernetesClientException => isTransientHttpCode(k.getCode)
    case _: ConnectException | _: SocketTimeoutException | _: IOException => true
    case _ => false
  }

  /** HTTP status codes that indicate transient/retryable failures (401, 429, 5xx). */
  private def isTransientHttpCode(code: Int): Boolean =
    code == HttpStatus.Unauthorized ||
      code == HttpStatus.TooManyRequests ||
      code >= HttpStatus.InternalServerError
}
