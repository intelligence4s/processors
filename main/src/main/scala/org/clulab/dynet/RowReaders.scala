/**
 * Task-specific readers for the Row class produced by ColumnReader
 * @author Mihai
 */

package org.clulab.dynet

import org.clulab.sequences.Row

import scala.collection.mutable.ArrayBuffer
import MetalRowReader._

import scala.util.Random
import org.clulab.utils.MathUtils

import scala.collection.mutable

case class AnnotatedSentence(words: IndexedSeq[String],
                             posTags: Option[IndexedSeq[String]] = None,
                             neTags: Option[IndexedSeq[String]] = None) {
  def indices: Range = words.indices
  def size: Int = words.size
}

trait RowReader {
  /** Converts the tabular format into one or more (AnnotatedSentence, sequence of gold heads (optional), sequence of gold labels) pairs */
  def toAnnotatedSentences(rows: IndexedSeq[Row], insertNegatives: Int = 0): IndexedSeq[(AnnotatedSentence, IndexedSeq[Label])]
}

class MetalRowReader extends RowReader {
  override def toAnnotatedSentences(rows: IndexedSeq[Row], insertNegatives: Int = 0): IndexedSeq[(AnnotatedSentence, IndexedSeq[Label])] = {

    if (rows.head.length == 2) {
      parseSimple(rows)
    } else if (rows.head.length == 4) {
      parseSimpleExtended(rows)
    } else if (rows.head.length >= 5) {
      parseFull(rows, insertNegatives)
    } else {
      throw new RuntimeException("ERROR: the Metal format expects 2, 4, or 5+ columns!")
    }
  }

  /** Parser for the simple format: word, label */
  def parseSimple(rows: IndexedSeq[Row]): IndexedSeq[(AnnotatedSentence, IndexedSeq[Label])] = {

    assert(rows.head.length == 2)
    val words = new ArrayBuffer[String]()
    val labels = new ArrayBuffer[Label]()

    for (row <- rows) {
      words += row.get(WORD_POSITION)
      labels += PrimalLabel(row.get(WORD_POSITION + 1))
    }

    IndexedSeq(Tuple2(AnnotatedSentence(words), labels))
  }

  /** Parser for the simple extended format: word, POS tag, NE label, label */
  def parseSimpleExtended(rows: IndexedSeq[Row]): IndexedSeq[(AnnotatedSentence, IndexedSeq[Label])] = {

    assert(rows.head.length == 4)
    val words = new ArrayBuffer[String]()
    val posTags = new ArrayBuffer[String]()
    val neLabels = new ArrayBuffer[String]()
    val labels = new ArrayBuffer[Label]()

    for (row <- rows) {
      words += row.get(WORD_POSITION)
      posTags += row.get(POS_TAG_POSITION)
      neLabels += row.get(NE_LABEL_POSITION)
      labels += PrimalLabel(row.get(LABEL_START_OFFSET))
    }

    IndexedSeq(Tuple2(AnnotatedSentence(words, Some(posTags), Some(neLabels)), labels))
  }

  /** Parser for the full format: word, POS tag, NE label, (label head)+ */
  def parseFull(rows: IndexedSeq[Row], insertNegatives: Int): IndexedSeq[(AnnotatedSentence, IndexedSeq[Label])] = {

    assert(rows.head.length >= 5)
    val numSent = (rows.head.length - 3) / 2
    assert(numSent >= 1)

    val words = new ArrayBuffer[String]()
    val posTags = new ArrayBuffer[String]()
    val neLabels = new ArrayBuffer[String]()
    val headPositions = new Array[ArrayBuffer[Int]](numSent)
    for(i <- headPositions.indices) headPositions(i) = new ArrayBuffer[Int]()
    val labels = new Array[ArrayBuffer[String]](numSent)
    for(i <- labels.indices) labels(i) = new ArrayBuffer[String]()

    for(row <- rows) {
      words += row.get(WORD_POSITION)
      posTags += row.get(POS_TAG_POSITION)
      neLabels += row.get(NE_LABEL_POSITION)

      for (j <- 0 until numSent) {
        labels(j) += row.get(LABEL_START_OFFSET + (j * 2))
        headPositions(j) += {
          try {
            row.get(LABEL_START_OFFSET + (j * 2) + 1).toInt
          } catch {
            case _: NumberFormatException => -1
          }
        }
      }
    }

    val sentences = new ArrayBuffer[(AnnotatedSentence, IndexedSeq[Label])]()
    for(i <- 0 until numSent) {
      val annotatedSent = AnnotatedSentence(
        words,
        Some(posTags),
        Some(neLabels)
      )
      val labelsForThisSentence = labels(i)
      val headsForThisSentence = headPositions(i)
      val sentLabels = new ArrayBuffer[Label]()
      for(j <- labelsForThisSentence.indices) {
        sentLabels += DualLabel(j, headsForThisSentence(j), labelsForThisSentence(j))

        if(insertNegatives > 0) {
          val negHeads = mkRandoms(-1 until annotatedSent.size, Set(headsForThisSentence(j)), insertNegatives)
          for(negHead <- negHeads) {
            sentLabels += DualLabel(j, negHead, Utils.STOP_TAG)
          }
        }
      }
      sentences += Tuple2(annotatedSent, sentLabels)
    }

    sentences
  }
}

object MetalRowReader {
  val WORD_POSITION = 0
  val POS_TAG_POSITION = 1
  val NE_LABEL_POSITION = 2
  val LABEL_START_OFFSET = 3

  val rand = new Random(1)

  private def mkRandoms(range: Range, exclude: Set[Int], howMany:Int): Set[Int] = {
    val numbers = MathUtils.randomize(range.toArray, rand)
    val randoms = new mutable.HashSet[Int]()
    for(n <- numbers if randoms.size < howMany) {
      if(! exclude.contains(n)) {
        randoms += n
      }
    }
    randoms.toSet
  }
}