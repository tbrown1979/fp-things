package com.tbrown.candymachine

import cats.effect._

import scala.collection.mutable

case class Candy[F[_]](thank: F[Unit])

trait CandyMachine[F[_]] {
  def giveIt: F[Candy[F]]
  def peopleInLine: F[Long]
}

object CandyMachine {
  private[this] case class PersonWaiting[F[_]](cb: Either[Throwable, Candy[F]] => Unit)

  //Candy Man will give candy away for free but you _must_ thank him.
  //He won't hand out anymore candy until you do
  def build[F[_]: Concurrent : Timer] =
    new CandyMachine[F] {
      private[this] var givingCandy: Boolean = false
      private[this] val queue: mutable.Queue[PersonWaiting[F]] = mutable.Queue.empty

      def thank: F[Unit] = Sync[F].delay {
        synchronized {
          if (queue.isEmpty)
            givingCandy = false
          else {
            val firstInLine = queue.dequeue()
            firstInLine.cb(Right(Candy(thank)))
          }
        }
      }

      private[this] def giveCandy(person: PersonWaiting[F]): F[Unit] =
        Sync[F].delay(person.cb(Right(Candy(thank))))

      val peopleInLine: F[Long] = Sync[F].delay(queue.size.toLong)

      val giveIt: F[Candy[F]] =
        Async[F].asyncF { cb =>
          synchronized {
            if (givingCandy) {
              queue.enqueue(PersonWaiting[F](cb))
              Sync[F].delay(())
            }
            else {
              givingCandy = true
              giveCandy(PersonWaiting[F](cb))
            }
          }
        }
    }
}