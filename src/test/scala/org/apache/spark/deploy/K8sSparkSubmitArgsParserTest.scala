package org.apache.spark.deploy

import io.spark.k8s.submit.SparkConstants
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.{Arrays => JavaArrays}
import scala.util.Try

/**
 * Tests for K8sSparkSubmitArgsParser - a thin wrapper around Spark's prepareSubmitEnvironment.
 *
 * Note: This parser delegates to Spark's internal argument parsing. We test that the wrapper
 * correctly invokes Spark's API, not that Spark's parser handles every possible configuration
 * (Spark already tests that extensively).
 */
class K8sSparkSubmitArgsParserTest extends AnyFlatSpec with Matchers {

  // CLI argument names
  private val MasterArg = "--master"
  private val DeployModeArg = "--deploy-mode"
  private val ClassArg = "--class"
  private val NameArg = "--name"
  private val ConfArg = "--conf"
  private val DriverMemoryArg = "--driver-memory"
  private val ExecutorMemoryArg = "--executor-memory"

  // Spark configuration keys
  private val SparkMasterKey = "spark.master"
  private val SparkDeployModeKey = "spark.submit.deployMode"
  private val SparkNamespaceKey = "spark.kubernetes.namespace"
  private val SparkDriverMemoryKey = "spark.driver.memory"
  private val SparkExecutorMemoryKey = "spark.executor.memory"
  private val SparkJarsKey = "spark.jars"
  private val SparkFilesKey = "spark.files"
  private val SparkArchivesKey = "spark.archives"
  private val SparkPyFilesKey = "spark.submit.pyFiles"

  // Resource argument names
  private val JarsArg = "--jars"
  private val FilesArg = "--files"
  private val ArchivesArg = "--archives"
  private val PyFilesArg = "--py-files"

  // Environment issue detection strings
  private val HttpResponseCodeStr = "HTTP response code"
  private val HadoopConfDirStr = "HADOOP_CONF_DIR"
  private val YarnConfDirStr = "YARN_CONF_DIR"
  private val DriverContainerImageStr = "driver container image"
  private val MavenCoordinatesStr = "Maven Coordinates"
  private val UnresolvedDependencyStr = "unresolved dependency"
  private val FileSystemSchemeStr = "FileSystem for scheme"

  private def isEnvironmentIssue(t: Throwable): Boolean = {
    if (t == null) return false

    val msg = Option(t.getMessage).getOrElse("")
    val isIssue = t match {
      case _: NoClassDefFoundError | _: ExceptionInInitializerError => true
      case _: java.io.FileNotFoundException | _: java.net.UnknownHostException => true
      case _: org.apache.spark.SparkUserAppException => true
      case _: org.apache.hadoop.fs.UnsupportedFileSystemException => true
      case _: java.io.IOException if msg.contains(HttpResponseCodeStr) => true
      case _: org.apache.spark.SparkException if msg.contains(HadoopConfDirStr) ||
        msg.contains(YarnConfDirStr) ||
        msg.contains(DriverContainerImageStr) => true
      case _: IllegalArgumentException if msg.contains(MavenCoordinatesStr) => true
      case _ if msg.contains(UnresolvedDependencyStr) ||
        msg.contains(FileSystemSchemeStr) => true
      case _ => false
    }

    isIssue || isEnvironmentIssue(t.getCause)
  }

  private def runTest(testName: String)(testFn: => Unit): Unit = {
    try {
      testFn
    } catch {
      case e: Throwable if isEnvironmentIssue(e) =>
        cancel(s"Environment issue - skipping '$testName': ${e.getClass.getSimpleName}")
      case e: Throwable => throw e
    }
  }

  // Unit tests for helper functions

  "K8sSparkSubmitArgsParser.extractParticularArgs" should "extract --jars flag and value" in {
    val args = Seq("--master", "k8s://...", "--jars", "s3://a.jar", "--class", "Main")
    val (sanitized, extracted) = K8sSparkSubmitArgsParser.extractParticularArgs(args)

    sanitized shouldBe Seq("--master", "k8s://...", "--class", "Main")
    extracted shouldBe Map("spark.jars" -> Seq("s3://a.jar"))
  }

  it should "extract comma-separated values" in {
    val args = Seq("--jars", "s3://a.jar,s3://b.jar,local:///c.jar")
    val (sanitized, extracted) = K8sSparkSubmitArgsParser.extractParticularArgs(args)

    sanitized shouldBe Seq.empty
    extracted shouldBe Map("spark.jars" -> Seq("s3://a.jar", "s3://b.jar", "local:///c.jar"))
  }

