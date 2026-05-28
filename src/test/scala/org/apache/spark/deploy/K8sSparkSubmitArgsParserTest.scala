package org.apache.spark.deploy

import io.spark.k8s.submit.{SparkConstants, SparkSubmitException}
import org.apache.spark.deploy.k8s.submit.{JavaMainAppResource, PythonMainAppResource, RMainAppResource}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.{Arrays => JavaArrays}
import scala.util.Try

/**
 * Tests for K8sSparkSubmitArgsParser.
 *
 * Verifies argument parsing, deploy-mode validation, Python/R mainClass resolution,
 * and spark-defaults.conf support.
 */
class K8sSparkSubmitArgsParserTest extends AnyFlatSpec with Matchers {

  // ========== Test Constants ==========

  // Spark CLI Arguments
  private val MasterArg = "--master"
  private val DeployModeArg = "--deploy-mode"
  private val ClassArg = "--class"
  private val NameArg = "--name"
  private val ConfArg = "--conf"
  private val JarsArg = "--jars"
  private val ProxyUserArg = "--proxy-user"
  private val PropertiesFileArg = "--properties-file"
  private val DriverMemoryArg = "--driver-memory"
  private val ExecutorMemoryArg = "--executor-memory"

  // Common test values
  private val DefaultK8sMaster = "k8s://https://kubernetes.default.svc"
  private val ClusterMode = "cluster"
  private val ClientMode = "client"
  private val SparkPiClass = "org.apache.spark.examples.SparkPi"
  private val MainClass = "com.example.Main"
  private val SimpleMainClass = "Main"
  private val LocalJar = "local:///app.jar"
  private val LocalMainJar = "local:///main.jar"
  private val SparkImage = "spark:4.0.1"
  private val ApacheSparkImage = "apache/spark:4.0.1"

  // SparkConf keys
  private val SparkMasterKey = "spark.master"
  private val SparkDeployModeKey = "spark.submit.deployMode"
  private val SparkNamespaceKey = "spark.kubernetes.namespace"
  private val SparkImageKey = "spark.kubernetes.container.image"
  private val SparkServiceAccountKey = "spark.kubernetes.authenticate.driver.serviceAccountName"
  private val SparkExecutorMemoryKey = "spark.executor.memory"
  private val SparkExecutorInstancesKey = "spark.executor.instances"
  private val SparkEventLogKey = "spark.eventLog.enabled"

  // Common conf values
  private val K8sImageConf = s"$SparkImageKey=$SparkImage"

  // Python/R test resources
  private val PythonPiFile = "local:///opt/spark/examples/src/main/python/pi.py"
  private val RDataFrameFile = "local:///opt/spark/examples/src/main/r/dataframe.R"
  private val PythonRunnerClass = "org.apache.spark.deploy.PythonRunner"
  private val RRunnerClass = "org.apache.spark.deploy.RRunner"

  // Test namespaces and values
  private val TestNamespace = "submitter"
  private val TestServiceAccount = "spark"
  private val TestAppName = "test-app"
  private val TestNamespaceValue = "test-ns"
  private val ProxyUserValue = "delegated-user"

  // ========== Basic parsing ==========

  "K8sSparkSubmitArgsParser" should "parse all argument types into K8sSparkSubmitArgs" in {
    val driverMemory = "2g"
    val executorMemory = "4g"
    val arg1 = "arg1"
    val arg2 = "arg2"

    val args = JavaArrays.asList(
      MasterArg, DefaultK8sMaster,
      DeployModeArg, ClusterMode,
      ClassArg, SparkPiClass,
      NameArg, TestAppName,
      ConfArg, s"$SparkNamespaceKey=$TestNamespaceValue",
      ConfArg, K8sImageConf,
      DriverMemoryArg, driverMemory,
      ExecutorMemoryArg, executorMemory,
      LocalJar,
      arg1, arg2
    )

    val result = K8sSparkSubmitArgsParser.parseArgs(args)

    result.mainClass shouldBe SparkPiClass
    result.appArgs.length shouldBe 2
    result.appArgs(0) shouldBe arg1
    result.sparkConf.get(SparkMasterKey) shouldBe DefaultK8sMaster
    result.sparkConf.get(SparkDeployModeKey) shouldBe ClusterMode
    result.sparkConf.get(SparkConstants.AppName) shouldBe TestAppName
    result.sparkConf.get(SparkNamespaceKey) shouldBe TestNamespaceValue
    result.sparkConf.get("spark.driver.memory") shouldBe driverMemory
    result.sparkConf.get(SparkExecutorMemoryKey) shouldBe executorMemory
  }

