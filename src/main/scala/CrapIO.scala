package com.banno.crapIO

import java.util.concurrent.locks.AbstractQueuedSynchronizer

import cats.effect.IO

import scala.annotation.tailrec

object CrapIOLoopStuff {

  private[this] def wrap[A, B](f: A => B): A => CrapIO[B] = (a: A) => CrapIO(f(a))

  def runLoopEither[A](cio: CrapIO[A], cb: Either[Throwable, A] => Unit): Unit = {
    //@tailrec //can't use because it's called in a non-tail rec position. Instead of making this recursive, we can
    // just turn it into a while true loop...
    def loop(
      cio: CrapIO[Any],
      firstBind: Option[Any => CrapIO[Any]],
      mapStack: List[Any => CrapIO[Any]],
      cb: Either[Throwable, A] => Unit): Unit = {
      cio match {
        case Pure(a) =>
          if (firstBind.isEmpty)
            cb(Right(a.asInstanceOf[A]))
          else {
            val bind = firstBind.get
            loop(bind(a), mapStack.headOption, mapStack.drop(1), cb)
          }

        case Delay(f) =>
          if (firstBind.isEmpty)
            cb(Right(f().asInstanceOf[A]))
          else {
            val bind = firstBind.get
            loop(bind(f()), mapStack.headOption, mapStack.drop(1), cb)
          }

        case Map(toMap, f) =>
          if (firstBind.isEmpty)
            loop(toMap, Some(wrap(f)), mapStack, cb)
          else loop(toMap, Some(wrap(f)), firstBind.toList ::: mapStack, cb)

        case Bind(toBind, f) =>
          if (firstBind.isEmpty)
            loop(toBind, Some(f), mapStack, cb)
          else loop(toBind, Some(f), firstBind.toList ::: mapStack, cb)

        case Async(f) =>
          f{
            (res: Either[Throwable, Any]) => {
              val weird = res.right.get //should work for Right and Left, need to fix
              loop(CrapIO(weird), firstBind, mapStack, cb)
            }
          }
      }
    }

    loop(cio, None, Nil, cb)
  }

  def unsafeRunLoop[A](cio: CrapIO[A]): A = {
    val latch = new OneShotLatch
    var ref: Either[Throwable, A] = null

    val cb: Either[Throwable, A] => Unit = result => {
      ref = result
      latch.releaseShared(1)
    }

    runLoopEither(cio, cb)

    latch.acquireSharedInterruptibly(1)

    ref match {
      case Right(success) => success
      case Left(t) => throw t
    }
  }

  //taken straight from cats-effect, essentially allows us to block until the resource is returned
  private final class OneShotLatch extends AbstractQueuedSynchronizer {
    override protected def tryAcquireShared(ignored: Int): Int =
      if (getState != 0) 1 else -1

    override protected def tryReleaseShared(ignore: Int): Boolean = {
      setState(1)
      true
    }
  }
}

sealed trait CrapIO[+A] {
  def unsafeRunSync(): A = CrapIOLoopStuff.unsafeRunLoop(this)

  def map[B](f: A => B): CrapIO[B] =
    Map(this, f)

  def flatMap[B](f: A => CrapIO[B]): CrapIO[B] =
    Bind(this, f)
}

object CrapIO {
  def apply[A](a: => A): CrapIO[A] = Delay(() => a)

  def async[A](cb: (Either[Throwable, A] => Unit) => Unit): CrapIO[A] =
    Async(cb)

  def never: CrapIO[Unit] = CrapIO.async(_ => ())
}

case class Pure[A](a: A) extends CrapIO[A]
case class Delay[A](a: () => A) extends CrapIO[A]
case class Map[A, B](cio: CrapIO[A], f: A => B) extends CrapIO[B]
case class Bind[A, B](cio: CrapIO[A], f: A => CrapIO[B]) extends CrapIO[B]
case class Async[A](cb: (Either[Throwable, A] => Unit) => Unit) extends CrapIO[A]

object CrapIOApp extends App {
//  val pure: CrapIO[String] = Pure("hello")
//  val delay: CrapIO[Unit] = CrapIO(println("HI!"))
//
//  val mapped: CrapIO[Int] = CrapIO(1).map(_ + 1)
//  println(pure)
//  println(delay)
//  println(mapped)
//
//  delay.unsafeRunSync()
//  println(mapped.unsafeRunSync())
//
//
//  val moreComplex = CrapIO(1).map(_ + 1).map(_ * 5).map(_ / 2).map(_ * 100)
//  println(moreComplex.unsafeRunSync())

  println(CrapIO.never.unsafeRunSync())

//  val result = for {
//    _ <- CrapIO(println("Starting!"))
//    _ <- CrapIO(println("Well here we are..."))
//    x <- CrapIO(5)
//    y <- CrapIO(1)
//    _ <- CrapIO(println("Just nonsense"))
//    z <- CrapIO(100)
//  } yield (x + y) * z
//
//  println(result.unsafeRunSync())
}