  it should "extract --conf spark.jars" in {
    val args = Seq("--conf", "spark.jars=s3://a.jar", "--class", "Main")
    val (sanitized, extracted) = K8sSparkSubmitArgsParser.extractParticularArgs(args)

    sanitized shouldBe Seq("--class", "Main")
    extracted shouldBe Map("spark.jars" -> Seq("s3://a.jar"))
  }

  it should "merge multiple --jars occurrences" in {
    val args = Seq("--jars", "s3://a.jar", "--jars", "s3://b.jar")
    val (sanitized, extracted) = K8sSparkSubmitArgsParser.extractParticularArgs(args)

    sanitized shouldBe Seq.empty
    extracted shouldBe Map("spark.jars" -> Seq("s3://a.jar", "s3://b.jar"))
  }

  it should "merge --jars and --conf spark.jars" in {
    val args = Seq("--jars", "s3://a.jar", "--conf", "spark.jars=s3://b.jar")
    val (sanitized, extracted) = K8sSparkSubmitArgsParser.extractParticularArgs(args)

    sanitized shouldBe Seq.empty
    extracted shouldBe Map("spark.jars" -> Seq("s3://a.jar", "s3://b.jar"))
  }

  it should "extract all four resource types" in {
    val args = Seq(
      "--jars", "s3://a.jar",
      "--files", "gs://b.txt",
      "--archives", "hdfs://c.zip",
      "--py-files", "s3://d.py"
    )
    val (sanitized, extracted) = K8sSparkSubmitArgsParser.extractParticularArgs(args)

    sanitized shouldBe Seq.empty
    extracted shouldBe Map(
      "spark.jars" -> Seq("s3://a.jar"),
      "spark.files" -> Seq("gs://b.txt"),
      "spark.archives" -> Seq("hdfs://c.zip"),
      "spark.submit.pyFiles" -> Seq("s3://d.py")
    )
  }

  it should "preserve non-resource --conf flags" in {
    val args = Seq(
      "--conf", "spark.executor.memory=2g",
      "--jars", "s3://a.jar",
      "--conf", "spark.driver.cores=4"
    )
    val (sanitized, extracted) = K8sSparkSubmitArgsParser.extractParticularArgs(args)

    sanitized shouldBe Seq("--conf", "spark.executor.memory=2g", "--conf", "spark.driver.cores=4")
    extracted shouldBe Map("spark.jars" -> Seq("s3://a.jar"))
  }

  it should "handle empty values gracefully" in {
    val args = Seq("--jars", "", "--class", "Main")
    val (sanitized, extracted) = K8sSparkSubmitArgsParser.extractParticularArgs(args)

    sanitized shouldBe Seq("--class", "Main")
    extracted shouldBe Map.empty
  }

  it should "handle mixed comma-separated and multiple occurrences" in {
    val args = Seq("--jars", "s3://a.jar,s3://b.jar", "--jars", "s3://c.jar")
    val (sanitized, extracted) = K8sSparkSubmitArgsParser.extractParticularArgs(args)

    sanitized shouldBe Seq.empty
    extracted shouldBe Map("spark.jars" -> Seq("s3://a.jar", "s3://b.jar", "s3://c.jar"))
  }

  "K8sSparkSubmitArgsParser.addExtractedArgs" should "add extracted values to empty SparkConf" in {
    val sparkConf = new org.apache.spark.SparkConf()
    val extracted = Map("spark.jars" -> Seq("s3://a.jar", "s3://b.jar"))

    K8sSparkSubmitArgsParser.addExtractedArgs(sparkConf, extracted)

    sparkConf.get("spark.jars") shouldBe "s3://a.jar,s3://b.jar"
  }

  it should "merge with existing spark.jars (e.g., primaryResource added by Spark)" in {
    // Simulates Spark adding primaryResource to spark.jars
    val sparkConf = new org.apache.spark.SparkConf()
    sparkConf.set("spark.jars", "local:///main.jar")  // Spark adds this
    val extracted = Map("spark.jars" -> Seq("s3://a.jar", "s3://b.jar"))

    K8sSparkSubmitArgsParser.addExtractedArgs(sparkConf, extracted)

    // Should preserve Spark-added jar and append extracted jars
    sparkConf.get("spark.jars") shouldBe "local:///main.jar,s3://a.jar,s3://b.jar"
  }

