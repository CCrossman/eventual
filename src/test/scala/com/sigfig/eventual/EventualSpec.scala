package com.sigfig.eventual

import org.scalatest.{FreeSpec, Matchers}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

class EventualSpec extends FreeSpec with Matchers {

    "Eventuals should be buildable from thunks" in {
        val eventual = Eventual.done[String]("foo")
        eventual shouldBe a [Eventual[String]]
        eventual.asOption shouldBe Some("foo")
    }

    "Eventuals should be buildable from Futures" in {
        implicit val ec = ExecutionContext.Implicits.global
        val future = Future[String]("bar")
        val eventual = Eventual.fromFuture[String](future)
        eventual shouldBe a [Eventual[String]]

        // completes in a nondeterministic way, so be extra-specific
        future.onComplete {
            case Success(str) =>
                eventual.isComplete shouldBe (true)
                eventual.isFailure shouldBe (false)
                eventual.asOption shouldBe Some(str)
            case Failure(ex) =>
                eventual.isComplete shouldBe (true)
                eventual.isFailure shouldBe (true)
                eventual.asOption shouldBe None
        }

        Await.ready(future,3 seconds)
    }

    "Eventuals should be complete when the underlying future is complete." in {
        implicit val ec = ExecutionContext.Implicits.global
        val promise = Promise[String]()
        val future = promise.future
        val eventual = Eventual.fromFuture(future)
        promise.complete(Success("baz"))

        // completes in a nondeterministic way, so be extra-specific
        future.onComplete {
            case Success(str) =>
                eventual.isFailure should be (false)
                eventual.isComplete should be (true)
                eventual.asOption should be (Some("baz"))
            case Failure(ex) =>
                eventual.isComplete shouldBe (true)
                eventual.isFailure shouldBe (true)
                eventual.asOption shouldBe None
        }

        Await.ready(future,3 seconds)
    }

    "Eventuals should be incomplete when the underlying future is pending." in {
        val promise = Promise[String]()
        val eventual = Eventual.fromFuture(promise.future)
        eventual.isFailure should be(false)
        eventual.isComplete should be(false)
        eventual.asOption should be(None)
    }

    "Eventuals should be pattern-matchable" - {
        "as Done" in {
            val eventual = Eventual.done(1)
            eventual shouldBe a [Done[Int]]
            eventual shouldBe a [Eventual[Int]]
            eventual.asOption shouldBe Some(1)
            eventual should matchPattern { case Done(1) => }
            eventual should matchPattern { case Eventual(Success(1)) => }
        }

        "as Failed" in {
            val ex = new Exception("foo")
            val eventual = Eventual.failed[Int](ex)
            eventual shouldBe a [Failed[Int]]
            eventual shouldBe a [Eventual[Int]]
            eventual.asOptionTry should matchPattern { case Some(Failure(e: Exception)) => }
            eventual.asOption should matchPattern { case None => }
            eventual should matchPattern { case Failed(e) => }
            eventual should matchPattern { case Eventual(Failure(e)) => }
        }

        "as Pending" - {
            "when successful" in {
                val eventual = Eventual.pending[String]
                eventual shouldBe a[Pending[String]]
                eventual shouldBe a[Eventual[String]]
                eventual.asOption shouldBe None
                eventual should matchPattern { case Pending(None) => }
                eventual should not matchPattern { case Eventual(_: Try[Nothing]) => }

                eventual.complete("quovo")
                eventual.asOption shouldBe Some("quovo")
                eventual should matchPattern { case Pending(Some(Success("quovo"))) => }
                eventual should matchPattern { case Eventual(Success("quovo")) => }
            }
            "when failed" in {
                val eventual = Eventual.pending[String]
                val ex = new Exception("quovo")
                eventual.fail(ex)
                eventual.asOption shouldBe None
                eventual should matchPattern { case Pending(Some(Failure(e))) => }
                eventual should matchPattern { case Eventual(Failure(e)) => }
            }
        }

        "as Lazy" in {
            val eventual = Eventual.lazily[Int](1+2)
            eventual shouldBe a [Lazy[Int]]
            eventual shouldBe a [Eventual[Int]]
            eventual.loaded shouldBe false
            eventual.asOption shouldBe None
            eventual.loaded shouldBe false
            eventual.get shouldBe 3
            eventual.loaded shouldBe true
        }
    }