  it should "generate default app name from mainClass when not provided" in {
    val args = JavaArrays.asList(
      MasterArg, DefaultK8sMaster,
      DeployModeArg, ClusterMode,
      ConfArg, K8sImageConf,
      ClassArg, SparkPiClass,
      LocalJar
    )

    val result = K8sSparkSubmitArgsParser.parseArgs(args)
    result.sparkConf.get(SparkConstants.AppName) should include("SparkPi")
  }

  it should "handle app arguments after jar" in {
    val appArg = "100"

    val args = JavaArrays.asList(
      MasterArg, DefaultK8sMaster,
      DeployModeArg, ClusterMode,
      ConfArg, K8sImageConf,
      ClassArg, SimpleMainClass,
      LocalMainJar,
      appArg
    )

    val result = K8sSparkSubmitArgsParser.parseArgs(args)
    result.mainClass shouldBe SimpleMainClass
    result.appArgs.length shouldBe 1
    result.appArgs(0) shouldBe appArg
    result.sparkConf.get(SparkMasterKey) shouldBe DefaultK8sMaster
  }

  // ========== Deploy mode validation ==========

  it should "reject client deploy mode" in {
    val args = JavaArrays.asList(
      MasterArg, DefaultK8sMaster,
      DeployModeArg, ClientMode,
      ConfArg, K8sImageConf,
      ClassArg, MainClass,
      LocalJar
    )

    val exception = intercept[SparkSubmitException] {
      K8sSparkSubmitArgsParser.parseArgs(args)
    }
    exception.isValidationError shouldBe true
    exception.getDetails should include(ClusterMode)
  }

  it should "reject client deploy mode passed via --conf" in {
    val args = JavaArrays.asList(
      ConfArg, s"$SparkMasterKey=$DefaultK8sMaster",
      ConfArg, s"$SparkDeployModeKey=$ClientMode",
      ConfArg, K8sImageConf,
      ClassArg, MainClass,
      LocalJar
    )

    val exception = intercept[SparkSubmitException] {
      K8sSparkSubmitArgsParser.parseArgs(args)
    }
    exception.isValidationError shouldBe true
    exception.getDetails should include(ClusterMode)
  }

  // ========== Python/R mainClass resolution ==========

  it should "resolve PythonRunner mainClass for Python files" in {
    val args = JavaArrays.asList(
      ConfArg, s"$SparkNamespaceKey=$TestNamespace",
      ConfArg, s"$SparkServiceAccountKey=$TestServiceAccount",
      ConfArg, s"$SparkDeployModeKey=$ClusterMode",
      ConfArg, s"$SparkImageKey=$ApacheSparkImage",
      ConfArg, s"$SparkMasterKey=$DefaultK8sMaster",
      PythonPiFile
    )

    val result = K8sSparkSubmitArgsParser.parseArgs(args)
    result.mainClass shouldBe PythonRunnerClass
    result.mainAppResource shouldBe PythonMainAppResource(PythonPiFile)
  }

  it should "resolve RRunner mainClass for R files with --master flag" in {
    val args = JavaArrays.asList(
      MasterArg, DefaultK8sMaster,
      DeployModeArg, ClusterMode,
      ConfArg, s"$SparkImageKey=$ApacheSparkImage",
      ConfArg, s"$SparkNamespaceKey=$TestNamespace",
      ConfArg, s"$SparkServiceAccountKey=$TestServiceAccount",
      RDataFrameFile
    )

    val result = K8sSparkSubmitArgsParser.parseArgs(args)
    result.mainClass shouldBe RRunnerClass
    result.mainAppResource shouldBe RMainAppResource(RDataFrameFile)
  }

  it should "resolve RRunner mainClass for R files with --conf style args" in {
    val args = JavaArrays.asList(
      ConfArg, s"$SparkNamespaceKey=$TestNamespace",
      ConfArg, s"$SparkServiceAccountKey=$TestServiceAccount",
      ConfArg, s"$SparkDeployModeKey=$ClusterMode",
      ConfArg, s"$SparkImageKey=$ApacheSparkImage",
      ConfArg, s"$SparkMasterKey=$DefaultK8sMaster",
      RDataFrameFile
    )

    val result = K8sSparkSubmitArgsParser.parseArgs(args)
    result.mainClass shouldBe RRunnerClass
    result.mainAppResource shouldBe RMainAppResource(RDataFrameFile)
  }

