package org.apache.spark.deploy

import io.spark.k8s.submit.SparkSubmitException
import io.spark.k8s.submit.api.Messages
import org.apache.spark.deploy.k8s.submit.{MainAppResource, ClientArguments => K8sClientArguments}
import org.apache.spark.{SparkConf, SparkException}

import scala.jdk.CollectionConverters._

/** Parsed spark-submit arguments for K8s cluster submission. */
case class K8sSparkSubmitArgs(
                               sparkConf: SparkConf,
                               mainAppResource: MainAppResource,
                               mainClass: String,
                               appArgs: Array[String],
                               proxyUser: Option[String]
                             )

/**
 * Parses spark-submit CLI arguments using Spark's internal private classes.
 *
 * Package: org.apache.spark.deploy - required to access package-private classes:
 *   - SparkSubmitArguments: parses CLI args (--master, --class, --conf, etc.)
 *   - SparkSubmit.prepareSubmitEnvironment: maps CLI options to SparkConf keys
 *   - ClientArguments: parses K8s childArgs (--main-class, --primary-py-file, etc.)
 *
 * This gives us 100% compatibility with spark-submit behavior.
 */
object K8sSparkSubmitArgsParser {

  /** Parses raw spark-submit args into a validated K8sSparkSubmitArgs for K8s cluster submission. */
  def parseArgs(args: java.util.List[String]): K8sSparkSubmitArgs = {
    try {
      val sparkArgs = new SparkSubmitArguments(args.asScala.toSeq)
      validateClusterMode(sparkArgs)
      val (childArgs, _, sparkConf, _) = new SparkSubmit().prepareSubmitEnvironment(sparkArgs, conf = None)

      val k8sArgs = K8sClientArguments.fromCommandLineArgs(childArgs.toArray)
      K8sSparkSubmitArgs(sparkConf, k8sArgs.mainAppResource, k8sArgs.mainClass, k8sArgs.driverArgs, k8sArgs.proxyUser)
    } catch {
      case e: SparkSubmitException => throw e
      case e: IllegalArgumentException => throw SparkSubmitException.validation(Messages.InvalidJobConfig, e)
      case e: SparkException => throw SparkSubmitException.validation(Messages.InvalidJobConfig, e)
      case e: Exception => throw SparkSubmitException.submission(s"Failed to parse arguments: ${e.getMessage}", e)
    }
  }

  /** Rejects non-cluster deploy modes early with a clear validation error. */
  private def validateClusterMode(sparkArgs: SparkSubmitArguments): Unit = {
    val mode = sparkArgs.deployMode
    if (mode != "cluster") {
      throw SparkSubmitException.validation(
        Messages.InvalidJobConfig,
        s"Only cluster deploy mode is supported (--deploy-mode cluster or --conf spark.submit.deployMode=cluster), received: $mode"
      )
    }
  }
}