    "Eventuals should be flat-mappable" - {
        "from successful to failed" in {
            val exception = new Exception()
            val eventual1 = Eventual.done(1)
            val eventual2 = eventual1.flatMap(_ => Eventual.failed[String](exception))
            eventual2 shouldBe a [Eventual[String]]
            eventual2.isFailure shouldBe true
            eventual2.isComplete shouldBe true
            Eventual.unapply(eventual2) shouldBe Some(Failure(exception))
        }

        "from failed to successful" in {
            val eventual1 = Eventual.failed(new Exception())
            val eventual2 = eventual1.flatMapLifted(_ => Eventual.done("foo"))
            eventual2.isFailure shouldBe false
            eventual2.isComplete shouldBe true
            eventual2.get shouldBe "foo"
        }

        "from successful to successful" in {
            val eventual1 = Eventual.done("bar")
            val eventual2 = eventual1.flatMap(s => Eventual.done(s.length))
            eventual2 shouldBe a [Eventual[Int]]
            eventual2.isFailure shouldBe false
            eventual2.isComplete shouldBe true
            eventual2.get shouldBe 3
        }

        "from failure to failure" in {
            val eventual1 = Eventual.failed[Int](new IllegalArgumentException)
            val eventual2 = eventual1.failed.flatMap(failure => Eventual.failed[String](new IllegalArgumentException(failure)))
            eventual2 shouldBe a [Eventual[String]]
            eventual2.isFailure shouldBe true
            eventual2.isComplete shouldBe true
            Eventual.unapply(eventual2) should matchPattern { case Some(Failure(_: IllegalArgumentException)) => }
        }

        "skip over function in failed non-T case" in {
            val eventual1 = Eventual.failed[String](new IllegalStateException)
            val eventual2: Eventual[Int] = eventual1.flatMap(i => {
                fail("should not call this code block")
            })
            eventual2.isFailure shouldBe true
            eventual2.isComplete shouldBe true
            Eventual.unapply(eventual2) should matchPattern { case Some(Failure(_: IllegalStateException)) => }
        }
    }

    "Eventuals should be mappable" - {

        "from successful to failed" in {
            val eventual1 = Eventual.done(1)
            val eventual2 = eventual1.mapLifted {
                case Success(t) => Failure(new IllegalArgumentException())
            }
            eventual2 shouldBe a [Eventual[Int]]
            eventual2.isFailure shouldBe true
            eventual2.isComplete shouldBe true
            Eventual.unapply(eventual2) should matchPattern { case Some(Failure(_: IllegalArgumentException)) => }
        }

        "from failed to successful" in {
            val eventual1 = Eventual.failed(new Exception("business error"))
            val eventual2 = eventual1.mapLifted {
                case Failure(reason) => Success(reason.getMessage)
            }
            eventual2.isFailure shouldBe false
            eventual2.isComplete shouldBe true
            eventual2.get shouldBe "business error"
        }

        "from successful to successful" in {
            val eventual1 = Eventual.done("bar")
            val eventual2 = eventual1.map(s => s.length)
            eventual2 shouldBe a [Eventual[Int]]
            eventual2.isFailure shouldBe false
            eventual2.isComplete shouldBe true
            eventual2.get shouldBe 3
        }

        "from failure to failure" in {
            val eventual1 = Eventual.failed(new IllegalArgumentException)
            val eventual2 = eventual1.mapLifted(failure => Failure(new java.io.IOException(failure.failed.get)))
            eventual2 shouldBe a [Eventual[String]]
            eventual2.isFailure shouldBe true
            eventual2.isComplete shouldBe true
            Eventual.unapply(eventual2) should matchPattern { case Some(Failure(_: java.io.IOException)) => }
        }

        "skip over function in failed non-T case" in {
            val eventual1 = Eventual.failed[String](new IllegalStateException)
            val eventual2: Eventual[Int] = eventual1.map(i => {
                fail("should not call this code block")
            })
            eventual2.isFailure shouldBe true
            eventual2.isComplete shouldBe true
            Eventual.unapply(eventual2) should matchPattern { case Some(Failure(_: IllegalStateException)) => }
        }
    }

    "Eventuals should be foreach-able" - {

        "when pending" in {
            val eventual = Eventual.pending[String]
            eventual.foreach(s => fail("should not call this code block"))
        }

        "when in failure state" in {
            val eventual = Eventual.failed[String](new Exception())
            eventual.foreach(s => fail("should not call this code block"))
        }

        "when value is calculated" in {
            val buffer = new ListBuffer[String]
            val eventual = Eventual.pending[String]
            eventual.foreach(s => fail("should not call this code block"))
            eventual.complete("foo")
            eventual.foreach(s => buffer += s)
            buffer.headOption shouldBe Some("foo")
        }
    }