  it should "resolve correct mainAppResource type for Java, Python, and R" in {
    val javaArgs = JavaArrays.asList(
      MasterArg, DefaultK8sMaster,
      DeployModeArg, ClusterMode, ConfArg, K8sImageConf,
      ClassArg, MainClass, LocalJar
    )
    val pyArgs = JavaArrays.asList(
      MasterArg, DefaultK8sMaster,
      DeployModeArg, ClusterMode, ConfArg, K8sImageConf,
      "local:///app.py"
    )
    val rArgs = JavaArrays.asList(
      MasterArg, DefaultK8sMaster,
      DeployModeArg, ClusterMode, ConfArg, K8sImageConf,
      "local:///app.R"
    )

    K8sSparkSubmitArgsParser.parseArgs(javaArgs).mainAppResource shouldBe a[JavaMainAppResource]
    K8sSparkSubmitArgsParser.parseArgs(pyArgs).mainAppResource shouldBe a[PythonMainAppResource]
    K8sSparkSubmitArgsParser.parseArgs(rArgs).mainAppResource shouldBe a[RMainAppResource]
  }

  // ========== Proxy user ==========

  it should "pass through --proxy-user when provided" in {
    val args = JavaArrays.asList(
      MasterArg, DefaultK8sMaster,
      DeployModeArg, ClusterMode,
      ConfArg, K8sImageConf,
      ProxyUserArg, ProxyUserValue,
      ClassArg, MainClass,
      LocalJar
    )

    val result = K8sSparkSubmitArgsParser.parseArgs(args)
    result.proxyUser shouldBe Some(ProxyUserValue)
    result.mainClass shouldBe MainClass
  }

  it should "have None proxyUser when not provided" in {
    val args = JavaArrays.asList(
      MasterArg, DefaultK8sMaster,
      DeployModeArg, ClusterMode,
      ConfArg, K8sImageConf,
      ClassArg, MainClass,
      LocalJar
    )

    val result = K8sSparkSubmitArgsParser.parseArgs(args)
    result.proxyUser shouldBe None
  }

  // ========== Spark defaults file ==========

  it should "load properties from --properties-file" in {
    val propsFile = java.io.File.createTempFile("spark-defaults", ".conf")
    propsFile.deleteOnExit()
    val writer = new java.io.PrintWriter(propsFile)
    writer.println("spark.executor.memory 4g")
    writer.println("spark.executor.instances 8")
    writer.println("spark.eventLog.enabled true")
    writer.println(s"$SparkImageKey $SparkImage")
    writer.close()

    val args = JavaArrays.asList(
      MasterArg, DefaultK8sMaster,
      DeployModeArg, ClusterMode,
      PropertiesFileArg, propsFile.getAbsolutePath,
      ClassArg, MainClass,
      LocalJar
    )

    val result = K8sSparkSubmitArgsParser.parseArgs(args)
    result.sparkConf.get(SparkExecutorMemoryKey) shouldBe "4g"
    result.sparkConf.get(SparkExecutorInstancesKey) shouldBe "8"
    result.sparkConf.get(SparkEventLogKey) shouldBe "true"
  }

  it should "give --conf precedence over --properties-file" in {
    val propsFile = java.io.File.createTempFile("spark-defaults", ".conf")
    propsFile.deleteOnExit()
    val writer = new java.io.PrintWriter(propsFile)
    writer.println("spark.executor.memory 2g")
    writer.println(s"$SparkImageKey $SparkImage")
    writer.close()

    val args = JavaArrays.asList(
      MasterArg, DefaultK8sMaster,
      DeployModeArg, ClusterMode,
      PropertiesFileArg, propsFile.getAbsolutePath,
      ConfArg, s"$SparkExecutorMemoryKey=8g",
      ClassArg, MainClass,
      LocalJar
    )

    val result = K8sSparkSubmitArgsParser.parseArgs(args)
    result.sparkConf.get(SparkExecutorMemoryKey) shouldBe "8g"
  }

  // ========== Edge cases ==========

  it should "handle empty --jars value gracefully" in {
    val args = JavaArrays.asList(
      MasterArg, DefaultK8sMaster,
      DeployModeArg, ClusterMode,
      ConfArg, K8sImageConf,
      JarsArg, "",
      ClassArg, SimpleMainClass,
      LocalMainJar
    )

    val result = K8sSparkSubmitArgsParser.parseArgs(args)
    result.mainClass shouldBe SimpleMainClass
  }

  it should "throw on malformed arguments" in {
    val args = JavaArrays.asList(
      MasterArg, DefaultK8sMaster,
      DeployModeArg, ClusterMode,
      ClassArg, SimpleMainClass,
      JarsArg
    )

    Try(K8sSparkSubmitArgsParser.parseArgs(args)) match {
      case scala.util.Success(_) => fail("Should have thrown")
      case scala.util.Failure(e) => e.getMessage should not be empty
    }
  }
}
