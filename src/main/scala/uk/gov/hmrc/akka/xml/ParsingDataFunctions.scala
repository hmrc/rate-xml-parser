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

/**
  * Created by abhishek on 12/12/16.
  */
trait ParsingDataFunctions {

  def getTailBytes(bytes: Array[Byte], from: Int) = {
    bytes.slice(from, bytes.length)
  }

  def insertBytes(chunk: Array[Byte], at: Int, insert: Array[Byte]): Array[Byte] = {
    chunk.slice(0, at) ++ insert
  }

  def getHeadAndTail(chunks: Array[Byte], at: Int, till: Int, insert: Array[Byte], offset: Int) = {
    (chunks.slice(0, at) ++ insert.slice(offset, insert.length), chunks.slice(till, chunks.length))
  }
}
