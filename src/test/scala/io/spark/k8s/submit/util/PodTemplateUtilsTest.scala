package io.spark.k8s.submit.util

import io.spark.k8s.submit.{SparkConstants, SparkSubmitException}
import io.spark.k8s.submit.api.ErrorCode
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.util.Using

/**
 * Unit tests for PodTemplateUtils.
 * Uses BeforeAndAfterEach for automatic cleanup of temp directories and system properties.
 */
class PodTemplateUtilsTest extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var originalTmpDir: String = _
  private var tempTestDir: Path = _

  private def createTestSubmissionDir(): Path = {
    val dirName = s"submission_${System.nanoTime()}"
    PodTemplateUtils.createTemplateDirForSubmission(dirName)
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    originalTmpDir = System.getProperty(SparkConstants.JavaIoTmpDir)
    tempTestDir = Files.createTempDirectory("test-templates")
    System.setProperty(SparkConstants.JavaIoTmpDir, tempTestDir.toString)
  }

  override def afterEach(): Unit = {
    try {
      if (tempTestDir != null && Files.exists(tempTestDir)) {
        Using.resource(Files.walk(tempTestDir)) { stream =>
          stream.sorted(java.util.Comparator.reverseOrder())
            .forEach(Files.delete)
        }
      }
      if (originalTmpDir != null) {
        System.setProperty(SparkConstants.JavaIoTmpDir, originalTmpDir)
      }
    } finally {
      super.afterEach()
    }
  }

  "PodTemplateUtils" should "create template directory successfully" in {
    val dir = createTestSubmissionDir()

    dir should not be null
    Files.exists(dir) shouldBe true
    Files.isDirectory(dir) shouldBe true
  }

  it should "create directory with epoch-based name" in {
    val epoch = System.nanoTime()
    val dirName = s"submission_$epoch"
    val dir = PodTemplateUtils.createTemplateDirForSubmission(dirName)

    dir.getFileName.toString shouldBe dirName
    Files.exists(dir) shouldBe true
  }

  it should "write template file successfully" in {
    val templateDir = createTestSubmissionDir()
    val content = "{\"metadata\":{\"name\":\"test\"}}"
    val fileName = "driver-template.json"

    val result = PodTemplateUtils.writeTempFile(content, fileName, templateDir)

    result shouldBe defined
    val path = result.get
    Files.exists(path) shouldBe true
    Files.readString(path) shouldBe content
  }

  it should "return None for empty content" in {
    val templateDir = createTestSubmissionDir()

    val result1 = PodTemplateUtils.writeTempFile("", "template.json", templateDir)
    val result2 = PodTemplateUtils.writeTempFile(null, "template.json", templateDir)

    result1 shouldBe None
    result2 shouldBe None
  }

  it should "delete template directory successfully" in {
    val templateDir = createTestSubmissionDir()
    val file = templateDir.resolve("test.txt")
    Files.writeString(file, "test content")

    Files.exists(templateDir) shouldBe true
    PodTemplateUtils.deleteTempDir(templateDir)
    Files.exists(templateDir) shouldBe false
  }

  it should "handle deletion of non-existent directory gracefully" in {
    val nonExistentDir = tempTestDir.resolve("non-existent")

    noException should be thrownBy PodTemplateUtils.deleteTempDir(nonExistentDir)
  }

  it should "throw exception when cannot create template directory" in {
    val tempFile = Files.createTempFile("test", ".tmp")
    try {
      System.setProperty(SparkConstants.JavaIoTmpDir, tempFile.toString)

      val dirName = s"submission_${System.nanoTime()}"
      val exception = intercept[SparkSubmitException] {
        PodTemplateUtils.createTemplateDirForSubmission(dirName)
      }
      exception.errorCode shouldBe ErrorCode.InternalError
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  it should "write multiple files to same directory" in {
    val templateDir = createTestSubmissionDir()

    val file1 = PodTemplateUtils.writeTempFile("content1", "file1.json", templateDir)
    val file2 = PodTemplateUtils.writeTempFile("content2", "file2.json", templateDir)

    file1 shouldBe defined
    file2 shouldBe defined
    Files.readString(file1.get) shouldBe "content1"
    Files.readString(file2.get) shouldBe "content2"
  }

  it should "handle writeTempFile with IOException gracefully" in {
    createTestSubmissionDir()

    val invalidDir = tempTestDir.resolve("non-existent-parent")
    val result = PodTemplateUtils.writeTempFile("content", "file.json", invalidDir)

    result shouldBe None
  }


  it should "delete directory with nested files" in {
    val templateDir = createTestSubmissionDir()

    val subDir = templateDir.resolve("subdir")
    Files.createDirectories(subDir)
    val file1 = templateDir.resolve("file1.txt")
    val file2 = subDir.resolve("file2.txt")
    Files.writeString(file1, "content1")
    Files.writeString(file2, "content2")

    Files.exists(templateDir) shouldBe true
    PodTemplateUtils.deleteTempDir(templateDir)
    Files.exists(templateDir) shouldBe false
  }

  it should "cleanup old template directories on startup" in {
    // Create base directory with some old subdirectories
    val baseDir = tempTestDir.resolve(SparkConstants.TempDirectory)
    Files.createDirectories(baseDir)

    // Create some template directories
    val dir1 = baseDir.resolve("submission_1738898765432")
    val dir2 = baseDir.resolve("submission_1738985165432")
    Files.createDirectories(dir1)
    Files.createDirectories(dir2)
    Files.writeString(dir1.resolve("file.txt"), "content")
    Files.writeString(dir2.resolve("file.txt"), "content")

    Files.exists(dir1) shouldBe true
    Files.exists(dir2) shouldBe true

    // Call cleanup - should delete entire base directory and recreate
    PodTemplateUtils.cleanupOldTemplateDirs()

    // Base directory should exist (recreated)
    Files.exists(baseDir) shouldBe true
    // But old directories should be gone
    Files.exists(dir1) shouldBe false
    Files.exists(dir2) shouldBe false
  }

  it should "handle cleanup when base directory doesn't exist" in {
    val baseDir = tempTestDir.resolve(SparkConstants.TempDirectory)

    Files.exists(baseDir) shouldBe false

    // Should create the directory without errors
    noException should be thrownBy PodTemplateUtils.cleanupOldTemplateDirs()

    Files.exists(baseDir) shouldBe true
    Files.isDirectory(baseDir) shouldBe true
  }

  it should "handle cleanup when base directory is empty" in {
    val baseDir = tempTestDir.resolve(SparkConstants.TempDirectory)
    Files.createDirectories(baseDir)

    Files.exists(baseDir) shouldBe true
    // Directory is empty, no subdirectories

    // Should handle gracefully
    noException should be thrownBy PodTemplateUtils.cleanupOldTemplateDirs()

    Files.exists(baseDir) shouldBe true
  }

  it should "write file with long content" in {
    val templateDir = createTestSubmissionDir()

    val longContent = "x" * 10000
    val result = PodTemplateUtils.writeTempFile(longContent, "large-file.json", templateDir)

    result shouldBe defined
    Files.readString(result.get).length shouldBe 10000
  }

  it should "write file with unicode characters" in {
    val templateDir = createTestSubmissionDir()

    val unicodeContent = "{\"name\":\"测试\",\"value\":\"🎉\"}"
    val result = PodTemplateUtils.writeTempFile(unicodeContent, "unicode.json", templateDir)

    result shouldBe defined
    Files.readString(result.get) shouldBe unicodeContent
  }

  it should "handle individual file deletion failures gracefully" in {
    val templateDir = createTestSubmissionDir()

    // Create a file
    val file = templateDir.resolve("file.txt")
    Files.writeString(file, "content")

    // Make file read-only (deletion might fail on some systems)
    val javaFile = file.toFile
    javaFile.setWritable(false)
    javaFile.setReadable(true)

    // deleteTempDir should handle individual file deletion failures gracefully
    // and log warnings without throwing exceptions
    noException should be thrownBy PodTemplateUtils.deleteTempDir(templateDir)

    // Clean up: restore permissions and delete manually if needed
    try {
      javaFile.setWritable(true)
      Files.deleteIfExists(file)
      Files.deleteIfExists(templateDir)
    } catch {
      case _: Exception => // Ignore cleanup errors
    }
  }

  it should "handle ensureBaseDirExists failure during cleanup" in {
    // Create a regular file where the base directory should be
    val baseDir = tempTestDir.resolve(SparkConstants.TempDirectory)
    val filePath = tempTestDir.resolve(SparkConstants.TempDirectory)

    // Create parent structure where baseDir creation will fail
    // Set java.io.tmpdir to a file instead of directory to trigger failure
    val blockingFile = Files.createTempFile(tempTestDir, "blocking", ".tmp")

    try {
      System.setProperty(SparkConstants.JavaIoTmpDir, blockingFile.toString)

      // cleanupOldTemplateDirs calls ensureBaseDirExists, which should handle failure gracefully
      // The recover block should catch the exception and log warning
      noException should be thrownBy PodTemplateUtils.cleanupOldTemplateDirs()

    } finally {
      Files.deleteIfExists(blockingFile)
      System.setProperty(SparkConstants.JavaIoTmpDir, tempTestDir.toString)
    }
  }

  it should "handle deleteTempDir failure with corrupted directory" in {
    val templateDir = createTestSubmissionDir()

    // Create a symbolic link to a non-existent target to potentially cause issues during walk
    val linkPath = templateDir.resolve("broken-link")
    val nonExistentTarget = tempTestDir.resolve("non-existent-target")

    try {
      // Create a file, then try to delete parent while simulating filesystem issues
      Files.writeString(templateDir.resolve("file.txt"), "content")

      // On some systems, walking a directory with broken symlinks or permission issues
      // can throw exceptions. We test that deleteTempDir handles this gracefully.
      // Create a symlink if supported
      if (System.getProperty("os.name").toLowerCase.contains("win")) {
        // Windows may not support symlinks easily, skip symlink creation
        Files.writeString(linkPath, "fake-link-content")
      } else {
        try {
          Files.createSymbolicLink(linkPath, nonExistentTarget)
        } catch {
          case _: UnsupportedOperationException =>
            // Symlinks not supported, just create a regular file
            Files.writeString(linkPath, "content")
        }
      }

      // deleteTempDir should handle any walking/deletion exceptions gracefully
      noException should be thrownBy PodTemplateUtils.deleteTempDir(templateDir)

    } finally {
      // Cleanup
      try {
        Files.deleteIfExists(linkPath)
        Files.deleteIfExists(templateDir.resolve("file.txt"))
        Files.deleteIfExists(templateDir)
      } catch {
        case _: Exception => // Ignore cleanup errors
      }
    }
  }

  it should "handle cleanupOldTemplateDirs with filesystem error" in {
    // Create base directory structure
    val baseDir = tempTestDir.resolve(SparkConstants.TempDirectory)
    Files.createDirectories(baseDir)

    // Create a subdirectory that we'll make problematic
    val problematicDir = baseDir.resolve("problematic")
    Files.createDirectories(problematicDir)

    // Make the directory read-only to potentially cause deletion issues
    val javaDir = problematicDir.toFile
    javaDir.setWritable(false)
    javaDir.setReadable(true)

    try {
      // cleanupOldTemplateDirs should handle deletion failures gracefully
      // The outer recover block should catch any exception and log warning
      noException should be thrownBy PodTemplateUtils.cleanupOldTemplateDirs()

    } finally {
      // Restore permissions and cleanup
      javaDir.setWritable(true)
      try {
        Files.deleteIfExists(problematicDir)
        if (Files.exists(baseDir)) {
          Using.resource(Files.walk(baseDir)) { stream =>
            stream.sorted(java.util.Comparator.reverseOrder())
              .forEach(Files.delete)
          }
        }
      } catch {
        case _: Exception => // Ignore cleanup errors
      }
    }
  }
}
