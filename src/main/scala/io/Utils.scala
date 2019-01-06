package com.tbrown.io

import java.util.concurrent.locks.AbstractQueuedSynchronizer

object Utils {
  //taken straight from cats-effect, essentially allows us to block until the resource is returned
  final class OneShotLatch extends AbstractQueuedSynchronizer {
    override protected def tryAcquireShared(ignored: Int): Int =
      if (getState != 0) 1 else -1

    override protected def tryReleaseShared(ignore: Int): Boolean = {
      setState(1)
      true
    }
  }
}
