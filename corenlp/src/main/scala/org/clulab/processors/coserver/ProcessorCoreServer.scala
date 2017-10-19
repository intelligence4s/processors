package org.clulab.processors.coserver

import com.typesafe.config.{ Config, ConfigValueFactory, ConfigFactory }
import com.typesafe.scalalogging.LazyLogging

import akka.actor._
import akka.actor.SupervisorStrategy._
import akka.routing._
import akka.event.Logging

import org.clulab.processors._
import org.clulab.processors.bionlp._
import org.clulab.processors.corenlp._
import org.clulab.processors.fastnlp._
import org.clulab.processors.shallownlp._

/**
  * Application to wrap and serve various Processors capabilities.
  *   Written by: Tom Hicks. 6/5/2017.
  *   Last Modified: Expose actor system for test termination. Add shutdown method.
  */
object ProcessorCoreServer extends LazyLogging {

  // THE instance of the the processor core server
  private var _pcs: ProcessorCoreServer = _

  /** Create a single instance of the processor core server, only if it has not been created. */
  def instance: ProcessorCoreServer = {
    logger.debug(s"(ProcessorCoreServer.instance): pcs = ${_pcs}")
    if (_pcs == null) {                     // create server, iff not already created
      val config = ConfigFactory.load().getConfig("ProcessorCoreServer")
      if (config == null)
        throw new RuntimeException("(ProcessorCoreServer.instance): Unable to read configuration from configuration file.")
      logger.debug(s"(ProcessorCoreServer.instance): config=${config}")
      _pcs = new ProcessorCoreServer(config)
    }
    logger.debug(s"(ProcessorCoreServer.instance): pcs => ${_pcs}")
    _pcs
  }

  /** Return an actor ref to the current instance of the router. */
  def router: ActorRef = instance.router

  def main (args: Array[String]) {
    val _ = instance
  }
}


class ProcessorCoreServer (

  /** Application-specific portion of the configuration file. */
  val config: Config

) extends LazyLogging {

  if (config == null)
    throw new RuntimeException("(ProcessorCoreServer.ctor): Empty configuration argument not allowed.")

  // create the Processor engine specified by the configuration and used by this server
  val processor: Processor = {

    // read all possible arguments for the various processors
    val prefix = "server.processor"
    val internStrings = getArgBoolean(s"${prefix}.internStrings", true)
    val maxSentenceLength = getArgInt(s"${prefix}.maxSentenceLength", 100)
    val removeFigTabReferences = getArgBoolean(s"${prefix}.removeFigTabReferences", true)
    val removeBibReferences = getArgBoolean(s"${prefix}.removeBibReferences", true)
    val withChunks = getArgBoolean(s"${prefix}.withChunks", true)
    val withContext = getArgBoolean(s"${prefix}.withContext", true)
    val withCRFNER = getArgBoolean(s"${prefix}.withCRFNER", true)
    val withRuleNER = getArgBoolean(s"${prefix}.withRuleNER", true)
    val withDiscourse = {
      getArgString(s"${prefix}.withDiscourse", "NO_DISCOURSE") match {
        case "WITH_DISCOURSE" => ShallowNLPProcessor.WITH_DISCOURSE
        case "JUST_EDUS" => ShallowNLPProcessor.JUST_EDUS
        case _ => ShallowNLPProcessor.NO_DISCOURSE
      }
    }

    // select the processor to use
    val proc = if (config.hasPath(s"${prefix}.type")) config.getString(s"${prefix}.type") else "core"

    proc.toLowerCase match {                // return instantiated processor
      case "bio" => new BioNLPProcessor(internStrings,
                                        withChunks,
                                        withCRFNER,
                                        withRuleNER,
                                        withContext,
                                        withDiscourse,
                                        maxSentenceLength,
                                        removeFigTabReferences,
                                        removeBibReferences)

      case "core" => new CoreNLPProcessor(internStrings, withChunks, withDiscourse, maxSentenceLength)

      case "fast" => new FastNLPProcessor(internStrings, withChunks, withDiscourse)

      case "fastbio" => new FastBioNLPProcessor(internStrings,
                                                withChunks,
                                                withCRFNER,
                                                withRuleNER,
                                                withContext,
                                                withDiscourse,
                                                maxSentenceLength,
                                                removeFigTabReferences,
                                                removeBibReferences)

      case _ => new ShallowNLPProcessor(internStrings, withChunks)
    }
  }

  logger.debug(s"(ProcessorCoreServer.ctor): processor=${processor}")

  // fire up the actor system
  val system = ActorSystem("procCoreServer", config)

  logger.debug(s"(ProcessorCoreServer.ctor): system=${system}")

  // create supervisory strategy for the router to handle errors
  private final val restartEachStrategy: SupervisorStrategy =
    OneForOneStrategy() { case _ => Restart }

  // create a router to a pool of processor actors waiting for work
  private val procPool: ActorRef = system.actorOf(
    ProcessorActor.props(processor).withRouter(
      FromConfig.withSupervisorStrategy(restartEachStrategy)),
    "procActorPool")

  logger.debug(s"(ProcessorCoreServer.ctor): procPool=${procPool}")

  /** Returns an actor ref to the internal instance of the pooled router. */
  val router: ActorRef = procPool


  /** Shutdown the server: kill the routers actor children followed by the router itself. */
  def shutdown: Unit = {
    router ! Broadcast(PoisonPill)
    router ! PoisonPill
  }

  private def getArgBoolean (argPath: String, defaultValue: Boolean): Boolean =
    if (config.hasPath(argPath)) config.getBoolean(argPath)
    else defaultValue

  private def getArgInt (argPath: String, defaultValue: Int): Int =
    if (config.hasPath(argPath)) config.getInt(argPath)
    else defaultValue

  private def getArgString (argPath: String, defaultValue: String): String =
    if (config.hasPath(argPath)) config.getString(argPath)
    else defaultValue
}
