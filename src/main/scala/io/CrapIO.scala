package com.tbrown.io

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

  //semantic blocking example...
  def never: CrapIO[Unit] = CrapIO.async(_ => ())
}

case class Pure[A](a: A) extends CrapIO[A]
case class Delay[A](a: () => A) extends CrapIO[A]
case class Map[A, B](cio: CrapIO[A], f: A => B) extends CrapIO[B]
case class Bind[A, B](cio: CrapIO[A], f: A => CrapIO[B]) extends CrapIO[B]
case class Async[A](cb: (Either[Throwable, A] => Unit) => Unit) extends CrapIO[A]