  it should "add multiple properties (files, archives should be empty in SparkConf)" in {
    val sparkConf = new org.apache.spark.SparkConf()
    // Spark may add primaryResource to spark.jars, but NOT to files/archives/pyFiles
    sparkConf.set("spark.jars", "local:///main.jar")
    val extracted = Map(
      "spark.jars" -> Seq("s3://a.jar"),
      "spark.files" -> Seq("gs://b.txt"),  // SparkConf should have nothing here
      "spark.archives" -> Seq("hdfs://c.zip")  // SparkConf should have nothing here
    )

    K8sSparkSubmitArgsParser.addExtractedArgs(sparkConf, extracted)

    sparkConf.get("spark.jars") shouldBe "local:///main.jar,s3://a.jar"
    sparkConf.get("spark.files") shouldBe "gs://b.txt"
    sparkConf.get("spark.archives") shouldBe "hdfs://c.zip"
  }

  it should "handle empty extracted map (no-op)" in {
    val sparkConf = new org.apache.spark.SparkConf()
    sparkConf.set("spark.jars", "local:///main.jar")

    K8sSparkSubmitArgsParser.addExtractedArgs(sparkConf, Map.empty)

    // Should remain unchanged
    sparkConf.get("spark.jars") shouldBe "local:///main.jar"
  }

  // Integration tests

  "K8sSparkSubmitArgsParser" should "parse spark-submit arguments via Spark's prepareSubmitEnvironment" in {
    runTest("parse spark-submit arguments") {
      val master = "k8s://https://kubernetes.default.svc"
      val deployMode = "cluster"
      val mainClass = "org.apache.spark.examples.SparkPi"
      val appName = "test-app"
      val namespace = "test-ns"
      val driverMemory = "2g"
      val executorMemory = "4g"
      val jar = "local:///app.jar"
      val arg1 = "arg1"
      val arg2 = "arg2"

      val args = JavaArrays.asList(
        MasterArg, master,
        DeployModeArg, deployMode,
        ClassArg, mainClass,
        NameArg, appName,
        ConfArg, s"$SparkNamespaceKey=$namespace",
        DriverMemoryArg, driverMemory,
        ExecutorMemoryArg, executorMemory,
        jar,
        arg1, arg2
      )

      val result = K8sSparkSubmitArgsParser.parseArgs(args)

      // Verify wrapper correctly extracts values from Spark's parser
      result.mainClass shouldBe mainClass
      result.primaryResource shouldBe jar
      result.appArgs.size() shouldBe 2
      result.appArgs.get(0) shouldBe arg1

      // Verify sparkConf contains values from various argument types
      result.sparkConf.get(SparkMasterKey) shouldBe master
      result.sparkConf.get(SparkDeployModeKey) shouldBe deployMode
      result.sparkConf.get(SparkConstants.AppName) shouldBe appName
      result.sparkConf.get(SparkNamespaceKey) shouldBe namespace
      result.sparkConf.get(SparkDriverMemoryKey) shouldBe driverMemory
      result.sparkConf.get(SparkExecutorMemoryKey) shouldBe executorMemory
    }
  }

  it should "generate default app name from mainClass when not provided" in {
    runTest("generate default app name") {
      val master = "k8s://https://kubernetes.default.svc"
      val mainClass = "org.apache.spark.examples.SparkPi"
      val jar = "local:///app.jar"
      val simpleClassName = "SparkPi"

      val args = JavaArrays.asList(
        MasterArg, master,
        ClassArg, mainClass,
        jar
      )

      val result = K8sSparkSubmitArgsParser.parseArgs(args)

      result.sparkConf.get(SparkConstants.AppName) should include(simpleClassName)
    }
  }

  it should "return K8sSparkSubmitArgs with all parsed components" in {
    runTest("return complete K8sSparkSubmitArgs") {
      val master = "k8s://https://kubernetes.default.svc"
      val mainClass = "org.apache.spark.examples.SparkPi"
      val jar = "local:///app.jar"
      val arg1 = "arg1"

      val args = JavaArrays.asList(
        MasterArg, master,
        ClassArg, mainClass,
        jar,
        arg1
      )

      val result = K8sSparkSubmitArgsParser.parseArgs(args)

      // Verify all components are populated and have correct values
      result.mainClass shouldBe mainClass
      result.primaryResource shouldBe jar
      result.appArgs.size() shouldBe 1
      result.appArgs.get(0) shouldBe arg1
      result.sparkConf.get(SparkMasterKey) shouldBe master
    }
  }

  // Resource extraction integration tests

