package org.clulab.dynet
import java.io.PrintWriter

import edu.cmu.dynet.{Expression, ExpressionVector, LstmBuilder, ParameterCollection, RnnBuilder}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable.ArrayBuffer
import org.clulab.dynet.Utils._

/**
 * This layer applies a biLSTM over the sequence of Expressions produced by a previous layer
 * @author Mihai
 */
class RnnLayer (val parameters:ParameterCollection,
                val inputSize: Int,
                val numLayers: Int,
                val rnnStateSize: Int,
                val useHighwayConnections: Boolean,
                val wordFwRnnBuilder:RnnBuilder,
                val wordBwRnnBuilder:RnnBuilder,
                val dropoutProb: Float = RnnLayer.DROPOUT_PROB) extends IntermediateLayer with Saveable {

  override def mkEmbeddings(inputExpressions: ExpressionVector, doDropout: Boolean): ExpressionVector = {
    setRnnDropout(wordFwRnnBuilder, dropoutProb, doDropout)
    setRnnDropout(wordBwRnnBuilder, dropoutProb, doDropout)

    val fwEmbeddings = inputExpressions
    val fwStates = Utils.transduce(fwEmbeddings, wordFwRnnBuilder)
    val bwEmbeddings = fwEmbeddings.reverse
    val bwStates = Utils.transduce(bwEmbeddings, wordBwRnnBuilder).reverse
    assert(fwStates.length == bwStates.length)

    // the word state concatenates the fwd and bwd LSTM hidden states; and the input embedding if useHighwayConnections
    val states = new ArrayBuffer[Expression]()
    for(i <- fwStates.indices) {
      val state =
        if(useHighwayConnections) {
          Expression.concatenate(fwStates(i), bwStates(i), inputExpressions(i))
        } else {
          Expression.concatenate(fwStates(i), bwStates(i))
        }

      states += state
    }

    states
  }

  override def outDim: Int = {
    val highwaySize = if(useHighwayConnections) inputSize else 0
    2 * rnnStateSize + highwaySize
  }

  override def inDim: Int = inputSize

  override def saveX2i(printWriter: PrintWriter): Unit = {
    save(printWriter, inputSize, "inputSize")
    save(printWriter, numLayers, "numLayers")
    save(printWriter, rnnStateSize, "rnnStateSize")
    save(printWriter, if(useHighwayConnections) 1 else 0, "useHighwayConnections")
  }
}

object RnnLayer {
  val logger: Logger = LoggerFactory.getLogger(classOf[RnnLayer])

  val DROPOUT_PROB = 0.2f

  def load(parameters: ParameterCollection,
           x2iIterator: Iterator[String]): RnnLayer = {
    //
    // load the x2i info
    //
    val byLineIntBuilder = new ByLineIntBuilder()

    val inputSize = byLineIntBuilder.build(x2iIterator)
    val numLayers = byLineIntBuilder.build(x2iIterator)
    val rnnStateSize = byLineIntBuilder.build(x2iIterator)
    val useHighwayConnectionsAsInt = byLineIntBuilder.build(x2iIterator)
    val useHighwayConnections = useHighwayConnectionsAsInt == 1

    //
    // make the loadable parameters
    //
    val fwBuilder = new LstmBuilder(numLayers, inputSize, rnnStateSize, parameters)
    val bwBuilder = new LstmBuilder(numLayers, inputSize, rnnStateSize, parameters)

    new RnnLayer(parameters,
      inputSize, numLayers, rnnStateSize, useHighwayConnections,
      fwBuilder, bwBuilder)
  }
}
