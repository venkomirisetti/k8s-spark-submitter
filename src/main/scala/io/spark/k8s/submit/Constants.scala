package io.spark.k8s.submit

/** Spark submission constants and configuration keys. */
object SparkConstants {
  // Spark configuration keys
  final val AppName = "spark.app.name"
  final val AppId = "spark.app.id"
  final val DriverPodSuffix = "-driver"

  // Kubernetes label keys
  final val SparkAppSelectorLabel = "spark-app-selector"

  // System properties
  final val JavaIoTmpDir = "java.io.tmpdir"

  // Pod template file names
  final val DriverPodTemplate = "driver-pod-template.json"
  final val ExecutorPodTemplate = "executor-pod-template.json"
  final val TempDirectory = ".spark-submitter"

  // Default pod template container names (must match spark-operator constants)
  final val DriverContainerName = "spark-kubernetes-driver"
  final val ExecutorContainerName = "spark-kubernetes-executor"
}

/** Log prefixes for structured logging and Splunk searching. */
object LogPrefix {
  final val Request = "[sf-spark-submit-request]"
  final val Success = "[sf-spark-submit-success]"
  final val Error = "[sf-spark-submit-error]"
  final val Resources = "[sf-spark-resources]"
  final val DryRun = "[sf-spark-submit-dryrun]"
}