  it should "merge extracted --jars with Spark-added primaryResource" in {
    runTest("merge extracted jars with primaryResource") {
      val args = JavaArrays.asList(
        MasterArg, "k8s://https://kubernetes.default.svc",
        JarsArg, "s3://a.jar,s3://b.jar",
        ClassArg, "Main",
        "local:///main.jar"  // This will be added to spark.jars by Spark
      )

      val result = K8sSparkSubmitArgsParser.parseArgs(args)
      val jars = result.sparkConf.get(SparkJarsKey).split(",").toSet
      // Verify Spark-added primaryResource is preserved
      jars should contain("local:///main.jar")
      // Verify extracted jars are present
      jars should contain("s3://a.jar")
      jars should contain("s3://b.jar")
    }
  }

  it should "preserve other --conf arguments" in {
    runTest("preserve other --conf") {
      val args = JavaArrays.asList(
        MasterArg, "k8s://https://kubernetes.default.svc",
        ConfArg, "spark.executor.memory=2g",
        ClassArg, "Main",
        "local:///main.jar"
      )

      val result = K8sSparkSubmitArgsParser.parseArgs(args)
      result.sparkConf.get(SparkExecutorMemoryKey) shouldBe "2g"
    }
  }

  it should "preserve arguments that are not resource flags" in {
    runTest("preserve non-resource args") {
      val args = JavaArrays.asList(
        MasterArg, "k8s://https://kubernetes.default.svc",
        JarsArg, "s3://a.jar",
        ClassArg, "Main",
        "local:///main.jar"
      )

      val result = K8sSparkSubmitArgsParser.parseArgs(args)
      result.mainClass shouldBe "Main"
      result.primaryResource shouldBe "local:///main.jar"
      result.sparkConf.get(SparkMasterKey) shouldBe "k8s://https://kubernetes.default.svc"
    }
  }

  it should "not extract application arguments that look like URIs" in {
    runTest("preserve app args") {
      val args = JavaArrays.asList(
        MasterArg, "k8s://https://kubernetes.default.svc",
        JarsArg, "s3://a.jar",
        ClassArg, "Main",
        "local:///main.jar",
        "s3://input",
        "s3://output"
      )

      val result = K8sSparkSubmitArgsParser.parseArgs(args)
      result.sparkConf.get(SparkJarsKey) should include("s3://a.jar")
      result.appArgs.size() shouldBe 2
      result.appArgs.get(0) shouldBe "s3://input"
      result.appArgs.get(1) shouldBe "s3://output"
    }
  }

  it should "extract all four resource types together" in {
    runTest("extract all four types") {
      val args = JavaArrays.asList(
        MasterArg, "k8s://https://kubernetes.default.svc",
        JarsArg, "s3://a.jar",
        FilesArg, "gs://b.txt",
        ArchivesArg, "hdfs://c.zip",
        PyFilesArg, "s3://d.py",
        "local:///app.py"
      )

      val result = K8sSparkSubmitArgsParser.parseArgs(args)
      result.sparkConf.get(SparkJarsKey) should include("s3://a.jar")
      result.sparkConf.get(SparkFilesKey) shouldBe "gs://b.txt"
      result.sparkConf.get(SparkArchivesKey) shouldBe "hdfs://c.zip"
      result.sparkConf.get(SparkPyFilesKey) shouldBe "s3://d.py"
    }
  }

  it should "handle realistic mixture scenario with k8s cluster mode" in {
    runTest("realistic k8s scenario") {
      val args = JavaArrays.asList(
        MasterArg, "k8s://api",
        DeployModeArg, "cluster",
        NameArg, "MyApp",
        JarsArg, "s3://app.jar,s3://lib.jar",
        ConfArg, "spark.executor.instances=10",
        ConfArg, "spark.executor.memory=4g",
        ClassArg, "com.example.Main",
        "s3://code/main.jar",
        "arg1", "arg2"
      )

      val result = K8sSparkSubmitArgsParser.parseArgs(args)
      result.sparkConf.get(SparkJarsKey).split(",").toSet should contain allOf("s3://app.jar", "s3://lib.jar")
      result.sparkConf.get(SparkExecutorMemoryKey) shouldBe "4g"
      result.sparkConf.get("spark.executor.instances") shouldBe "10"
      result.mainClass shouldBe "com.example.Main"
      result.appArgs.size() shouldBe 2
    }
  }

