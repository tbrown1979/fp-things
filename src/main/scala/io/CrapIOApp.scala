package com.tbrown.io

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
