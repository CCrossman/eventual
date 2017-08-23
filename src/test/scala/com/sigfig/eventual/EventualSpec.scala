package com.sigfig.eventual

import org.scalatest.{FreeSpec, Matchers}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

class EventualSpec extends FreeSpec with Matchers {

    "Eventuals should be buildable from thunks" in {
        val eventual = Eventual[String]("foo")
        eventual shouldBe a [Eventual[String]]
        eventual.getOption shouldBe Some("foo")
    }

    // FIXME: fails in a nondeterministic way
    "Eventuals should be buildable from Futures" ignore {
        implicit val ec = ExecutionContext.Implicits.global
        val eventual = Eventual.fromFuture[String](Future[String]("bar"))
        eventual shouldBe a [Eventual[String]]
        eventual.isComplete shouldBe (true)
        eventual.isFailed shouldBe (false)
        eventual.getOption shouldBe Some("bar")
    }

    // FIXME: fails in a nondeterministic way
    "Eventuals should be complete when the underlying future is complete." ignore {
        val promise = Promise[String]()
        val eventual = Eventual.fromFuture(promise.future)
        promise.complete(Success("baz"))
        eventual.isFailed should be (false)
        eventual.isComplete should be (true)
        eventual.getOption should be (Some("baz"))
    }

    "Eventuals should be incomplete when the underlying future is pending." in {
        val promise = Promise[String]()
        val eventual = Eventual.fromFuture(promise.future)
        eventual.isFailed should be(false)
        eventual.isComplete should be(false)
        eventual.getOption should be(None)
    }

    "Eventuals should be pattern-matchable" - {
        "as Done" in {
            val eventual = Eventual.done(1)
            eventual shouldBe a [Done[Int]]
            eventual shouldBe a [Eventual[Int]]
            eventual.getOption shouldBe Some(1)
            eventual should matchPattern { case Done(1) => }
            eventual should matchPattern { case Eventual(Success(1)) => }
        }

        "as Failed" in {
            val ex = new Exception("foo")
            val eventual = Eventual.failed[Int](ex)
            eventual shouldBe a [Failed[Int]]
            eventual shouldBe a [Eventual[Int]]
            eventual.getOption shouldBe None
            eventual should matchPattern { case Failed(e) => }
            eventual should matchPattern { case Eventual(Failure(e)) => }
        }

        "as Never" in {
            val eventual = Eventual.never
            eventual shouldBe a [Never.type]
            eventual shouldBe a [Eventual[Nothing]]
            eventual.getOption shouldBe None
            eventual should matchPattern { case Never => }
            eventual should not matchPattern { case Eventual(_: Try[Nothing]) => }
        }

        "as Pending" - {
            "when successful" in {
                val eventual = Eventual.pending[String]
                eventual shouldBe a[Pending[String]]
                eventual shouldBe a[Eventual[String]]
                eventual.getOption shouldBe None
                eventual should matchPattern { case Pending(None) => }
                eventual should not matchPattern { case Eventual(_: Try[Nothing]) => }

                eventual.complete("quovo")
                eventual.getOption shouldBe Some("quovo")
                eventual should matchPattern { case Pending(Some(Success("quovo"))) => }
                eventual should matchPattern { case Eventual(Success("quovo")) => }
            }
            "when failed" in {
                val eventual = Eventual.pending[String]
                val ex = new Exception("quovo")
                eventual.fail(ex)
                eventual.getOption shouldBe None
                eventual should matchPattern { case Pending(Some(Failure(e))) => }
                eventual should matchPattern { case Eventual(Failure(e)) => }
            }
        }

        "as Lazy" in {
            val eventual = Eventual.lazily[Int](1+2)
            eventual shouldBe a [Lazy[Int]]
            eventual shouldBe a [Eventual[Int]]
            eventual.getOption shouldBe Some(3)
        }
    }
}