    "Eventuals should support andThen -- fluent onComplete" - {

        "when pending" in {
            val buffer = new ListBuffer[Try[Int]]
            val eventual = Eventual.pending[Int]
            val ret = eventual.andThen[Int,Unit] {
                case t: Try[Int] => buffer += t
            }
            ret shouldBe a [Eventual[Int]]
            ret should not be 'complete
            eventual should not be 'complete
            buffer shouldBe 'empty
        }

        "when in failure state" in {
            val buffer = new ListBuffer[Try[Int]]
            val eventual = Eventual.failed[Int](new IllegalArgumentException())
            val ret = eventual.andThen[Int,Unit] {
                case t: Try[Int] => buffer += t
            }
            Seq(eventual, ret).foreach(e => {
                e shouldBe a [Eventual[Int]]
                e shouldBe 'complete
                e shouldBe 'failure
                e.asOption shouldBe None
            })
            buffer.headOption should matchPattern { case Some(Failure(_: IllegalArgumentException)) => }
        }

        "when value is calculated" in {
            val buffer = new ListBuffer[Try[Int]]
            val eventual = Eventual.done(2)
            val ret = eventual.andThen[Int,Unit] {
                case t: Try[Int] => buffer += t
            }
            Seq(eventual, ret).foreach(e => {
                e shouldBe a [Eventual[Int]]
                e shouldBe 'complete
                e should not be 'failure
                e.asOption shouldBe Some(2)
            })
            buffer.headOption should matchPattern { case Some(Success(2)) => }
        }

        "when pending, then failure state" in {
            val buffer = new ListBuffer[Try[Int]]
            val pending = Eventual.pending[Int]
            val eventual = pending.andThen[Int,Unit] {
                case t: Try[Int] => buffer += t
            }
            pending.fail(new IllegalArgumentException())
            eventual shouldEqual pending
            eventual shouldBe 'complete
            eventual shouldBe 'failure
            buffer.headOption should matchPattern { case Some(Failure(_: IllegalArgumentException)) => }
        }

        "when pending, then value is calculated" in {
            val buffer = new ListBuffer[Try[Int]]
            val pending = Eventual.pending[Int]
            val eventual = pending.andThen[Int,Unit] {
                case t: Try[Int] => buffer += t
            }
            pending.complete(5)
            eventual shouldEqual pending
            eventual shouldBe 'complete
            eventual should not be 'failure
            buffer.headOption should matchPattern { case Some(Success(5)) => }
        }
    }

    "Eventuals should be zippable" - {

        "when pending" in {
            val e1 = Eventual.pending[String]
            val e2 = Eventual.done(3)
            val e3 = e1.zip(e2)
            e3 shouldBe a [Eventual[(String,Int)]]
            e3.isComplete shouldBe false
        }

        "when in failure state" in {
            val e1 = Eventual.failed(new IllegalArgumentException)
            val e2 = Eventual.done(2)
            val e3 = e1.zip(e2)
            e3 shouldBe a [Eventual[(String,Int)]]
            e3.isFailure shouldBe true
            e3.failed.get shouldBe a [IllegalArgumentException]
        }

        "when value is calculated" in {
            val e1 = Eventual.done("foo")
            val e2 = Eventual.done(3)
            val e3 = e1.zip(e2)
            e3.hasValue shouldBe true
            e3.get shouldBe ("foo",3)
        }
    }

    "Eventuals should be filterable" - {

        "when pending" in {
            val eventual = Eventual.pending[Int].filter(_ > 3)
            eventual should not be 'complete
        }

        "when in failure state" in {
            val eventual = Eventual.failed[Int](new IllegalArgumentException).filter(_ > 3)
            eventual shouldBe 'failure
            eventual should matchPattern { case Eventual(Failure(_: IllegalArgumentException)) => }
        }

        "when value is calculated" - {

            "and filtered" in {
                val eventual = Eventual.done(5).filter(_ < 2)
                eventual should not be 'complete
            }

            "and not filtered" in {
                val eventual = Eventual.done(5).filter(_ > 2)
                eventual shouldBe 'complete
                eventual should not be 'failure
                eventual.get shouldBe 5
            }
        }
    }

    "Eventuals should support for-comprehensions" - {

        "when pending" in {
            val pending = Eventual.pending[String]
            val ret = for {
                s <- pending
            } yield s

            // runs through the for-comprehension but 's' is never defined.
            ret shouldBe a [Pending[String]]

            // Pending(1 queued handler) vs Pending(0 queued handler)
            ret should not be pending
        }

        "when in failure state" in {
            val eventual = Eventual.failed[String](new IllegalArgumentException)

            val ret = for {
                s <- eventual
            } yield s.length

            ret shouldBe a [Eventual[Int]]
            ret.failed.get shouldBe a [IllegalArgumentException]
        }

        "when value is calculated" in {
            val eventual = Eventual.done("quovo")
            val ret = for {
                s <- eventual
            } yield s.length

            ret shouldBe a [Eventual[Int]]
            ret.get shouldBe 5
        }

        "when value is calculated and filtered" in {
            val eventual = Eventual.done(8)
            val ret = for {
                i <- eventual if i > 10
            } yield i

            ret shouldBe a [Eventual[Int]]
            ret should not be 'complete
        }

        // FIXME: doesn't work!
        "when pending, then failed" ignore {
            val pending = Eventual.pending[String]
            val ret = for {
                s <- pending
            } yield {
                pending.complete("quovo")
                s
            }

            ret shouldBe a [Eventual[String]]
            println(ret)
        }
    }
}
