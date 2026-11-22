package io.spark.k8s.submit.server

import jakarta.servlet.{Filter, FilterChain, ServletRequest, ServletResponse}
import jakarta.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.sparkproject.jetty.server.{Server, ServerConnector}

import java.net.{HttpURLConnection, URL}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.io.Source

/** Tests the SparkServletContextFactory of the module being built. */
class SparkServletContextFactoryTest extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private implicit val ec: ExecutionContext = ExecutionContext.global
  private var server: Server = _

  override def afterEach(): Unit = if (server != null && !server.isStopped) server.stop()

  private def start(contextPath: String, servlets: Seq[(String, HttpServlet)], filters: Seq[Filter] = Nil): Int = {
    server = new Server()
    val connector = new ServerConnector(server)
    connector.setHost("127.0.0.1")
    connector.setPort(0)
    server.addConnector(connector)
    server.setHandler(SparkServletContextFactory.create(contextPath, servlets, filters))
    server.setStopTimeout(10000)
    server.start()
    connector.getLocalPort
  }

  private def get(port: Int, path: String): (Int, String) = {
    val conn = new URL(s"http://127.0.0.1:$port$path").openConnection().asInstanceOf[HttpURLConnection]
    conn.setConnectTimeout(5000)
    conn.setReadTimeout(15000)
    try {
      val status = conn.getResponseCode
      val stream = if (status < 400) conn.getInputStream else conn.getErrorStream
      val body = if (stream == null) "" else try Source.fromInputStream(stream).mkString finally stream.close()
      (status, body)
    } finally conn.disconnect()
  }

  private def textServlet(body: String): HttpServlet = new HttpServlet {
    override def doGet(req: HttpServletRequest, resp: HttpServletResponse): Unit = resp.getWriter.write(body)
  }

  "SparkServletContextFactory" should "serve each servlet at its path under the context path" in {
    val port = start("/api", Seq("/a" -> textServlet("A"), "/b" -> textServlet("B")))

    get(port, "/api/a") shouldBe ((200, "A"))
    get(port, "/api/b") shouldBe ((200, "B"))
    get(port, "/api/c")._1 shouldBe 404
    get(port, "/a")._1 shouldBe 404
  }

  it should "run the filters in order before the servlet" in {
    val order = new StringBuffer()
    def recording(name: String): Filter = new Filter {
      override def doFilter(req: ServletRequest, resp: ServletResponse, chain: FilterChain): Unit = {
        order.append(name); chain.doFilter(req, resp)
      }
    }
    val servlet = new HttpServlet {
      override def doGet(req: HttpServletRequest, resp: HttpServletResponse): Unit = order.append("servlet")
    }
    val port = start("/", Seq("/x" -> servlet), Seq(recording("first,"), recording("second,")))

    get(port, "/x")._1 shouldBe 200
    order.toString shouldBe "first,second,servlet"
  }

  it should "wait for in-flight requests when the server stops" in {
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val slow = new HttpServlet {
      override def doGet(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
        entered.countDown()
        release.await(10, TimeUnit.SECONDS)
        resp.getWriter.write("done")
      }
    }
    val port = start("/", Seq("/slow" -> slow))

    val response = Future(get(port, "/slow"))
    entered.await(5, TimeUnit.SECONDS) shouldBe true

    val stopped = Future(server.stop())
    Thread.sleep(500)
    stopped.isCompleted shouldBe false

    release.countDown()
    Await.result(response, 15.seconds) shouldBe ((200, "done"))
    Await.result(stopped, 15.seconds)
    server.isStopped shouldBe true
  }
}
