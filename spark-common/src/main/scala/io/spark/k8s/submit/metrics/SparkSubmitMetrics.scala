package io.spark.k8s.submit.metrics

import io.micrometer.core.instrument.{Counter, Gauge, MeterRegistry, Timer}
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.spark.k8s.submit.ServerConfig
import io.spark.k8s.submit.api.HttpStatus

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Micrometer metrics for Spark submit API. Exposed via /metrics for Prometheus collection.
 *
 * Metrics:
 * - spark_submit_request_success_count - successful submissions (2xx)
 * - spark_submit_request_failure_count - failed requests, tagged by failure_type (HTTP status)
 * - spark_submit_requests_in_flight - current requests being processed (gauge)
 * - spark_submit_request_latency_seconds - request latency with percentiles (p50, p90, p99) and histogram buckets
 * - spark_submit_request_latency_seconds_count - total request count
 */
class SparkSubmitMetrics {

  private val METRIC_PREFIX = "spark_submit_request_"
  private val METRIC_SUCCESS_COUNT: String = METRIC_PREFIX + "success_count"
  private val METRIC_FAILURE_COUNT: String = METRIC_PREFIX + "failure_count"
  private val METRIC_IN_FLIGHT: String = "spark_submit_requests_in_flight"
  private val METRIC_LATENCY: String = METRIC_PREFIX + "latency_seconds"
  private val TAG_FAILURE_TYPE: String = "failure_type"

  private val registry: MeterRegistry = new SimpleMeterRegistry()

  private val successCount: Counter = Counter.builder(METRIC_SUCCESS_COUNT)
    .description("Successful Spark job submissions")
    .register(registry)

  private val latencyTimer: Timer = Timer.builder(METRIC_LATENCY)
    .description("Spark submit request latency (count = total requests)")
    .publishPercentileHistogram()
    .publishPercentiles(ServerConfig.Metrics.percentiles: _*)
    .serviceLevelObjectives(ServerConfig.Metrics.sloMs.map(Duration.ofMillis): _*)
    .register(registry)

  private val inFlightCount = new AtomicInteger(0)

  Gauge.builder(METRIC_IN_FLIGHT, inFlightCount, (ai: AtomicInteger) => ai.get().toDouble)
    .description("Number of Spark submit requests currently being processed")
    .register(registry)

  /** For filter to create Timer.Sample. */
  def getRegistry: MeterRegistry = registry

  /** For filter to record latency via sample.stop(timer). */
  def getLatencyTimer: Timer = latencyTimer

  def recordRequestStart(): Unit = {
    inFlightCount.incrementAndGet()
  }

  def recordRequestComplete(status: Int): Unit = {
    inFlightCount.decrementAndGet()
    if (status >= HttpStatus.Ok && status < HttpStatus.BadRequest) {
      successCount.increment()
    } else {
      registry.counter(METRIC_FAILURE_COUNT, TAG_FAILURE_TYPE, status.toString).increment()
    }
  }
}
