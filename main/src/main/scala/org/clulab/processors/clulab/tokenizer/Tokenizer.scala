package org.clulab.processors.clulab.tokenizer

import java.io.{BufferedReader, InputStreamReader}
import org.clulab.processors.Sentence

import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import Tokenizer._

import scala.util.matching.Regex

/** English open domain tokenizer */
class OpenDomainEnglishTokenizer extends Tokenizer(
  lexer = new OpenDomainEnglishLexer,
  abbreviations = IS_ENGLISH_ABBREVIATION,
  sentStarts = IS_ENGLISH_SENTSTART)

/**
  * Generic tokenizer
  * Author: mihais
  * Date: 3/15/17
  */
class Tokenizer(lexer:TokenizerLexer, abbreviations:Regex, sentStarts:Regex) {
  /** Tokenization and sentence splitting */
  def tokenize(text:String):Array[Sentence] = {
    val tokens = lexer.mkLexer(text)
    var done = false

    val rawTokens = new ArrayBuffer[RawToken]()

    // raw tokenization, using the antlr grammar
    while(! done) {
      val t = tokens.LT(1)
      if(t.getType == -1) {
        // EOF
        done = true
      } else {
        // info on the current token
        val word = t.getText
        val startOffset = t.getStartIndex
        val endOffset = t.getStopIndex + 1 // antlr is inclusive, we are exclusive

        // add to raw stream
        rawTokens ++= normalizeToken(RawToken(word, startOffset, endOffset))

        // advance to next token in stream
        tokens.consume()
      }
    }

    // sentence splitting, including detection of abbreviations
    sentenceSplitting(rawTokens.toArray)
  }

  /** Local normalization of a given token */
  def normalizeToken(raw:RawToken): Seq[RawToken] = {
    //
    // Unlike CoreNLP, we allow single quotes inside words
    // We must separate important linguistic constructs here
    //
    // genitive
    if("""'[sS]$""".r.findFirstIn(raw.text).isDefined) {
      val tokens = new ListBuffer[RawToken]
      tokens += RawToken(raw.text.substring(0, raw.text.length - 2), raw.startOffset, raw.endOffset - 2)
      tokens += RawToken(raw.text.substring(raw.text.length - 2), raw.startOffset + raw.text.length - 2, raw.endOffset)
      return tokens
    }
    // "won't"
    if("""^[wW][oO][nN]'[tT]$""".r.findFirstIn(raw.text).isDefined) {
      val tokens = new ListBuffer[RawToken]
      tokens += RawToken("will", raw.startOffset, 2)
      tokens += RawToken("not", raw.startOffset + 2, raw.endOffset)
      return tokens
    }
    // other words ending with "n't"
    if("""[nN]'[tT]$""".r.findFirstIn(raw.text).isDefined) {
      val tokens = new ListBuffer[RawToken]
      tokens += RawToken(raw.text.substring(0, raw.text.length - 3), raw.startOffset, raw.endOffset - 3)
      tokens += RawToken("not", raw.startOffset + raw.text.length - 3, raw.endOffset)
      return tokens
    }

    List(raw)
  }

  /** Sentence splitting over a stream of tokens */
  def sentenceSplitting(tokens:Array[RawToken]):Array[Sentence] = {
    val sentences = new ArrayBuffer[Sentence]()
    var words = new ArrayBuffer[String]()
    var startOffsets = new ArrayBuffer[Int]()
    var endOffsets = new ArrayBuffer[Int]()

    for(i <- tokens.indices) {
      val crt = tokens(i)

      if(EOS.findFirstIn(crt.text).isDefined) {
        // found a token that normally indicates end of sentence

        // next and previous tokens. We need these to detect proper ends of sentences
        var next:Option[RawToken] = None
        if(i < tokens.length - 1) next = Some(tokens(i + 1))
        var prev:Option[RawToken] = None
        if(i > 0) prev = Some(tokens(i - 1))

        var isEos = true
        if(crt.text == "." && prev.isDefined && isAbbreviation(prev.get.text) && crt.startOffset == endOffsets.last) {
          // found a period that should be attached to the previous abbreviation
          endOffsets(endOffsets.size - 1) = crt.endOffset
          words(words.size - 1) = words.last + crt.text

          // we may still have an end of sentence here
          if(next.isDefined && ! isSentStart(next.get.text)) {
            isEos = false
          }
        } else {
          // regular end-of-sentence marker; treat is a distinct token
          words += crt.text
          startOffsets += crt.startOffset
          endOffsets += crt.endOffset
        }

        // found a valid end of sentence; start an empty one
        if(isEos) {
          sentences += Sentence(words.toArray, startOffsets.toArray, endOffsets.toArray)
          words = new ArrayBuffer[String]()
          startOffsets = new ArrayBuffer[Int]()
          endOffsets = new ArrayBuffer[Int]()
        }
      } else {
        // just a regular token
        words += crt.text
        startOffsets += crt.startOffset
        endOffsets += crt.endOffset
      }
    }

    if(words.nonEmpty) {
      sentences += Sentence(words.toArray, startOffsets.toArray, endOffsets.toArray)
    }

    sentences.toArray
  }

  def isAbbreviation(word:String):Boolean = {
    abbreviations.findFirstIn(word).isDefined
  }

  def isSentStart(word:String):Boolean = {
    sentStarts.findFirstIn(word).isDefined
  }

}

case class RawToken(text:String, startOffset:Int, endOffset:Int)

object Tokenizer {
  val EOS: Regex = """^[\.!\?]+$""".r

  val IS_ENGLISH_ABBREVIATION: Regex = loadDictionary("org/clulab/processors/clulab/tokenizer/english.abbreviations")
  val IS_ENGLISH_SENTSTART: Regex = loadDictionary("org/clulab/processors/clulab/tokenizer/english.sentstarts")

  /** Reads all words in the given dictionary and converts them into a single disjunction regex for efficiency */
  private def loadDictionary(rn:String): Regex = {
    val is = Tokenizer.getClass.getClassLoader.getResourceAsStream(rn)
    assert(is != null, s"Failed to find resource $rn in the classpath!")
    val reader = new BufferedReader(new InputStreamReader(is))
    val regex = new StringBuilder
    regex.append("^(")

    var done = false
    var first = true
    while(! done) {
      val line = reader.readLine()
      if(line == null) {
        done = true
      } else if(! line.startsWith("#")) { // skip comments
        if(! first) regex.append("|")
        regex.append(normalizeSpecialChars(line.trim))
        first = false
      }
    }

    regex.append(")$")
    reader.close()
    regex.toString.r
  }

  private def normalizeSpecialChars(s:String):String = {
    var n = s.replaceAll("\\.", "\\\\.")
    n = n.replaceAll("\\-", "\\\\-")
    n
  }

}
