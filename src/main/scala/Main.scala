package com.banno.totp

import java.time.Instant
import java.util.concurrent.TimeUnit

import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import cats.effect.{Bracket, Resource}

import scala.collection.mutable
import scala.concurrent.duration._
import Leak._
import cats.{Alternative, Applicative, Eq}
import cats.data.State

import scala.collection.immutable.Queue
import scala.concurrent.ExecutionContext
import scala.util.Random
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

//  case class CachedResource[F[_], Key, A](key: Key, leak: Leak[F, A])
  case class Waiting[Key, A](key: Key, cb: Callback[A], started: Instant)
  case class PoolState[F[_], Key, A](
    currentTotal: Long, //amount of resources that have been leaked but not released (includes cached)
    allocated: Map[Key, Int],
    cached: Map[Key, Queue[Leak[F, A]]],
    waiting: List[Waiting[Key, Leak[F, A]]])

  def getResourceFromCache[F[_], Key, A](key: Key): State[PoolState[F, Key, A], Option[Leak[F, A]]] =
    State { state =>
      state.cached.get(key)
        .flatMap(_.dequeueOption)
        .map { case (leak, rest) =>
          val updated: Map[Key, Queue[Leak[F, A]]] = state.cached + (key -> rest)
          (state.copy(cached = updated), Some(leak))
        }.getOrElse(state -> None)
    }

  def getResourceFromCachee[F[_]: Sync, Key, A](key: Key, poolRef: Ref[F, PoolState[F, Key, A]]): F[Option[Leak[F, A]]] =
    for {
      pool      <- poolRef.get
      maybeLeak =  pool.cached.get(key).flatMap(_.dequeueOption)
      _         <- maybeLeak.traverse { case (_, rest) =>
        poolRef.set(pool.copy(cached = pool.cached + (key -> rest)))
      }
    } yield maybeLeak.map(_._1)

