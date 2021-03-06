package nl.gideondk.sentinel.client

import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.actor.{ ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import akka.stream._
import akka.stream.scaladsl.{ BidiFlow, Broadcast, Flow, GraphDSL, Merge, RunnableGraph, Sink, Source }
import akka.util.ByteString
import com.typesafe.config.{ Config ⇒ TypesafeConfig }
import nl.gideondk.sentinel.Config
import nl.gideondk.sentinel.client.Client._
import nl.gideondk.sentinel.client.ClientStage.{ HostEvent, _ }
import nl.gideondk.sentinel.pipeline.{ Processor, Resolver }
import nl.gideondk.sentinel.protocol._

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.Try

class ClientConfig(config: TypesafeConfig) extends Extension {
  val connectionsPerHost = config.getInt("client.host.max-connections")
  val maxFailuresPerHost = config.getInt("client.host.max-failures")
  val failureRecoveryPeriod = Duration(config.getDuration("client.host.failure-recovery-duration").toNanos, TimeUnit.NANOSECONDS)

  val reconnectDuration = Duration(config.getDuration("client.host.reconnect-duration").toNanos, TimeUnit.NANOSECONDS)
  val shouldReconnect = config.getBoolean("client.host.auto-reconnect")

  val clientParallelism = config.getInt("client.parallelism")
  val inputBufferSize = config.getInt("client.input-buffer-size")
}

object ClientConfig extends ExtensionId[ClientConfig] with ExtensionIdProvider {
  override def lookup = ClientConfig
  override def createExtension(system: ExtendedActorSystem) =
    new ClientConfig(system.settings.config.getConfig("nl.gideondk.sentinel"))
  override def get(system: ActorSystem): ClientConfig = super.get(system)

  private def clientConfig(implicit system: ActorSystem) = apply(system)

  def connectionsPerHost(implicit system: ActorSystem) = clientConfig.connectionsPerHost
  def maxFailuresPerHost(implicit system: ActorSystem) = clientConfig.maxFailuresPerHost
  def failureRecoveryPeriod(implicit system: ActorSystem) = clientConfig.failureRecoveryPeriod

  def reconnectDuration(implicit system: ActorSystem) = clientConfig.reconnectDuration
  def shouldReconnect(implicit system: ActorSystem) = clientConfig.shouldReconnect

  def clientParallelism(implicit system: ActorSystem) = clientConfig.clientParallelism
  def inputBufferSize(implicit system: ActorSystem) = clientConfig.inputBufferSize
}

object Client {

  private def reconnectLogic[M](builder: GraphDSL.Builder[M], hostEventSource: Source[HostEvent, NotUsed]#Shape, hostEventIn: Inlet[HostEvent], hostEventOut: Outlet[HostEvent])(implicit system: ActorSystem) = {
    import GraphDSL.Implicits._
    implicit val b = builder

    val delay = ClientConfig.reconnectDuration
    val groupDelay = Flow[HostEvent].groupBy[Host](1024, { x: HostEvent ⇒ x.host }).delay(delay).map { x ⇒ system.log.warning(s"Reconnecting after ${delay.toSeconds}s for ${x.host}"); HostUp(x.host) }.mergeSubstreams

    if (ClientConfig.shouldReconnect) {
      val connectionMerge = builder.add(Merge[HostEvent](2))
      hostEventSource ~> connectionMerge ~> hostEventIn
      hostEventOut ~> b.add(groupDelay) ~> connectionMerge
    } else {
      hostEventSource ~> hostEventIn
      hostEventOut ~> Sink.ignore
    }
  }

  def apply[Cmd, Evt](hosts: Source[HostEvent, NotUsed], resolver: Resolver[Evt],
                      shouldReact: Boolean, inputOverflowStrategy: OverflowStrategy,
                      protocol: BidiFlow[Cmd, ByteString, ByteString, Evt, Any])(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext): Client[Cmd, Evt] = {
    val processor = Processor[Cmd, Evt](resolver, Config.producerParallelism)
    new Client(hosts, ClientConfig.connectionsPerHost, ClientConfig.maxFailuresPerHost, ClientConfig.failureRecoveryPeriod, ClientConfig.inputBufferSize, inputOverflowStrategy, processor, protocol.reversed)
  }

  def apply[Cmd, Evt](hosts: List[Host], resolver: Resolver[Evt],
                      shouldReact: Boolean, inputOverflowStrategy: OverflowStrategy,
                      protocol: BidiFlow[Cmd, ByteString, ByteString, Evt, Any])(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext): Client[Cmd, Evt] = {
    val processor = Processor[Cmd, Evt](resolver, Config.producerParallelism)
    new Client(Source(hosts.map(HostUp)), ClientConfig.connectionsPerHost, ClientConfig.maxFailuresPerHost, ClientConfig.failureRecoveryPeriod, ClientConfig.inputBufferSize, inputOverflowStrategy, processor, protocol.reversed)
  }

  def flow[Cmd, Evt](hosts: Source[HostEvent, NotUsed], resolver: Resolver[Evt],
                     shouldReact: Boolean = false, protocol: BidiFlow[Cmd, ByteString, ByteString, Evt, Any])(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext) = {
    val processor = Processor[Cmd, Evt](resolver, Config.producerParallelism)
    type Context = Promise[Event[Evt]]

    val eventHandler = Sink.foreach[(Try[Event[Evt]], Promise[Event[Evt]])] {
      case (evt, context) ⇒ context.complete(evt)
    }

    Flow.fromGraph(GraphDSL.create(hosts) { implicit b ⇒
      connections ⇒
        import GraphDSL.Implicits._

        val s = b.add(new ClientStage[Context, Cmd, Evt](ClientConfig.connectionsPerHost, ClientConfig.maxFailuresPerHost, ClientConfig.failureRecoveryPeriod, true, processor, protocol.reversed))

        reconnectLogic(b, connections, s.in2, s.out2)

        val input = b add Flow[Command[Cmd]].map(x ⇒ (x, Promise[Event[Evt]]()))
        val broadcast = b add Broadcast[(Command[Cmd], Promise[Event[Evt]])](2)

        val output = b add Flow[(Command[Cmd], Promise[Event[Evt]])].mapAsync(ClientConfig.clientParallelism)(_._2.future)

        s.out1 ~> eventHandler
        input ~> broadcast
        broadcast ~> output
        broadcast ~> s.in1

        FlowShape(input.in, output.out)
    })
  }

  def rawFlow[Context, Cmd, Evt](hosts: Source[HostEvent, NotUsed], resolver: Resolver[Evt],
                                 shouldReact: Boolean = false,
                                 protocol: BidiFlow[Cmd, ByteString, ByteString, Evt, Any])(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext) = {
    val processor = Processor[Cmd, Evt](resolver, Config.producerParallelism)

    Flow.fromGraph(GraphDSL.create(hosts) { implicit b ⇒
      connections ⇒

        val s = b.add(new ClientStage[Context, Cmd, Evt](ClientConfig.connectionsPerHost, ClientConfig.maxFailuresPerHost, ClientConfig.failureRecoveryPeriod, true, processor, protocol.reversed))

        reconnectLogic(b, connections, s.in2, s.out2)

        FlowShape(s.in1, s.out2)
    })
  }

  trait ClientException

  case class InputQueueClosed() extends Exception with ClientException

  case class InputQueueUnavailable() extends Exception with ClientException

  case class IncorrectEventType[A](event: A) extends Exception with ClientException

  case class EventException[A](cause: A) extends Throwable

}

class Client[Cmd, Evt](hosts: Source[HostEvent, NotUsed],
                       connectionsPerHost: Int, maximumFailuresPerHost: Int, recoveryPeriod: FiniteDuration,
                       inputBufferSize: Int, inputOverflowStrategy: OverflowStrategy,
                       processor: Processor[Cmd, Evt], protocol: BidiFlow[ByteString, Evt, Cmd, ByteString, Any])(implicit system: ActorSystem, mat: Materializer) {

  type Context = Promise[Event[Evt]]

  val eventHandler = Sink.foreach[(Try[Event[Evt]], Promise[Event[Evt]])] {
    case (evt, context) ⇒ context.complete(evt)
  }

  val g = RunnableGraph.fromGraph(GraphDSL.create(Source.queue[(Command[Cmd], Promise[Event[Evt]])](inputBufferSize, inputOverflowStrategy)) { implicit b ⇒
    source ⇒
      import GraphDSL.Implicits._

      val s = b.add(new ClientStage[Context, Cmd, Evt](connectionsPerHost, maximumFailuresPerHost, recoveryPeriod, true, processor, protocol))

      reconnectLogic(b, b.add(hosts), s.in2, s.out2)
      source.out ~> s.in1
      s.out1 ~> b.add(eventHandler)

      ClosedShape
  })

  val input = g.run()

  private def send(command: Command[Cmd])(implicit ec: ExecutionContext): Future[Event[Evt]] = {
    val context = Promise[Event[Evt]]()
    input.offer((command, context)).flatMap {
      case QueueOfferResult.Dropped         ⇒ Future.failed(InputQueueUnavailable())
      case QueueOfferResult.QueueClosed     ⇒ Future.failed(InputQueueClosed())
      case QueueOfferResult.Failure(reason) ⇒ Future.failed(reason)
      case QueueOfferResult.Enqueued        ⇒ context.future
    }
  }

  def ask(command: Cmd)(implicit ec: ExecutionContext): Future[Evt] = send(SingularCommand(command)) flatMap {
    case SingularEvent(x)      ⇒ Future(x)
    case SingularErrorEvent(x) ⇒ Future.failed(EventException(x))
    case x                     ⇒ Future.failed(IncorrectEventType(x))
  }

  def askStream(command: Cmd)(implicit ec: ExecutionContext): Future[Source[Evt, Any]] = send(SingularCommand(command)) flatMap {
    case StreamEvent(x)        ⇒ Future(x)
    case SingularErrorEvent(x) ⇒ Future.failed(EventException(x))
    case x                     ⇒ Future.failed(IncorrectEventType(x))
  }

  def sendStream(stream: Source[Cmd, Any])(implicit ec: ExecutionContext): Future[Evt] = send(StreamingCommand(stream)) flatMap {
    case SingularEvent(x)      ⇒ Future(x)
    case SingularErrorEvent(x) ⇒ Future.failed(EventException(x))
    case x                     ⇒ Future.failed(IncorrectEventType(x))
  }

  def sendStream(command: Cmd, stream: Source[Cmd, Any])(implicit ec: ExecutionContext): Future[Evt] = send(StreamingCommand(Source.single(command) ++ stream)) flatMap {
    case SingularEvent(x)      ⇒ Future(x)
    case SingularErrorEvent(x) ⇒ Future.failed(EventException(x))
    case x                     ⇒ Future.failed(IncorrectEventType(x))
  }

  def react(stream: Source[Cmd, Any])(implicit ec: ExecutionContext): Future[Source[Evt, Any]] = send(StreamingCommand(stream)) flatMap {
    case StreamEvent(x)        ⇒ Future(x)
    case SingularErrorEvent(x) ⇒ Future.failed(EventException(x))
    case x                     ⇒ Future.failed(IncorrectEventType(x))
  }
}
