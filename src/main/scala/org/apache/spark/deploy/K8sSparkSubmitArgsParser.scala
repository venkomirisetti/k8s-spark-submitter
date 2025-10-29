package org.apache.spark.deploy

import org.apache.spark.SparkConf

import scala.jdk.CollectionConverters._

/** Parsed spark-submit arguments. */
case class K8sSparkSubmitArgs(
                               sparkConf: SparkConf,
                               mainClass: String,
                               primaryResource: String,
                               appArgs: java.util.List[String]
                             )

/**
 * Parses spark-submit CLI arguments using Spark's internal classes.
 *
 * Package: org.apache.spark.deploy - required to access package-private classes:
 *   - SparkSubmitArguments: parses CLI args (--master, --class, --conf, etc.)
 *   - SparkSubmit.prepareSubmitEnvironment: maps CLI options to SparkConf keys
 *
 * This gives us 100% compatibility with spark-submit behavior.
 *
 * Throws raw exceptions (IllegalArgumentException, SparkException, etc.) — error translation
 * to API contract is handled by the service layer (SparkSubmitter).
 */
object K8sSparkSubmitArgsParser {
  def parseArgs(args: java.util.List[String]): K8sSparkSubmitArgs = {
    val sparkArgs = new SparkSubmitArguments(args.asScala.toSeq, Map.empty)
    val (_, _, sparkConf, _) = new SparkSubmit().prepareSubmitEnvironment(sparkArgs, conf = None)
    K8sSparkSubmitArgs(sparkConf, sparkArgs.mainClass, sparkArgs.primaryResource, sparkArgs.childArgs.asJava)
  }
}
