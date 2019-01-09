package com.tbrown.io

import scala.util.control.NonFatal

object CrapIOLoopStuff {
  import Utils._

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
          if (firstBind.isEmpty) {
            try {
              cb(Right(f().asInstanceOf[A]))
            } catch {
              case NonFatal(t) => cb(Left(t))
            }
          }
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
          f {
            (res: Either[Throwable, Any]) => {
              val weird = res.right.get //should work for Right and Left, need to fix
              loop(CrapIO(weird), firstBind, mapStack, cb)
            }
          }

        case RaiseError(e) =>
          cb(Left(e))
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
}