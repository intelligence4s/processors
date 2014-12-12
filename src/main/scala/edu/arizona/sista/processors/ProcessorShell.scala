package edu.arizona.sista.processors

import edu.arizona.sista.processors.bionlp.BioNLPProcessor

/**
 * A simple interactive shell
 * User: mihais
 * Date: 3/13/14
 */
object ProcessorShell {
  def main(args:Array[String]) {
    // create the processor
    // val proc:Processor = new CoreNLPProcessor()
    val proc:Processor = new BioNLPProcessor(removeFigTabReferences = true)

    while(true) {
      print("> ")
      var text = Console.readLine()
      val doc = proc.annotate(text)
      ProcessorExample.printDoc(doc)
    }
  }
}
