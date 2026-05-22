package org.apache.spark.deploy

import io.spark.k8s.submit.SparkConstants
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.{Arrays => JavaArrays}
import scala.util.Try

/**
 * Tests for K8sSparkSubmitArgsParser - a thin wrapper around Spark's prepareSubmitEnvironment.
 *
 * We only test that our wrapper correctly invokes Spark's API and returns a well-formed
 * K8sSparkSubmitArgs. Spark's parser behavior is tested extensively by Spark itself.
 */
class K8sSparkSubmitArgsParserTest extends AnyFlatSpec with Matchers {

  "K8sSparkSubmitArgsParser" should "parse all argument types into K8sSparkSubmitArgs" in {
    val master = "k8s://https://kubernetes.default.svc"
    val deployMode = "cluster"
    val mainClass = "org.apache.spark.examples.SparkPi"
    val appName = "test-app"
    val namespace = "test-ns"
    val driverMemory = "2g"
    val executorMemory = "4g"
    val jar = "local:///app.jar"

    val args = JavaArrays.asList(
      "--master", master,
      "--deploy-mode", deployMode,
      "--class", mainClass,
      "--name", appName,
      "--conf", s"spark.kubernetes.namespace=$namespace",
      "--driver-memory", driverMemory,
      "--executor-memory", executorMemory,
      jar,
      "arg1", "arg2"
    )

    val result = K8sSparkSubmitArgsParser.parseArgs(args)

    result.mainClass shouldBe mainClass
    result.primaryResource shouldBe jar
    result.appArgs.size() shouldBe 2
    result.appArgs.get(0) shouldBe "arg1"
    result.sparkConf.get("spark.master") shouldBe master
    result.sparkConf.get("spark.submit.deployMode") shouldBe deployMode
    result.sparkConf.get(SparkConstants.AppName) shouldBe appName
    result.sparkConf.get("spark.kubernetes.namespace") shouldBe namespace
    result.sparkConf.get("spark.driver.memory") shouldBe driverMemory
    result.sparkConf.get("spark.executor.memory") shouldBe executorMemory
  }

  it should "generate default app name from mainClass when not provided" in {
    val args = JavaArrays.asList(
      "--master", "k8s://https://kubernetes.default.svc",
      "--class", "org.apache.spark.examples.SparkPi",
      "local:///app.jar"
    )

    val result = K8sSparkSubmitArgsParser.parseArgs(args)

    result.sparkConf.get(SparkConstants.AppName) should include("SparkPi")
  }

  it should "handle empty --jars value gracefully" in {
    val args = JavaArrays.asList(
      "--master", "k8s://https://kubernetes.default.svc",
      "--jars", "",
      "--class", "Main",
      "local:///main.jar"
    )

    val result = K8sSparkSubmitArgsParser.parseArgs(args)
    result.mainClass shouldBe "Main"
  }

  it should "throw on malformed arguments" in {
    val args = JavaArrays.asList(
      "--master", "k8s://https://kubernetes.default.svc",
      "--class", "Main",
      "--jars"
    )

    Try(K8sSparkSubmitArgsParser.parseArgs(args)) match {
      case scala.util.Success(_) => fail("Should have thrown")
      case scala.util.Failure(e) => e.getMessage should not be empty
    }
  }
}
