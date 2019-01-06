package com.tbrown.candymachine

import cats.effect._
import cats.implicits._

import scala.concurrent.duration._

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

      //a thousand fibers can be started all waiting for a piece of candy, no threads blocked
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