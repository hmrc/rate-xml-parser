/*
 * Copyright 2017 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.akka.xml

import akka.NotUsed
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.scaladsl.Flow
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.ByteString
import com.fasterxml.aalto.{AsyncByteArrayFeeder, AsyncXMLInputFactory, AsyncXMLStreamReader, WFCException}
import com.fasterxml.aalto.stax.InputFactoryImpl

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Try}

/**
  * Created by abhishek on 28/02/17.
  */
object ParsingStage {
  val MALFORMED_STATUS = "Malformed"
  val STREAM_MAX_SIZE = "Stream max size"
  val STREAM_IS_EMPTY = "Stream is empty"
  val STREAM_SIZE_LESS_AND_BELOW_MIN = "Stream Size"
  val STREAM_SIZE = "Stream Size"
  val NO_VALIDATION_TAGS_FOUND_IN_FIRST_N_BYTES_FAILURE = "No validation tags were found in first n bytes failure"
  val VALIDATION_INSTRUCTION_FAILURE = "Validation instruction failure"
  val PARTIAL_OR_NO_VALIDATIONS_DONE_FAILURE = "Not all of the xml validations / checks were done"
  val XML_START_END_TAGS_MISMATCH = "Start and End tags mismatch. Element(s) - "

  def parser(instructions: Set[XMLInstruction], validationMaxSize: Option[Int] = None, validationMaxSizeOffset: Int = 10000)
  : Flow[ParsingData, (ByteString, Set[XMLElement]), NotUsed] = {
    Flow.fromGraph(new StreamingXmlParser(instructions, validationMaxSize, validationMaxSizeOffset))
  }

