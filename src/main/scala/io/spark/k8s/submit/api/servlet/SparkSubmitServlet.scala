package io.spark.k8s.submit.api.servlet

import io.spark.k8s.submit._
import io.spark.k8s.submit.api._
import io.spark.k8s.submit.model.{SparkSubmitRequest, SparkSubmitResponse}
import io.spark.k8s.submit.service.SparkSubmitter
import jakarta.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}
import org.slf4j.{Logger, LoggerFactory, MDC}

import java.io.IOException
import scala.util.{Failure, Success, Try}

/**
 * Servlet handling Spark job submission API.
 *
 * Endpoints:
 * - POST /spark-submit              → Submit Spark job (creates K8s resources)
 * - POST /spark-submit?dryRun=true  → Validate only (no K8s resources created)
 */
class SparkSubmitServlet(sparkSubmitter: SparkSubmitter) extends HttpServlet with JsonSupport with ErrorSupport {

  protected val log: Logger = LoggerFactory.getLogger(getClass)

  override def doPost(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
    var submissionId: String = null
    try {
      Try {
        val contentType = req.getContentType
        if (contentType == null || !contentType.startsWith(MediaType.ApplicationJson)) {
          throw SparkSubmitException.of(ErrorCode.UnsupportedMediaType, Messages.ContentTypeMustBeJson)
        }

        val dryRun = Option(req.getParameter("dryRun")).exists(_.toLowerCase == "true")
        val request = parseJsonStream(req.getInputStream, classOf[SparkSubmitRequest]).get

        submissionId = request.submissionId
        MDC.put("submissionId", submissionId)

        if (dryRun) log.debug(s"${LogPrefix.Request} [DRY-RUN] $request")
        else log.debug(s"${LogPrefix.Request} $request")

        val response = sparkSubmitter.submitJob(request, dryRun)

        if (dryRun) log.info(s"${LogPrefix.Success} dryrun validation passed appName=${response.appName}")
        else logSubmissionSuccess(response)

        val status = if (dryRun || response.duplicateSubmission) HttpStatus.Ok else HttpStatus.Created
        sendJson(resp, status, response)
      } match {
        case Success(_) => // Already handled
        case Failure(ex: SparkSubmitException) =>
          logSubmissionFailure(ex)
          sendErrorResponse(resp, ex, submissionId)
        case Failure(ex: IOException) =>
          logSubmissionFailure(ex)
          sendErrorResponse(resp, ErrorCode.BadRequest, Messages.MalformedRequest, submissionId)
        case Failure(ex) =>
          logSubmissionFailure(ex)
          sendErrorResponse(resp, ErrorCode.InternalError, Messages.UnexpectedError, submissionId)
      }
    } finally {
      MDC.remove("submissionId")
    }
  }

  override def doGet(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
    sendErrorResponse(resp, ErrorCode.MethodNotAllowed,
      "Use POST with a JSON payload to submit Spark applications")
  }

  private def logSubmissionSuccess(response: SparkSubmitResponse): Unit = {
    val msg = s"${LogPrefix.Success}" +
      s" namespace=${response.namespace}" +
      s" spark_app_id=${response.sparkAppId}" +
      s" app_name=${response.appName}" +
      s" driver_pod_name=${response.driverPodName}" +
      s" driver_pod_uid=${response.driverPodUid}"
    log.info(msg)
  }
}
