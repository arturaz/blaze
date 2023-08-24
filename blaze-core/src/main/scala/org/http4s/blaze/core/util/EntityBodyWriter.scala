/*
 * Copyright 2014 http4s.org
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

package org.http4s.blaze.core.util

import cats.effect._
import cats.syntax.all._
import fs2._
import org.http4s.Entity

import scala.concurrent._

private[blaze] trait EntityBodyWriter[F[_]] {
  implicit protected def F: Async[F]

  /** Write a Chunk to the wire.
    *
    * @param chunk BodyChunk to write to wire
    * @return a future letting you know when its safe to continue
    */
  protected def writeBodyChunk(chunk: Chunk[Byte], flush: Boolean): Future[Unit]

  /** Write the ending chunk and, in chunked encoding, a trailer to the
    * wire.
    *
    * @param chunk BodyChunk to write to wire
    * @return a future letting you know when its safe to continue (if `false`) or
    * to close the connection (if `true`)
    */
  protected def writeEnd(chunk: Chunk[Byte]): Future[Boolean]

  /** Called in the event of an Await failure to alert the pipeline to cleanup */
  protected def exceptionFlush(): Future[Unit] = FutureUnit

  /** Creates an effect that writes the contents of the [[Entity]] to the output.
    *
    * @param entity an [[Entity]] which body to write out
    * @return the Task which when run will unwind the Process
    */
  def writeEntityBody(entity: Entity[F]): F[Boolean] =
    entity match {
      case Entity.Streamed(body, _) =>
        val writeBody: F[Unit] = writePipe(body).compile.drain
        val writeBodyEnd: F[Boolean] = fromFutureNoShift(F.delay(writeEnd(Chunk.empty)))
        writeBody *> writeBodyEnd

      case Entity.Strict(bv) =>
        fromFutureNoShift(F.delay(writeEnd(Chunk.byteVector(bv))))

      case Entity.Empty =>
        fromFutureNoShift(F.delay(writeEnd(Chunk.empty)))
    }

  /** Writes each of the body chunks, if the write fails it returns
    * the failed future which throws an error.
    * If it errors the error stream becomes the stream, which performs an
    * exception flush and then the stream fails.
    */
  private def writePipe(s: Stream[F, Byte]): Stream[F, Nothing] = {
    def writeChunk(chunk: Chunk[Byte]): F[Unit] =
      fromFutureNoShift(F.delay(writeBodyChunk(chunk, flush = false)))

    val writeStream: Stream[F, Nothing] =
      s.repeatPull {
        _.uncons.flatMap {
          case None => Pull.pure(None)
          case Some((hd, tl)) => Pull.eval(writeChunk(hd)).as(Some(tl))
        }
      }

    val errorStream: Throwable => Stream[F, Nothing] = e =>
      Stream
        .eval(fromFutureNoShift(F.delay(exceptionFlush())))
        .flatMap(_ => Stream.raiseError[F](e))
    writeStream.handleErrorWith(errorStream)
  }

}