  private class StreamingXmlParser(instructions: Set[XMLInstruction], validationMaxSize: Option[Int] = None, validationMaxSizeOffset: Int)
    extends GraphStage[FlowShape[ParsingData, (ByteString, Set[XMLElement])]]
      with StreamHelper
      with ParsingDataFunctions {
    val in: Inlet[ParsingData] = Inlet("XMLParser.in")
    val out: Outlet[(ByteString, Set[XMLElement])] = Outlet("XMLParser.out")
    override val shape: FlowShape[ParsingData, (ByteString, Set[XMLElement])] = FlowShape(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {

        import javax.xml.stream.XMLStreamConstants

        private val feeder: AsyncXMLInputFactory = new InputFactoryImpl()
        private val parser: AsyncXMLStreamReader[AsyncByteArrayFeeder] = feeder.createAsyncFor(Array.empty)

        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            processStage(processOnPush)
          }

          override def onUpstreamFinish(): Unit = {
            parser.getInputFeeder.endOfInput()
            //processStage(processOnUpstreamFinish)
            completeStage()
          }
        })

        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            pull(in)
          }
        })

        def processOnPush() = {
          parsingData = grab(in)
          println("------" + parsingData)
          chunkOffset = 0
          Try {
            parser.getInputFeeder.feedInput(parsingData.data.toArray, 0, parsingData.data.length)
            advanceParser()
            push(out, (ByteString(streamBuffer.toArray),
              getCompletedXMLElements(xmlElements).toSet ++ parsingData.extractedElements))
            streamBuffer.clear()
          }
        }

        def processStage(f: () => Try[Unit]) = {
          f().recover {
            //              case e: WFCException =>
            //                emitStage(
            //                  XMLElement(Nil, Map(MALFORMED_STATUS -> e.getMessage), Some(MALFORMED_STATUS)),
            //                  XMLElement(Nil, Map(STREAM_SIZE -> totalReceivedLength.toString), Some(STREAM_SIZE))
            //                )(ByteString(incompleteBytes.toArray ++ chunk))
            //                completeStage()
            //              case e: MaxSizeError =>
            //                emitStage(
            //                  XMLElement(Nil, Map.empty, Some(STREAM_MAX_SIZE)),
            //                  XMLElement(Nil, Map(STREAM_SIZE -> totalReceivedLength.toString), Some(STREAM_SIZE))
            //                )(ByteString(incompleteBytes.toArray ++ chunk))
            //                completeStage()
            //              case e: EmptyStreamError =>
            //                emitStage(
            //                  XMLElement(Nil, Map.empty, Some(STREAM_IS_EMPTY)),
            //                  XMLElement(Nil, Map(STREAM_SIZE -> totalReceivedLength.toString), Some(STREAM_SIZE))
            //                )(ByteString(Array.empty[Byte]))
            //                completeStage()
            case e: Throwable =>
              throw e
          }
        }

        var parsingData = ParsingData(ByteString(""), Set.empty, 0)
        var isCharacterBuffering = false
        var chunkOffset = 0

        val node = ArrayBuffer[String]()
        val completedInstructions = mutable.Set[XMLInstruction]()
        val xmlElements = mutable.Set[XMLElement]()
        val bufferedText = new StringBuilder
        val streamBuffer = ArrayBuffer[Byte]()
        val validators = mutable.Map[XMLValidate, ArrayBuffer[Byte]]()

        @tailrec private def advanceParser(): Unit = {
          if (parser.hasNext) {
            val event = parser.next()
            val (start, end) = getBounds(parser)
            event match {

              case AsyncXMLStreamReader.EVENT_INCOMPLETE =>
                streamBuffer ++= parsingData.data.slice(chunkOffset, parsingData.data.length)

              case XMLStreamConstants.START_ELEMENT =>
                node += parser.getLocalName
                instructions.diff(completedInstructions).foreach(f = (e: XMLInstruction) => e match {
                  case e@XMLExtract(`node`, _) if getPredicateMatch(parser, e.attributes).nonEmpty || e.attributes.isEmpty =>
                    val keys = getPredicateMatch(parser, e.attributes)
                    val ele = XMLElement(e.xPath, keys, None)
                    xmlElements.add(ele)

                  case e: XMLUpdate if e.xPath == node.slice(0, e.xPath.length) =>
                    e.xPath match {
                      case path if path == node.toList =>
                        val input = getUpdatedElement(e.xPath, e.attributes, e.value)(parser).getBytes
                        streamBuffer ++= insertBytes(parsingData.data, chunkOffset, start, input)
                        chunkOffset = end
                      case _ =>
                        chunkOffset = end
                    }

                  case e: XMLValidate if e.start == node.slice(0, e.start.length) =>
                    val newBytes = parsingData.data.slice(start, end)
                    if (!parser.isEmptyElement) {
                      val ele = validators.get(e) match {
                        case Some(x) => (e, x ++ newBytes)
                        case None => (e, ArrayBuffer.empty ++= newBytes)
                      }
                      validators += ele
                    }

                  case e: XMLDelete if e.xPath == node.slice(0, e.xPath.length) =>
                    streamBuffer ++= extractBytes(parsingData.data, chunkOffset, start)
                    chunkOffset = end

                  case x =>
                })
                if (parser.hasNext) advanceParser()

              case XMLStreamConstants.END_ELEMENT =>
                isCharacterBuffering = false
                instructions.diff(completedInstructions).foreach(f = (e: XMLInstruction) => {
                  e match {
                    case e@XMLExtract(`node`, _) =>
                      update(xmlElements, node, Some(bufferedText.toString()))

                    case e: XMLUpdate if e.xPath.dropRight(1) == node && e.isUpsert =>
                      val input = getUpdatedElement(e.xPath, e.attributes, e.value)(parser).getBytes
                      streamBuffer ++= insertBytes(parsingData.data, chunkOffset, start, input)
                      completedInstructions += e
                      chunkOffset = start

                    case e: XMLUpdate if e.xPath == node.slice(0, e.xPath.length) =>
                      e.xPath match {
                        case path if path == node.toList =>
                          completedInstructions += e
                        case _ =>
                      }
                      chunkOffset = end

                    case e: XMLValidate if e.start == node.slice(0, e.start.length) =>
                      val newBytes = parsingData.data.slice(start, end)
                      val ele = validators.get(e) match {
                        case Some(x) => (e, x ++= newBytes)
                        case None => throw new IncompleteXMLValidationException
                      }
                      validators += ele
                      validators.foreach {
                        case (s@XMLValidate(_, `node`, f), testData) =>
                          f(new String(testData.toArray)).map(throw _)
                          completedInstructions += e

                        case x =>
                      }

                    case e: XMLDelete if e.xPath == node.slice(0, e.xPath.length) =>
                      chunkOffset = end

                    case x =>
                  }
                })
                bufferedText.clear()
                node -= parser.getLocalName
                if (parser.hasNext) advanceParser()

              case XMLStreamConstants.CHARACTERS =>
                instructions.foreach(f = (e: XMLInstruction) => {
                  e match {
                    case e@XMLExtract(`node`, _) =>
                      val t = parser.getText()
                      if (t.trim.length > 0) {
                        isCharacterBuffering = true
                        bufferedText.append(t)
                      }
                    case e: XMLUpdate if e.xPath == node.slice(0, e.xPath.length) =>
                      chunkOffset = end

                    case e: XMLValidate if e.start == node.slice(0, e.start.length) =>
                      val newBytes = parsingData.data.slice(start, end)
                      val ele = validators.get(e) match {
                        case Some(x) => (e, x ++ newBytes)
                        case None => (e, ArrayBuffer.empty ++= newBytes)
                      }
                      validators += ele

                    case e: XMLDelete if e.xPath == node.slice(0, e.xPath.length) =>
                      chunkOffset += (end - start)

                    case _ =>
                  }
                })
                if (parser.hasNext) advanceParser()

              case x =>
                if (parser.hasNext) advanceParser()
            }
          }
        }

        private def getBounds(implicit reader: AsyncXMLStreamReader[AsyncByteArrayFeeder]): (Int, Int) = {
          val start = reader.getLocationInfo.getStartingByteOffset.toInt
          (
            if (start == 1) 0 else start - (parsingData.totalProcessedLength - parsingData.data.length),
            reader.getLocationInfo.getEndingByteOffset.toInt - (parsingData.totalProcessedLength - parsingData.data.length)
            )
        }
      }
  }

}
