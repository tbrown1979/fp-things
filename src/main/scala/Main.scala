package com.banno.totp

import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import cats.effect.{Bracket, Resource}
import com.banno.totp.Test.Pool.PoolData
import scala.concurrent.duration._

sealed abstract case class Leak[F[_], A](value: A, release: F[Unit])

object Leak {
  def of[F[_], A](r: Resource[F, A])(implicit b: Bracket[F, Throwable]): F[Leak[F, A]] = {
    r.allocated.map(t => new Leak(t._1, t._2) {})
  }
}

object Test extends IOApp {

  object Pool {

    //    /**
    //      * Resource that yields a non-blocking **pooled** version of `rsrc`, with up to `maxInstances`
    //      * live instances permitted, constructed lazily. A released instance is returned to the pool if
    //      * `reset` yields `true` (the typical case), otherwise it is discarded. This gives the pool a
    //      * chance to verify that an instance is valid, and reset it to a "clean" state if necessary. All
    //      * instances are released when the pool is released, in an undefined order (i.e., don't use this
    //      * if `rsrc` yields instances that depend on each other).
    //      */
    //    def of[F[_]: Concurrent, A](
    //      rsrc: Resource[F, A],
    //      maxInstances: Long,
    //      reset: A => F[Boolean]
    //    ): Pool[F, A] =
    //      Resource.make(PoolData.create(rsrc, maxInstances, reset))(_.close).map(_.resource)

//    /**
//      * Internal state used by a pool. We need an underlying resource, a counting semaphore to limit
//      * concurrent instances, a cache to store instances for reuse, and a reset program constructor
//      * for checking validity (and arbitrary cleanup) of returned instances.
//      */

//    trait Pool[F[_], A] {
//      def resource: Resource[F, A]
//      def close: F[Unit]
//    }

    //We need to use a Semaphore to bound the amount of resources we can give out
    //We need to be able to reset a resource in the case that it can no longer stay cached
    //We need to cache our resources so that we can clean them up?
    final class PoolData[F[_]: Concurrent, A](
      underlying: Resource[F, A],
      semaphore: Semaphore[F],
      cache: MVar[F, List[Leak[F, A]]],
      reset: A => F[Boolean]
    ) {

      // Take a permit and yield a leaked resource from the queue, or leak a new one if necessary
      // If an error is raised leaking the resource we give up the permit and re-throw
      private def take(factory: F[Leak[F, A]]): F[Leak[F, A]] =
        for {
          _ <- semaphore.acquire
          q <- cache.take
          lfa <- q match {
            case a :: as => cache.put(as).as(a)
            case Nil => cache.put(Nil) *> factory.onError { case _ => semaphore.release }
          }
        } yield lfa

      // Add a leaked resource to the pool and release a permit
      private def release(leak: Leak[F, A]): F[Unit] =
        cache.take.flatMap { q =>
          Sync[F].delay(println("Trying to release....")) *>
          reset(leak.value).attempt.flatMap {
            case Right(true) =>
              cache.put(leak :: q) *> semaphore.release *> Sync[F].delay(println("Should be cached now"))
            case Right(false) => cache.put(q) *> semaphore.release
            case Left(e) => cache.put(q) *> semaphore.release *> Concurrent[F].raiseError(e)
          }
        }

      def count: F[Long] = semaphore.count

      // Release all resources
      def close: F[Unit] =
        for {
          _ <- Sync[F].delay(println("Trying to close all resources"))
          leaks <- cache.take
          _ <- leaks.traverse(_.release.attempt) // on error no big deal?
        } yield ()

      // View this bundle of nastiness as a resource
      val resource: Resource[F, A] =
        Resource.make(take(Leak.of(underlying)))(release).flatMap(x => Resource.liftF(Sync[F].delay(x.value)))

    }

    object PoolData {
      def create[F[_]: Concurrent, A](
        r: Resource[F, A],
        maxInstances: Long,
        reset: A => F[Boolean]
      ): F[PoolData[F, A]] =
        for {
          sem <- Semaphore[F](maxInstances)
          cache <- MVar[F].of(List.empty[Leak[F, A]])
        } yield new PoolData(r, sem, cache, reset)
    }

  }

  def run(args: List[String]): IO[ExitCode] = {
    val printingResource: Resource[IO, Unit] = Resource.make(IO(println("Acquiring...")))(_ => IO(println("...Released")))
    val reset: Unit => IO[Boolean] = _ => IO(true)

//    val pool = PoolData.create[IO, Unit](printingResource, 2, reset).unsafeRunSync()
//
//    val cs = ContextShift[IO]

    def usingResource[F[_]: Concurrent](pool: PoolData[F, Unit], durationMs: Int): F[Unit] = {
      pool.resource.use(_ => Sync[F].delay { println("...Using..."); Thread.sleep(5000) })
    }
//
//    val longRunning = pool.resource.use(_ => IO { println("...Using..."); Thread.sleep(5000) })
//    val shortRunning = pool.resource.use(_ => IO { println("...Using2..."); Thread.sleep(1000) })
    //pool.resource.use(_ => IO(println("...Using3..."))).unsafeRunSync()

//    val theResource = pool.resource
//
//    theResource

//    longRunning.unsafeRunSync()
//    shortRunning.unsafeRunSync()
//    println(pool.count.unsafeRunSync())




    for {
      pool <- PoolData.create[IO, Unit](printingResource, 2, reset)
      lr <- usingResource(pool, 5000).start
      sr <- usingResource(pool, 2000).start
      x  <- usingResource(pool, 10000).start
      _ <- Timer[IO].sleep(200 millis) >> pool.count.map(println).void
      _ <- lr.join >> IO(println("LR joined"))
      _ <- sr.join >> IO(println("SR joined"))
      _ <- x.join
      _ <- pool.close
    } yield ExitCode.Success


//    pool.close.unsafeRunSync()

//    IO(ExitCode.Success)
  }
}