  it should "handle realistic mixture scenario with yarn" in {
    runTest("realistic yarn scenario") {
      val args = JavaArrays.asList(
        MasterArg, "local[*]",  // Use local instead of yarn to avoid yarn env requirement
        FilesArg, "gs://config/app.conf,gs://data/input.csv",
        ConfArg, "spark.driver.memory=2g",
        ConfArg, "spark.sql.shuffle.partitions=200",
        ClassArg, "ETLJob",
        "local:///etl.jar"
      )

      val result = K8sSparkSubmitArgsParser.parseArgs(args)
      result.sparkConf.get(SparkFilesKey).split(",").toSet should contain allOf("gs://config/app.conf", "gs://data/input.csv")
      result.sparkConf.get(SparkDriverMemoryKey) shouldBe "2g"
      result.sparkConf.get("spark.sql.shuffle.partitions") shouldBe "200"
    }
  }

  it should "handle realistic mixture scenario with Python app" in {
    runTest("realistic Python scenario") {
      val args = JavaArrays.asList(
        MasterArg, "k8s://https://kubernetes.default.svc",
        JarsArg, "s3://jars/dep1.jar,s3://jars/dep2.jar",
        PyFilesArg, "s3://libs/utils.py,s3://libs/helpers.py",
        ConfArg, "spark.kubernetes.container.image=myimage:latest",
        ConfArg, "spark.files=gs://config/prod.yaml",
        "local:///app.py",
        "--input", "s3://data",
        "--output", "s3://results"
      )

      val result = K8sSparkSubmitArgsParser.parseArgs(args)
      result.sparkConf.get(SparkJarsKey).split(",").toSet should contain allOf("s3://jars/dep1.jar", "s3://jars/dep2.jar")
      result.sparkConf.get(SparkPyFilesKey).split(",").toSet should contain allOf("s3://libs/utils.py", "s3://libs/helpers.py")
      // --conf spark.files gets merged with extracted files
      result.sparkConf.get(SparkFilesKey) shouldBe "gs://config/prod.yaml"
      // App args include everything after primaryResource
      result.appArgs.size() should be >= 4
    }
  }

  it should "handle realistic mixture with --conf resource flags" in {
    runTest("realistic --conf resource scenario") {
      val args = JavaArrays.asList(
        MasterArg, "local[*]",
        ConfArg, "spark.jars=s3://spark-jars/postgres.jar",
        ConfArg, "spark.driver.extraJavaOptions=-Dlog4j.level=DEBUG",
        ArchivesArg, "hdfs://archives/venv.zip#venv",
        DeployModeArg, "client",
        "local:///Main.py"
      )

      val result = K8sSparkSubmitArgsParser.parseArgs(args)
      result.sparkConf.get(SparkJarsKey) should include("s3://spark-jars/postgres.jar")
      result.sparkConf.get(SparkArchivesKey) shouldBe "hdfs://archives/venv.zip#venv"
      result.sparkConf.get("spark.driver.extraJavaOptions") shouldBe "-Dlog4j.level=DEBUG"
    }
  }

  it should "handle realistic mixture with local and remote resources" in {
    runTest("realistic mixed local/remote scenario") {
      val args = JavaArrays.asList(
        MasterArg, "local[4]",
        JarsArg, "file:///local/test.jar",
        FilesArg, "/tmp/config.properties",
        ConfArg, "spark.ui.port=4040",
        ConfArg, "spark.jars=s3://prod/app.jar",
        ClassArg, "TestRunner",
        "test.jar"
      )

      val result = K8sSparkSubmitArgsParser.parseArgs(args)
      val jars = result.sparkConf.get(SparkJarsKey).split(",").toSet
      jars should contain("file:///local/test.jar")
      jars should contain("s3://prod/app.jar")
      result.sparkConf.get(SparkFilesKey) shouldBe "/tmp/config.properties"
      result.sparkConf.get("spark.ui.port") shouldBe "4040"
    }
  }

  // Edge case tests

  it should "handle empty --jars value gracefully" in {
    runTest("empty --jars") {
      val args = JavaArrays.asList(
        MasterArg, "k8s://https://kubernetes.default.svc",
        JarsArg, "",
        ClassArg, "Main",
        "local:///main.jar"
      )

      val result = K8sSparkSubmitArgsParser.parseArgs(args)
      // Empty string should be filtered out
      result.mainClass shouldBe "Main"
    }
  }

  it should "not break when --jars has no following value" in {
    runTest("--jars without value") {
      val args = JavaArrays.asList(
        MasterArg, "k8s://https://kubernetes.default.svc",
        ClassArg, "Main",
        JarsArg
      )

      // Should throw validation error (invalid args passed through to Spark)
      Try(K8sSparkSubmitArgsParser.parseArgs(args)) match {
        case scala.util.Success(_) => fail("Should have thrown validation error")
        case scala.util.Failure(e) =>
          // Spark validation error - exact message may vary
          e.getMessage should not be empty
      }
    }
  }
}
