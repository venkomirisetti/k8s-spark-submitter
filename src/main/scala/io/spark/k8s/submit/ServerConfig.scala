package io.spark.k8s.submit

import scala.util.Try

/**
 * Server configuration loaded from environment variables.
 * Fails fast at startup with a clear message if any value is invalid.
 */
object ServerConfig {

  object Server {
    val port: Int = envInt("PORT", 8080)
    val probePort: Int = envInt("PROBE_PORT", 8081)
    val address: String = sys.env.getOrElse("SERVER_ADDRESS", "0.0.0.0")
    val shutdownTimeoutMs: Long = envLong("SHUTDOWN_TIMEOUT_MS", 30000)
    val maxThreads: Int = envInt("SERVER_MAX_THREADS", 200)
    val minThreads: Int = envInt("SERVER_MIN_THREADS", 20)
    val threadIdleTimeoutMs: Long = envLong("SERVER_THREAD_IDLE_TIMEOUT_MS", 60000)
    val acceptQueueSize: Int = envInt("SERVER_ACCEPT_QUEUE_SIZE", 1000)
    val connectionIdleTimeoutMs: Long = envLong("SERVER_CONNECTION_IDLE_TIMEOUT_MS", 60000) // must exceed k8s total timeout to avoid premature close
  }

  object Tls {
    val enabled: Boolean = sys.env.get("TLS_ENABLED").exists(_.toBoolean)
    val certReloadEnabled: Boolean = sys.env.get("TLS_CERT_RELOAD_ENABLED").exists(_.toBoolean)

    val certPath: String = envRequired("TLS_CERT_PATH", enabled)
    val keyPath: String = envRequired("TLS_KEY_PATH", enabled)
    val caCertPath: Option[String] = sys.env.get("TLS_CA_CERT_PATH")
    val certCheckIntervalMs: Long = envLong("TLS_CERT_CHECK_INTERVAL_MS", 3600000)
    val certVerifyWithHash: Boolean = sys.env.get("TLS_CERT_VERIFY_WITH_HASH").exists(_.toBoolean)

    if (!enabled && caCertPath.isDefined)
      throw new IllegalArgumentException("TLS_CA_CERT_PATH requires TLS_ENABLED=true")
    if (!enabled && certReloadEnabled)
      throw new IllegalArgumentException("TLS_CERT_RELOAD_ENABLED=true requires TLS_ENABLED=true")
  }

  private def envRequired(key: String, required: Boolean): String =
    if (required) sys.env.getOrElse(key, throw new IllegalArgumentException(s"$key is required but not set"))
    else sys.env.getOrElse(key, "")

  // Concurrency aligned with Server.maxThreads (max parallel submissions)
  object K8sClient {
    val connectionTimeoutMs: Int = envInt("K8S_CLIENT_CONNECTION_TIMEOUT_MS", 10000)
    val requestTimeoutMs: Int = envInt("K8S_CLIENT_REQUEST_TIMEOUT_MS", 30000)
    val maxConcurrentRequests: Int = envInt("K8S_CLIENT_MAX_CONCURRENT_REQUESTS", 200)
    val maxConcurrentRequestsPerHost: Int = envInt("K8S_CLIENT_MAX_CONCURRENT_REQUESTS_PER_HOST", 200)
    val retryBackoffLimit: Int = envInt("K8S_CLIENT_REQUEST_RETRY_BACKOFF_LIMIT", 3)
    val retryBackoffIntervalMs: Int = envInt("K8S_CLIENT_REQUEST_RETRY_BACKOFF_INTERVAL_MS", 1000)
  }

  object Metrics {
    val percentiles: Array[Double] = envDoubles("METRICS_PERCENTILES", Array(0.5, 0.9, 0.99))
    val sloMs: Array[Long] = envLongs("METRICS_SLO_MS", Array(50, 100, 250, 500, 1000, 2000, 5000, 10000, 30000))
  }

  private def envInt(key: String, default: Int): Int =
    sys.env.get(key).fold(default) { v =>
      Try(v.toInt).getOrElse(throw new IllegalArgumentException(s"Invalid value for $key: '$v' (expected integer)"))
    }

  private def envLong(key: String, default: Long): Long =
    sys.env.get(key).fold(default) { v =>
      Try(v.toLong).getOrElse(throw new IllegalArgumentException(s"Invalid value for $key: '$v' (expected long)"))
    }

  private def envDoubles(key: String, default: Array[Double]): Array[Double] =
    sys.env.get(key).fold(default) { v =>
      Try(v.split(",").map(_.trim.toDouble))
        .getOrElse(throw new IllegalArgumentException(s"Invalid value for $key: '$v' (expected comma-separated doubles, e.g. 0.5,0.9,0.99)"))
    }

  private def envLongs(key: String, default: Array[Long]): Array[Long] =
    sys.env.get(key).fold(default) { v =>
      Try(v.split(",").map(_.trim.toLong))
        .getOrElse(throw new IllegalArgumentException(s"Invalid value for $key: '$v' (expected comma-separated longs, e.g. 50,100,500)"))
    }
}
