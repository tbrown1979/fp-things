package com.tbrown.io

import java.util.concurrent.{ExecutorService, Executors}

import scala.concurrent.ExecutionContext

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

  //will never print
//  println(CrapIO.never.unsafeRunSync())



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


  val executor: ExecutorService = Executors.newFixedThreadPool(2)

  val ec = ExecutionContext.fromExecutor(executor)

  val x = for {
    _ <- CrapIO.shift(ec)
    _ <- CrapIO { Thread.sleep(5000); println("DONE WAITING") }
  } yield {}

  //semantic blocking...
  CrapIO.shift(ec).flatMap(_ => CrapIO.never).unsafeRunAsync(_ => ())
  CrapIO.shift(ec).flatMap(_ => CrapIO.never).unsafeRunAsync(_ => ())
  CrapIO.shift(ec).flatMap(_ => CrapIO.never).unsafeRunAsync(_ => ())
  //----------------------------------------

  x.unsafeRunAsync(_ => ())

//  x.unsafeRunSync()

//  x.unsafeRunAsync(_ => println("Fully done"))
//  x.unsafeRunAsync(_ => println("They happened at the same time"))

  Thread.sleep(10000)

  executor.shutdown()
}
