package com.banno.totp

import java.util.concurrent.{ExecutorService, Executors}
import java.util.concurrent.Executors._

import scala.concurrent.ExecutionContext

object ECExample extends App {
//  trait ExecutionContext {
//
//    /** Runs a block of code on this execution context.
//      *
//      *  @param runnable  the task to execute
//      */
//    def execute(runnable: Runnable): Unit
// }

//  public interface Runnable {
//    /**
//      * When an object implementing interface <code>Runnable</code> is used
//      * to create a thread, starting the thread causes the object's
//      * <code>run</code> method to be called in that separately executing
//      * thread.
//      * <p>
//      * The general contract of the method <code>run</code> is that it may
//      * take any action whatsoever.
//      *
//      * @see     java.lang.Thread#run()
//      */
//    public abstract void run();
//  }


  //An ExecutionContext is an abstraction over a ThreadPool. It can be built from an Executor, which is a similar abstraction
  //to an ExecutionContext, from Java


  //ExecutorService is a Java interface that provides `pool`ing functionality for the Threads you are constructing here.
  //This allows our program to reuse Threads so that they don't have to be destroyed and re-created (this is "costly" or
  //slow and requries more cpu time than we'd like). By keeping a pool we can reuse our threads to get the most out of them
  val executor: ExecutorService = Executors.newFixedThreadPool(4)
  //`newFixedThreadPool` means you have a fixed number of threads, and it will not fluctuate up or down. 4 is what you always have.
  //From the javadocs:
  //A `thread` is a thread of execution in a program. The Java Virtual Machine allows an application to
  //have multiple threads of execution running concurrently.
  //Basically, it is a construct that can process work, and if you have more than 1 you can process work concurrently.

  val ourEC = ExecutionContext.fromExecutor(executor)

  def runnable(n: Int) = new Runnable {
    override def run(): Unit = {
      println(s"Hello $n")
      Thread.sleep(3000)
      println(s"done $n")
    }
  }

  List.range(1, 10).foreach(n => ourEC.execute(runnable(n)))

  executor.shutdown()

  //You'll notice those don't all print in order

  //Executing on an ExecutionContext is what Task/IO/Future allow us to do. Those data types just give us great APIs for
  //dealing with this asynchronous stuff.

  //Normally, code that you write just executes on whatever Thread you're currently on. In a `Main` method it's probably
  //the `main` thread. In our example above, the `runnable` code is executing on threads totally separate from our `main` thread.
  //Without using something like `.unsafeRunSync` there's not really a way to get the result from the `.execute` back to our
  //current Thread. Thankfully that's okay though! We won't ever really need to do that.
}
