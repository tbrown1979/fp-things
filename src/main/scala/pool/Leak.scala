package com.tbrown.pool

import cats.implicits._
import cats.effect.{Bracket, Resource}

object Leak {
  //Represents a `Resource`
  sealed abstract case class Leak[F[_], A](value: A, release: F[Unit])

  def of[F[_], A](r: Resource[F, A])(implicit b: Bracket[F, Throwable]): F[Leak[F, A]] = {
    r.allocated.map(t => new Leak(t._1, t._2) {})
  }
}

