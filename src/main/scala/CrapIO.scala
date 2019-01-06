package com.banno.crapIO

import scala.annotation.tailrec

object CrapIOLoopStuff {

  private[this] def wrap[A, B](f: A => B): A => CrapIO[B] = (a: A) => CrapIO(f(a))

  def runLoopEither[A](cio: CrapIO[A]): Either[Throwable, A] = {
    @tailrec
    def loop(cio: CrapIO[Any], firstBind: Option[Any => CrapIO[Any]], mapStack: List[Any => CrapIO[Any]]): Either[Throwable, A] = {
      cio match {
        case Pure(a) =>
          if (firstBind.isEmpty)
            Right(a.asInstanceOf[A])
          else {
            val bind = firstBind.get
            loop(bind(a), mapStack.headOption, mapStack.drop(1))
          }

        case Delay(f) =>
          if (firstBind.isEmpty)
            Right(f().asInstanceOf[A])
          else {
            val bind = firstBind.get
            loop(bind(f()), mapStack.headOption, mapStack.drop(1))
          }

        case Map(toMap, f) =>
          if (firstBind.isEmpty)
            loop(toMap, Some(wrap(f)), mapStack)
          else loop(toMap, Some(wrap(f)), firstBind.toList ::: mapStack)

        case Bind(toBind, f) =>
          if (firstBind.isEmpty)
            loop(toBind, Some(f), mapStack)
          else loop(toBind, Some(f), firstBind.toList ::: mapStack)

        //case
      }
    }

    loop(cio, None, Nil)
  }

  def unsafeRunLoop[A](cio: CrapIO[A]): A = runLoopEither(cio).fold(throw _, identity)
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
}

case class Pure[A](a: A) extends CrapIO[A]
case class Delay[A](a: () => A) extends CrapIO[A]
case class Map[A, B](cio: CrapIO[A], f: A => B) extends CrapIO[B]
case class Bind[A, B](cio: CrapIO[A], f: A => CrapIO[B]) extends CrapIO[B]
//case class Async[]

//trait CrapIOApp {
//  def run(): CrapIO[A]
//
//  def main(args: Array[String]): Unit =
//
//}

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

  val result = for {
    _ <- CrapIO(println("Starting!"))
    _ <- CrapIO(println("Well here we are..."))
    x <- CrapIO(5)
    y <- CrapIO(1)
    _ <- CrapIO(println("Just nonsense"))
    z <- CrapIO(100)
  } yield (x + y) * z

  println(result.unsafeRunSync())
}