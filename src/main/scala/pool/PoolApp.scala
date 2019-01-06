package com.tbrown.pool

import cats.effect._
import cats.effect.Resource
import cats.implicits._

import java.util.concurrent.TimeUnit

import scala.concurrent.duration._

object Test extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    val printingResource: Resource[IO, Unit] = Resource.make(IO(println("Acquiring...")))(_ => IO(println("...Released")))
    val reset: Unit => IO[Boolean] = _ => IO(true)

    def usingResource[F[_]: Concurrent](pool: Pool[F, Unit], durationMs: Int): F[Unit] = {
      pool.resource.use(_ => Sync[F].delay { println("...Using..."); Thread.sleep(durationMs); println("DONE!") })
    }

    println(Runtime.getRuntime().availableProcessors())

    //need to write some actual tests...
    for {
      pool <- Pool.create[IO, Unit](printingResource, 2, reset)
      start <- Clock[IO].monotonic(TimeUnit.MILLISECONDS)

      lr <- usingResource(pool, 5000).start
      _  <- Timer[IO].sleep(10 millis)

      sr <- usingResource(pool, 5000).start
      _  <- Timer[IO].sleep(10 millis)

      x  <- usingResource(pool, 10000).start
      _ <- Timer[IO].sleep(200 millis) >> pool.available.map(println).void

      _ <- IO(println("Attempting to join....."))

      _ <- lr.join >> IO(println("LR joined"))
      lrEnd <- Clock[IO].monotonic(TimeUnit.MILLISECONDS)
      _ <- IO(println(s"LR join time: ${lrEnd - start}"))

      _ <- sr.join >> IO(println("SR joined"))
      srEnd <- Clock[IO].monotonic(TimeUnit.MILLISECONDS)
      _ <- IO(println(s"SR join time: ${srEnd - start}"))

      _ <- x.join >> IO(println("X joined"))
      xEnd <- Clock[IO].monotonic(TimeUnit.MILLISECONDS)
      _ <- IO(println(s"X join time: ${xEnd - start}"))
      //      _ <- x.join >> IO(println("X joined"))


      _ <- IO(println("CLOSING THE POOL"))
      _ <- pool.close
      //      alreadyClosed <- usingResource(pool, 4000).start
      //      either <- alreadyClosed.join.attempt
      //      _ <- IO(println(either))
    } yield ExitCode.Success
  }
}
