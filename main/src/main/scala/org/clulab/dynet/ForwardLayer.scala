package org.clulab.dynet

import edu.cmu.dynet.{Dim, Expression, ExpressionVector, Parameter, ParameterCollection}
import org.slf4j.{Logger, LoggerFactory}
import ForwardLayer.{DIST_WIN_SIZE, _}
import org.clulab.dynet.Utils.{ByLineIntBuilder, fromIndexToString, mkTransitionMatrix}
import org.clulab.struct.Counter
import org.clulab.utils.Configured

abstract class ForwardLayer (val parameters:ParameterCollection,
                             val inputSize: Int,
                             val hasPredicate: Boolean,
                             val t2i: Map[String, Int],
                             val i2t: Array[String],
                             val H: Parameter,
                             val nonlinearity: Int,
                             val dropoutProb: Float = DEFAULT_DROPOUT_PROB)
  extends FinalLayer {

  def forward(inputExpressions: ExpressionVector,
              predicatePositionOpt: Option[Int],
              doDropout: Boolean): ExpressionVector = {
    val pH = Expression.parameter(H)
    val emissionScores = new ExpressionVector()

    if(predicatePositionOpt.isEmpty) {
      for (i <- inputExpressions.indices) {
        var argExp = inputExpressions(i)

        argExp = Utils.expressionDropout(argExp, dropoutProb, doDropout)

        var l1 = Utils.expressionDropout(pH * argExp, dropoutProb, doDropout)

        l1 = nonlinearity match {
          case NONLIN_TANH => Expression.tanh(l1)
          case NONLIN_RELU => Expression.rectify(l1)
          case _ => l1
        }

        emissionScores.add(l1)
      }
    }

    else {
      val predPosition = predicatePositionOpt.get
      for(i <- inputExpressions.indices) {
        val argExp = Utils.expressionDropout(inputExpressions(i), dropoutProb, doDropout)
        val predExp = Utils.expressionDropout(inputExpressions(predPosition), dropoutProb, doDropout)

        var dist = i - predPosition
        if (dist < - DIST_WIN_SIZE) dist = - DIST_WIN_SIZE - 1
        if(dist > DIST_WIN_SIZE) dist = DIST_WIN_SIZE + 1
        val posIndex = dist + DIST_WIN_SIZE + 1
        val distEmbed = Utils.expressionDropout(Expression.tanh(Expression.lookup(distanceLookupParameters, posIndex)), dropoutProb, doDropout)

        val predEmbed = Expression.input(if(i == predPosition) 1f else 0f)
        val adjLeft = Expression.input(if(i == predPosition - 1) 1f else 0f)
        val adjRight = Expression.input(if(i == predPosition + 1) 1f else 0f)
        //val distAsNum = Expression.input((i - predPosition).toFloat)

        /*
        val ctxExp = Utils.expressionDropout(
          mkContextExp(inputExpressions, i, predPosition),
          dropoutProb, doDropout)
        */

        // TODO: dropout before or after concatenate? - seems better before
        val ss = Expression.concatenate(argExp, predExp, distEmbed, predEmbed, adjLeft, adjRight)

        var l1 = Utils.expressionDropout(pH * ss, dropoutProb, doDropout)

        l1 = nonlinearity match {
          case NONLIN_TANH => Expression.tanh(l1)
          case NONLIN_RELU => Expression.rectify(l1)
          case _ => l1 // nothing to do otherwise
        }

        emissionScores.add(l1)
      }
    }

    emissionScores
  }

  override def inDim: Int = inputSize

  override def outDim: Int = t2i.size
}

object ForwardLayer {
  val logger: Logger = LoggerFactory.getLogger(classOf[ViterbiForwardLayer])

  val DEFAULT_DROPOUT_PROB = 0.2f

  val TYPE_VITERBI = 1
  val TYPE_GREEDY = 2

  val NONLIN_NONE = 0
  val NONLIN_RELU = 1
  val NONLIN_TANH = 2

  val TYPE_GREEDY_STRING = "greedy"
  val TYPE_VITERBI_STRING = "viterbi"

  val DEFAULT_HAS_PREDICATE = 0
  val DIST_EMBED_SIZE = 64
  val DIST_WIN_SIZE = 20

  def load(parameters: ParameterCollection,
           x2iIterator: BufferedIterator[String]): ForwardLayer = {
    val inferenceType = new ByLineIntBuilder().build(x2iIterator, "inferenceType")

    inferenceType match {
      case TYPE_VITERBI => ViterbiForwardLayer.load(parameters, x2iIterator)
      case TYPE_GREEDY => GreedyForwardLayer.load(parameters, x2iIterator)
      case _ => throw new RuntimeException(s"ERROR: unknown forward layer type $inferenceType!")
    }
  }

  def initialize(config: Configured,
                 paramPrefix: String,
                 parameters: ParameterCollection,
                 labelCounter: Counter[String],
                 hasPredicate: Boolean,
                 inputSize: Int): Option[ForwardLayer] = {
    if (!config.contains(paramPrefix)) {
      return None
    }

    val inferenceType = config.getArgString(paramPrefix + ".inference", Some("greedy"))
    val dropoutProb = config.getArgFloat(paramPrefix + ".dropoutProb", Some(ForwardLayer.DEFAULT_DROPOUT_PROB))

    val nonlinAsString = config.getArgString(paramPrefix + ".nonlinearity", Some(""))
    val nonlin = nonlinAsString match {
      case "relu" => NONLIN_RELU
      case "tanh" => NONLIN_TANH
      case "" => NONLIN_NONE
      case _ => throw new RuntimeException(s"ERROR: unknown non-linearity $nonlinAsString!")
    }

    val t2i = labelCounter.keySet.toList.sorted.zipWithIndex.toMap
    val i2t = fromIndexToString(t2i)

    val distanceEmbeddingSize = DIST_EMBED_SIZE
    val distanceWindowSize = DIST_WIN_SIZE
    val distanceLookupParameters =
      if(distanceEmbeddingSize > 0) {
        // Position embeddings [-distanceWindowSize, distanceWindowSize] + < distanceWindowSize + > distanceWindowSize. total = 43
        val distanceLookupParameters = parameters.addLookupParameters(distanceWindowSize * 2 + 3, Dim(distanceEmbeddingSize))

        Some(distanceLookupParameters)
      } else {
        None
      }

    val actualInputSize = if(hasPredicate) (2 * inputSize + DIST_EMBED_SIZE + 3) else inputSize
    val H = parameters.addParameters(Dim(t2i.size, actualInputSize))

    inferenceType match {
      case TYPE_GREEDY_STRING =>
        Some(new GreedyForwardLayer(parameters,
          inputSize, hasPredicate,
          t2i, i2t, distanceLookupParameters, H, nonlin, dropoutProb))
      case TYPE_VITERBI_STRING =>
        val T = mkTransitionMatrix(parameters, t2i)
        val layer = new ViterbiForwardLayer(parameters,
          inputSize, hasPredicate,
          t2i, i2t, H, T, nonlin, dropoutProb)
        layer.initializeTransitions()
        Some(layer)
      case _ =>
        new RuntimeException(s"ERROR: unknown inference type $inferenceType!")
        None
    }
  }
}