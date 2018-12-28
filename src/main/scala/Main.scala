package com.banno.totp

import java.util.concurrent.TimeUnit

import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import cats.effect.{Bracket, Resource}

import scala.collection.mutable
import scala.concurrent.duration._
import Leak._

import scala.concurrent.ExecutionContext
import scala.util.control.NoStackTrace


object Leak {
  //Represents a `Resource`
  sealed abstract case class Leak[F[_], A](value: A, release: F[Unit])

  def of[F[_], A](r: Resource[F, A])(implicit b: Bracket[F, Throwable]): F[Leak[F, A]] = {
    r.allocated.map(t => new Leak(t._1, t._2) {})
  }
}


trait Pool[F[_], A] {
  def resource: Resource[F, A]
  def available: F[Long]
  def close: F[Unit]
}

object Pool {
  //the base for this Pool was taken from Rob Norris' Skunk project here:
  //https://github.com/tpolecat/skunk/blob/3c75aa91a6ec3026377fd5fb572085964e261cd2/modules/core/src/main/scala/util/Pool.scala#L34
  //A few changes have been made:
  // - Leak now works properly
  // - The pool releases if `reset` returns `false` or errors
  // - Better handling of the Pool being `close`d. Will error instead of causing someone to hang. This is necessary
  //   because someone may still be `.release`ing when the Pool is closed, and thus we want to auto-release instead of caching.
  //   Previously this would cause the release to hang and never return because it couldn't `.take` from the Cache.
  def create[F[_], A](
    r: Resource[F, A],
    maxInstances: Long,
    reset: A => F[Boolean]
  )(implicit F: Concurrent[F]): F[Pool[F, A]] =
    for {
      semaphore <- Semaphore[F](maxInstances)
      cache <- MVar.of[F, List[Leak[F, A]]](Nil)
      closed <- Ref.of[F, Boolean](false)
    } yield new Pool[F, A] {

      private def take(factory: F[Leak[F, A]]): F[Leak[F, A]] =
        for {
          _     <- closed.get.ifM(ifTrue = F.raiseError(AlreadyClosed), ifFalse = F.unit)
          _     <- semaphore.acquire
          leaks <- cache.take
          lfa   <- leaks match {
            case a :: as => cache.put(as).as(a)
            case Nil => cache.put(Nil) *> factory.onError { case _ => semaphore.release }
          }
        } yield lfa

      private def release(leak: Leak[F, A]): F[Unit] =
        cache.take.flatMap { q =>
          closed.get.ifM(
            ifTrue = cache.put(q) *> semaphore.release *> leak.release.attempt.void,
            ifFalse =
              reset(leak.value).attempt.flatMap {
                case Right(true) => cache.put(leak :: q) *> semaphore.release
                case Right(false) => cache.put(q) *> semaphore.release *> leak.release.attempt.void
                case Left(e) => cache.put(q) *> semaphore.release *> leak.release.attempt.void *> Concurrent[F].raiseError(e)
              }
          )
        }

      //non-negative
      def available: F[Long] = semaphore.available

      def close: F[Unit] =
        for {
          leaks <- cache.take
          _     <- closed.update(_ => true)
          _     <- leaks.traverse(_.release.attempt) // on error no big deal?
          _     <- cache.put(Nil)
        } yield ()

      //.map isn't working for some reason...
      val resource: Resource[F, A] =
        Resource.make(take(Leak.of(r)))(release).flatMap(x => Resource.liftF(Sync[F].delay(x.value)))
    }

  private case object AlreadyClosed extends RuntimeException with NoStackTrace
}

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


//tests to cover:
//Leaked resources are cached for re-use
//closing releases all cached resources
//anything released after closing will be auto-released
//not fail releasing cached resources in the face of errors or maybe in general