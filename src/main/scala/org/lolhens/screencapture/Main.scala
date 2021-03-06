package org.lolhens.screencapture

import java.awt.{GraphicsDevice, GraphicsEnvironment}
import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import fastparse.all._
import org.lolhens.screencapture.ParserUtils._
import swave.core.StreamEnv

import scala.language.postfixOps
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
  * Created by pierr on 23.11.2016.
  */
object Main {
  def main(args: Array[String]): Unit = {
    val options = Options.parse(args.mkString(" ")) match {
      case Success(options) =>
        options

      case Failure(NonFatal(exception)) =>
        exception.printStackTrace(System.out)

        println(
          """
            |Options:
            |  -h [host]           Activates the client-mode and specifies the host
            |  -p [port]           Overrides the default port (51234)
            |  -f                  Activates fullscreen-mode
            |  -m [monitor]        Overrides the default monitor
            |  -t [timeout]        Timeout in seconds
            |  -par [parallelism]  Parallelism (numProcessors / 2)
            |  -l [latency]        Override the default maximum latency (800ms)
            |  -fps [rate]         Overrides the default framerate (20fps)
            |  -log                Turn logging on
            |  -tree               Shows a tree""".stripMargin)

        System.exit(0)
        throw new IllegalStateException()
    }

    if (options.tree)
      Test.main(new Array(0))
    else
      start(options)
  }

  def start(options: Options) = {
    implicit val streamEnv = StreamEnv()
    implicit val actorSystem = ActorSystem()
    implicit val materializer = ActorMaterializer()

    options.host match {
      case Some(host) =>
        CaptureSender(
          selectScreen(options.monitor),
          new InetSocketAddress(host, options.port),
          parallelism = options.parallelism,
          fps = options.fps,
          maxLatency = options.latency
        )

      case None =>
        CaptureReceiver(
          selectScreen(options.monitor),
          options.fullscreen,
          new InetSocketAddress("0.0.0.0", options.port),
          timeout = options.timeout
        )
    }
  }

  def selectScreen(index: Int): GraphicsDevice = {
    val graphicsEnv = GraphicsEnvironment.getLocalGraphicsEnvironment
    if (index == -1)
      graphicsEnv.getDefaultScreenDevice
    else {
      val screens = graphicsEnv.getScreenDevices
      screens(index)
    }
  }

  case class Options(host: Option[String],
                     port: Int,
                     fullscreen: Boolean,
                     monitor: Int,
                     fps: Double,
                     timeout: Int,
                     logging: Boolean,
                     parallelism: Int,
                     latency: Int,
                     tree: Boolean) {
    ImageGrabber.logging = logging
    TcpStream.logging = logging
  }

  object Options {
    private def stringOption(name: String) = P(name ~ s1 ~ text)

    private def intParser(name: String) = P(name ~ s1 ~ number.map(_.toIntExact))

    private def doubleParser(name: String) = P(name ~ s1 ~ number.map(_.toDouble))

    private def booleanOption(name: String) = P(name.!.map(_ => true))

    private val hostParser = stringOption("-h").map(Some.apply)
    private val portParser = intParser("-p")
    private val fullscreenParser = booleanOption("-f")
    private val monitorParser = intParser("-m")
    private val framerateParser = doubleParser("-fps")
    private val timeoutParser = intParser("-t")
    private val loggingParser = booleanOption("-log")
    private val parallelismParser = intParser("-par")
    private val latencyParser = intParser("-l")
    private val treeParser = booleanOption("-tree")

    private val parser =
      any(Seq(
        hostParser,
        portParser,
        monitorParser,
        framerateParser,
        fullscreenParser,
        loggingParser,
        timeoutParser,
        parallelismParser,
        latencyParser,
        treeParser
      ).map(e => e -> e).toMap)
        .rep(sep = s1)
        .map(_.toMap)
        .map { values =>
          Options(
            host = values.collectFirst { case (`hostParser`, host: Option[String@unchecked]) => host }.getOrElse(None),
            port = values.collectFirst { case (`portParser`, port: Int) => port }.getOrElse(51234),
            fullscreen = values.collectFirst { case (`fullscreenParser`, fullscreen: Boolean) => fullscreen }.getOrElse(false),
            monitor = values.collectFirst { case (`monitorParser`, monitor: Int) => monitor }.getOrElse(-1),
            fps = values.collectFirst { case (`framerateParser`, fps: Double) => fps }.getOrElse(20),
            timeout = values.collectFirst { case (`timeoutParser`, timeout: Int) => timeout }.getOrElse(3),
            logging = values.collectFirst { case (`loggingParser`, logging: Boolean) => logging }.getOrElse(false),
            parallelism = values.collectFirst { case (`parallelismParser`, parallelism: Int) => parallelism }
              .getOrElse {
                Runtime.getRuntime.availableProcessors() / 2
              },
            latency = values.collectFirst { case (`latencyParser`, latency: Int) => latency }.getOrElse(800),
            tree = values.collectFirst { case (`treeParser`, tree: Boolean) => tree }.getOrElse(false)
          )
        }

    def parse(string: String): Try[Options] = {
      (Start ~ s ~ parser ~ s ~ End)
        .parse(string)
        .tried
    }
  }

}
