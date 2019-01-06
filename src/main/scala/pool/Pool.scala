package com.tbrown.pool

import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import cats.effect.Resource

import com.tbrown.pool.Leak._

import scala.util.control.NoStackTrace

trait Pool[F[_], A] {
  def resource: Resource[F, A]
  def available: F[Long]
  def close: F[Unit]
}

object Pool {
  private case object AlreadyClosed extends RuntimeException with NoStackTrace

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
}