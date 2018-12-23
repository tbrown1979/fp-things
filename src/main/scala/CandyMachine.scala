package com.banno.totp

//import cats.FlatMap
import cats.effect._

import scala.collection.mutable
//import cats.effect.concurrent._
//import cats.effect.implicits._
import cats.implicits._
import cats.effect.{Bracket, Resource}
import com.banno.totp.Test.Pool.PoolData
import cats.effect.concurrent._

import scala.concurrent.duration._

//semantic blocking example

case class Candy[F[_]](thank: F[Unit])

trait CandyMachine[F[_]] {
  def giveIt: F[Candy[F]]
  def peopleInLine: F[Long]
}

object CandyMachine {
  private[this] case class PersonWaiting[F[_]](cb: Either[Throwable, Candy[F]] => Unit)


  //Candy store where the Candy Man will give candy away for free but you _must_ thank him.
  //He won't hand out any more candy until you do
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

object CandyMachineApp extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    val candyMachine = CandyMachine.build[IO]

    def getCandyAndWaitBeforeThank[F[_]: Sync : Timer](candyMachine: CandyMachine[F], duration: FiniteDuration): F[Unit] = {
      for {
        candy <- candyMachine.giveIt
        _     <- Sync[F].delay(println("============================================="))
        _     <- Sync[F].delay(println("Got my candy, but haven't thanked!"))
        _     <- candyMachine.peopleInLine.flatTap(l => Sync[F].delay(println(s"People in line: $l")))
        _     <- Timer[F].sleep(duration)
        _     <- candy.thank
        _     <- Sync[F].delay("Thanked!")
        _     <- Sync[F].delay(println("============================================="))
      } yield ()
    }

    val duration = 3 seconds

    candyMachine.peopleInLine.flatTap(l => IO(println(l)))

    for {
      _          <- IO(println("Asking for the first piece of candy"))
      firstCandy <- candyMachine.giveIt
      _          <- IO(println("Got the first Candy"))

      fibers     <- List.range(1,1000).traverse(_ => getCandyAndWaitBeforeThank[IO](candyMachine, 10 millis).start)

      _          <- IO(println("Initial 5 second wait before first thank..."))
      _          <- Timer[IO].sleep(5 seconds)
      _          <- firstCandy.thank

      _          <- IO(println("Flood gates have opened!"))

      _          <- fibers.traverse(_.join)
      _          <- IO(println("Done!"))
    } yield ExitCode.Success
  }

}