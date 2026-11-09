package io.spark.k8s.submit.util

import io.spark.k8s.submit.api.ErrorCode
import io.spark.k8s.submit.{SparkConstants, SparkSubmitException}
import org.slf4j.{Logger, LoggerFactory}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._
import scala.util.{Try, Using}

/** Utilities for managing pod template temp files. Uses epoch timestamps for directory names. */
object PodTemplateUtils {

  private val log: Logger = LoggerFactory.getLogger(getClass)

  private def baseDir: Path =
    Path.of(System.getProperty(SparkConstants.JavaIoTmpDir)).resolve(SparkConstants.TempDirectory)

  /** Ensures base directory exists. Called once on startup. */
  private def ensureBaseDirExists(): Unit = {
    Try {
      if (!Files.exists(baseDir)) {
        Files.createDirectories(baseDir)
        log.info(s"Created base template directory: $baseDir")
      }
    }.recover {
      case e: Exception =>
        log.warn(s"Failed to ensure base directory exists: ${e.getMessage}")
    }
  }

  /** Creates a writable template directory for this submission: .../spark-submitter/{dirName}. */
  def createTemplateDirForSubmission(dirName: String): Path = {
    val dir = baseDir.resolve(dirName)

    Try {
      Files.createDirectories(dir)
      log.info(s"Created template directory: $dir")
      dir
    }.getOrElse(throw SparkSubmitException.of(ErrorCode.InternalError, "Cannot create template directory"))
  }

  /** Writes template content to {baseDir}/{fileName}. */
  def writeTempFile(content: String, fileName: String, baseDir: Path): Option[Path] =
    Option(content).filter(_.nonEmpty).flatMap { c =>
      val path = baseDir.resolve(fileName)
      Try {
        Files.writeString(path, c)
        log.debug(s"Wrote template: $path (${c.length} bytes)")
        path
      }.recover {
        case e: Exception =>
          log.warn(s"Failed to write template file: ${e.getMessage}")
          throw SparkSubmitException.of(ErrorCode.InternalError, s"Failed to write template file: ${e.getMessage}", e)
      }.toOption
    }

  /** Deletes the template directory and all files in it. */
  def deleteTempDir(dir: Path): Unit =
    Try {
      if (Files.exists(dir)) {
        Using.resource(Files.walk(dir)) { stream =>
          stream.iterator().asScala.toSeq.reverse.foreach { path =>
            Try(Files.deleteIfExists(path)).recover {
              case e: Exception =>
                log.warn(s"Failed to delete: $path - ${e.getMessage}")
            }
          }
        }
      }
    }.recover {
      case e: Exception =>
        log.warn(s"Failed to delete template directory: $dir - ${e.getMessage}")
    }

  /**
   * Cleans up all template directories on startup.
   * On startup, no jobs are running, so all template directories are orphaned.
   * Simpler than checking timestamps - just delete everything and recreate base dir.
   */
  def cleanupOldTemplateDirs(): Unit = {
    Try {
      if (Files.exists(baseDir)) {
        deleteTempDir(baseDir)
        log.info(s"Startup cleanup: deleted base template directory $baseDir")
      }
    }.recover {
      case e: Exception =>
        log.warn(s"Failed to cleanup template directories: ${e.getMessage}")
    }

    // Recreate base directory
    ensureBaseDirExists()
  }
}
