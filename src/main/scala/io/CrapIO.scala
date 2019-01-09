package com.tbrown.io

import scala.concurrent.ExecutionContext

sealed trait CrapIO[+A] {
  def unsafeRunSync(): A = CrapIOLoopStuff.unsafeRunLoop(this)

  def unsafeRunAsync(cb: Either[Throwable, A] => Unit): Unit =
    CrapIOLoopStuff.runLoopEither(this, cb)

  def runAsync(cb: Either[Throwable, A] => Unit): CrapIO[Unit] =
    CrapIO(unsafeRunAsync(cb))

  def map[B](f: A => B): CrapIO[B] =
    Map(this, f)

  def flatMap[B](f: A => CrapIO[B]): CrapIO[B] =
    Bind(this, f)

  def >>[B](f: A => CrapIO[B]): CrapIO[B] = flatMap(f)
}

object CrapIO {
  def apply[A](a: => A): CrapIO[A] = Delay(() => a)

  def async[A](cb: (Either[Throwable, A] => Unit) => Unit): CrapIO[A] =
    Async(cb)

  def asyncF[A](cb: (Either[Throwable, A] => Unit) => CrapIO[Unit]): CrapIO[A] = {
    Async {
      (x: Either[Throwable, A] => Unit) => CrapIO(CrapIOLoopStuff.runLoopEither(cb(x), (x: Either[Throwable, Unit]) => ()))
    }
  }

  //semantic blocking example...
  def never: CrapIO[Unit] = CrapIO.async(_ => ())

  def shift(ec: ExecutionContext): CrapIO[Unit] = CrapIO.async { cb =>
    ec.execute {
      new Runnable {
        def run(): Unit = cb(Right(()))
      }
    }
  }
}

case class Pure[A](a: A) extends CrapIO[A]
case class Delay[A](a: () => A) extends CrapIO[A]
case class Map[A, B](cio: CrapIO[A], f: A => B) extends CrapIO[B]
case class Bind[A, B](cio: CrapIO[A], f: A => CrapIO[B]) extends CrapIO[B]
case class Async[A](cb: (Either[Throwable, A] => Unit) => Unit) extends CrapIO[A]
case class RaiseError[A](e: Throwable) extends CrapIO[A]