//  def removeResource[F[_], Key: Eq, A](key: Key): State[PoolState[F, Key, A], Unit] =
//    State { state =>
//
////      Alternative[Option].guard()
//
//      val allocatedUpdated: Map[Key, Int] =
//        state.allocated.get(key).map { allocatedByKey =>
//          if (allocatedByKey > 1)
//            state.allocated + (key -> (allocatedByKey - 1))
//          else
//            state.allocated.filterKeys(_ === key)
//        }.getOrElse(state.allocated)
////      val newState = state.copy(
////        cached = state.cached.filterKeys(_ === key),
////        allocated = state.allocated.filterKeys(_ === key))
//      state.copy(
//        cached = state.cached.filterKeys(_ === key),
//        allocated = allocatedUpdated) -> ()
//    }

  def removeResource[F[_]: Sync, Key: Eq, A](key: Key, poolState: Ref[F, PoolState[F, Key, A]]): F[Unit] =
    poolState.get.flatMap { state =>
      val allocatedUpdated: Map[Key, Int] =
        state.allocated.get(key).map { allocatedByKey =>
          if (allocatedByKey > 1)
            state.allocated + (key -> (allocatedByKey - 1))
          else
            state.allocated.filterKeys(_ === key)
        }.getOrElse(state.allocated)

      poolState.set(
        state.copy(
          cached = state.cached.filterKeys(_ === key),
          allocated = allocatedUpdated))
    }

  //need to verify that `Resources` that we are `take`ing are still valid - i.e. via some supplied method

  type Callback[A] = Either[Throwable, A] => Unit

  def queued[F[_], Key: Eq, A](
    r: Resource[F, A],
    maxInstances: Long,
    factory: Key => Resource[F, A],
    reset: A => F[Boolean],
    stillUsable: A => F[Boolean] //function that allows us to check if a given A is still usable or if we should remove and grab new
  )(implicit F: Concurrent[F]): F[Pool[F, A]] =
    for {
      semaphore <- Semaphore[F](1)
      state <- Ref.of[F, PoolState[F, Key, A]](
        PoolState(0, Map.empty[Key, Int], Map.empty[Key, Queue[Leak[F, A]]], List.empty[Waiting[Key, Leak[F, A]]]))
//      cache <- MVar.of[F, List[Leak[F, A]]](Nil)
//      closed <- Ref.of[F, Boolean](false)
    } yield new Pool[F, A] {

//      private def take(key: Key, factory: F[Leak[F, A]]): F[Leak[F, A]] =
//        semaphore.withPermit {
//          for {
//            s <- state.get
//
//            maybeLeak <- getResourceFromCachee(key, state)
//
//            _ <-
//
//
////            cachedTuple = state.cached.get(key)
//
//
//            //If None we're in a few states:
//            // - Pool is full, maxInstances have been leased
//            // - Not full but we don't have a cached resource to re-use so we have to make a new one
//            // - maxConnectionsPerRequestKey needs to be verified
//            // - if none of the above, then we need to addToWaitQueue
////            _ = cachedTuple.fold {
////
////            }
//
//            leaks <- cache.take
//            lfa   <- leaks match {
//              case a :: as => cache.put(as).as(a)
//              case Nil => cache.put(Nil) *> factory.onError { case _ => semaphore.release }
//            }
//          } yield lfa
//        }

//      private def decr

      private def numConnectionsCheckHolds(key: Key, state: PoolState[F, Key, A]): Boolean =
        //curTotal < maxTotal && allocated.getOrElse(key, 0) < maxConnectionsPerRequestKey(key)
        state.currentTotal < maxInstances //&& state.allocated.getOrElse(key, 0) < maxConnectionsPerRequestKey(key)

      //make it so this can be pulled out
      private def handleMaybeLeak(
        key: Key,
        pool: Ref[F, PoolState[F, Key, A]],
        c: Option[Leak[F, A]],
        cb: Callback[Leak[F, A]]): F[Unit] = {

        pool.get.flatMap { state =>
          c match {
            case Some(r) => stillUsable(r.value).ifM(
              ifTrue = F.delay(cb(Right(r))),
              ifFalse = removeResource(key, pool) >> handleMaybeLeak(key, pool, c, cb))

            case None if numConnectionsCheckHolds(key, state) => leakResource(key, pool, cb)

            //case maxResourcesPerKey

            case None if state.currentTotal == maxInstances => blah(key, pool, cb)

            //full
            case None => addToWaitQueue(key, pool, cb)
          }
        }
      }

      private def incrConnection(key: Key, pool: Ref[F, PoolState[F, Key, A]]): F[Unit] =
        for {
          state <- pool.get
          updated = state.copy(
            currentTotal = state.currentTotal + 1,
            allocated = state.allocated + (key -> (state.allocated.getOrElse(key, 0) + 1))
          )
          _    <- pool.set(updated)
        } yield ()

      private def leakResource(key: Key, pool: Ref[F, PoolState[F, Key, A]], callback: Callback[Leak[F, A]]): F[Unit] =
        //should we allow someone to specify the execution context used for building the Resource?
        incrConnection(key, pool) >> F.start {
          Leak.of(factory(key)).attempt.flatMap {
            case Right(leak) =>
              F.delay(callback(Right(leak)))
            case Left(error) =>
              removeResource(key, pool) *> F.delay(callback(Left(error)))
          }
        }.void

      private def blah(key: Key, pool: Ref[F, PoolState[F, Key, A]], callback: Callback[Leak[F, A]]): F[Unit] = {
        pool.get.flatMap { state =>
          val keys = state.cached.keys
          if (keys.nonEmpty) {
            F.delay(keys.iterator.drop(Random.nextInt(keys.size)).next()).flatMap { randKey =>
              getResourceFromCachee(randKey, pool).flatMap {
                case Some(leak) => leak.release
                case None => F.unit //log here, shouldn't happen, better error handling?
              } *> leakResource(key, pool, callback)
            }
          } else addToWaitQueue(key, pool, callback)
        }
      }

      //implement maxWaitQueueLength
      private def addToWaitQueue(key: Key, pool: Ref[F, PoolState[F, Key, A]], callback: Callback[Leak[F, A]]): F[Unit] =
        pool.get.flatMap { state =>
          pool.set(state.copy(waiting = Waiting(key, callback, Instant.now()) :: state.waiting))
        }

//      private def release(leak: Leak[F, A], pool: Ref[F, PoolState[F, Key, A]]): F[Unit] =
//        semaphore.withPermit {
//          pool.get.flatMap { state =>
//            stillUsable(leak.value).attempt.flatMap {
//              case Right(true) => pool.set(state.copy(cached = leak :: state.cached)) *> semaphore.release
//              case Right(false) => ??? //cache.put(q) *> semaphore.release *> leak.release.attempt.void
//              case Left(e) => ??? //cache.put(q) *> semaphore.release *> leak.release.attempt.void *> Concurrent[F].raiseError(e)
//            }
//          }
//        }

//      private def release(leak: Leak[F, A]): F[Unit] = //???
//        cache.take.flatMap { q =>
//          closed.get.ifM(
//            ifTrue = cache.put(q) *> semaphore.release *> leak.release.attempt.void,
//            ifFalse =
//              reset(leak.value).attempt.flatMap {
//                case Right(true) => cache.put(leak :: q) *> semaphore.release
//                case Right(false) => cache.put(q) *> semaphore.release *> leak.release.attempt.void
//                case Left(e) => cache.put(q) *> semaphore.release *> leak.release.attempt.void *> Concurrent[F].raiseError(e)
//              }
//          )
//        }


      def close: F[Unit] = ???

      def available: F[Long] = ???

      def resource: Resource[F, A] = ???

      def resource(key: Key): Resource[F, A] = ???
//        F.asyncF[Leak[F, A]] { cb =>
//          getResourceFromCachee(key, state).flatMap(handleMaybeLeak(key, state, _, cb))
//        }.flatMap { leak =>
//          Resource.make(leak.value)(release)
//        }




      //??? //release by requestkey????????
//        Resource.make(take(Leak.of(r)))(release).flatMap(x => Resource.liftF(Sync[F].delay(x.value)))
    }
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