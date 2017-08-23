package com.sigfig.eventual

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent._
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
  * An {{{Eventual}}} instance is a value that is known and fixed,
  * or the value is going to be calculated at a later date, or the
  * latter will be attempted and failed. This means that {{{Eventual}}}
  * has three states represented in a monadic way.
  *
  * Unlike {{{Future}}}, an {{{Eventual}}} does not "fail hard" when
  * it enters a failure state. Instead, it must be processed differently.
  * However, if someone attempts to extract a value on a failed {{{Eventual}}},
  * in an unsafe way, it will "fail hard".
  */
sealed trait Eventual[+T] {

    /**
      * @return - true if this instance has calculated a value OR entered a failure state.
      */
    def isComplete: Boolean = hasValue || isFailure

    /**
      * @return - true if this instance has entered a failure state.
      */
    def isFailure: Boolean

    /**
      * @return - true if this instance has calculated a value.
      */
    def hasValue: Boolean

    /**
      * @return - a calculated value of T
      */
    def get: T

    /**
      * @return - Some(Success(T)) if a value is calculated
      *           Some(Failure(T)) if entered a failure state.
      *           None otherwise.
      */
    def asOptionTry: Option[Try[T]]

    /**
      * @return - Some(T) if value was calculated.
      *           None otherwise.
      */
    def asOption: Option[T] = asOptionTry.flatMap(_.toOption)

    /**
      * yield to the code block. Does NOT resolve the Eventual.
      */
    def foreach[U](f: T => U): Unit = asOption.foreach(f)

    /**
      * yield to the code block when Eventual completes.
      */
    def andThen[U >: T,V](pf: PartialFunction[Try[U],Unit])(implicit blk: Throwable => V = null): this.type = {
        onComplete {
            case t: Try[T] =>
                try {
                    pf(t)
                } catch {
                    case NonFatal(ex) =>
                        if (blk == null) {
                            ex.printStackTrace()
                        } else {
                            blk(ex)
                        }
                }
        }
        this
    }

    /**
      * @return - true if this instance has not calculated a value.
      */
    def hasNoValue: Boolean = !hasValue

    /**
      * @return - true if this instance has not calculated a value AND has not entered a failure state.
      */
    def notComplete: Boolean = !isComplete

    /**
      * @return - true if this instance has not entered a failure state.
      */
    def notFailure: Boolean = !isFailure

    /**
      * a Future-like listener register for completion
      * @param pf - the listener function
      */
    def onComplete(pf: PartialFunction[Try[T],Unit]): Unit

    /**
      * flips the success/failed state.
      */
    def failed: Eventual[Throwable] = {
        val pending = Eventual.pending[Throwable]
        onComplete {
            case Failure(a) => pending.complete(a)
            case Success(t) => pending.fail(new IllegalStateException(s"expected failure, received '$t'"))
        }
        pending
    }

    /**
      * lifts {{{Eventual}}} content into the {{{Try}}} monad.
      */
    def lift: Eventual[Try[T]] = {
        val pending = Eventual.pending[Try[T]]
        onComplete {
            case t: Try[T] => pending.complete(t)
        }
        pending
    }

    /**
      * unlifts {{{Eventual}}} content from the {{{Try}}} monad.
      */
    def unlift[U](implicit ev: T <:< Try[U]): Eventual[U] = {
        val pending = Eventual.pending[U]
        onComplete {
            case t: Try[T] =>
                val u: Try[U] = t.map(ev(_)).flatten
                pending.completeWith(u)
        }
        pending
    }

    /**
      * filter-and-maps the {{{Eventual}}}. a "filtered" instance will never complete, a la {{{Never}}}.
      */
    def collect[B](pf: PartialFunction[T,B]): Eventual[B] = {
        val pending = Eventual.pending[B]
        onComplete {
            case Success(t) if pf.isDefinedAt(t) => pending.complete(pf.apply(t))
            case Failure(e) => pending.fail(e)
        }
        pending
    }

    /**
      * filter the {{{Eventual}}}. a "filtered" instance will never complete, a la {{{Never}}}.
      */
    def filter(p: T => Boolean): Eventual[T] = withFilter(p)

    /**
      * flat-maps over the successful value if it happens.
      * otherwise, propogates the error downstream. More
      * closely emulates the Future::flatMap behavior.
      */
    def flatMap[B](f: T => Eventual[B]): Eventual[B] = {
        val pending = Eventual.pending[B]
        onComplete {
            case Success(t) => f(t).onComplete {
                case t: Try[B] => pending.completeWith(t)
            }
            case Failure(e) => pending.fail(e)
        }
        pending
    }

    /**
      * maps over a successful value if it happens.
      * otherwise, propogates the error downstream.
      */
    def map[B](f: T => B): Eventual[B] = {
        val pending = Eventual.pending[B]
        onComplete {
            case t: Try[T] => pending.completeWith(t.map(f))
        }
        pending
    }

    /**
      *
      */
    def flatten[B](implicit ev: T <:< Eventual[B]): Eventual[B] = flatMap[B](ev)

    /**
      * shorthand for mapping over the {{{Try}}} version of these Eventuals.
      */
    def mapLifted[B](f: Try[T] => Try[B]): Eventual[B] = lift.map(f).unlift

    /**
      * shorthand for mapping over the {{{Try}}} version of these Eventuals.
      */
    def flatMapLifted[B](f: Try[T] => Eventual[B]): Eventual[B] = lift.flatMap(f)

    /**
      * shorthand for mapping over failed attempts
      */
    def recover[U >: T](pf: PartialFunction[Throwable,U]): Eventual[U] = mapLifted[U] {
        case s @ Success(_) => s
        case Failure(e) => Success(pf(e))
    }

    /**
      * shorthand for mapping over failed attempts
      */
    def recoverWith[U >: T](pf: PartialFunction[Throwable,Eventual[U]]): Eventual[U] = flatMapLifted[U] {
        case Success(t) => Eventual.done(t)
        case Failure(e) => pf(e)
    }

    /**
      * used with for-comprehensions
      */
    final def withFilter(p: T => Boolean): Eventual[T] = {
        val pending = Eventual.pending[T]
        onComplete {
            case Success(t) if p(t) => pending.complete(t)
            case Success(_) => /* NOOP */
            case Failure(e) => pending.fail(e)
        }
        pending
    }

    /**
      * combines two {{{Eventual}}}s into one.
      */
    def zip[U](other: Eventual[U]): Eventual[(T,U)] = {
        val pending = Eventual.pending[(T,U)]

        for {
            tryT <- this.lift
            tryU <- other.lift
        } yield {
            (tryT,tryU) match {
                case (Success(t),Success(u)) => pending.complete((t,u))
                case (Failure(e),_) => pending.fail(e)
                case (_,Failure(e)) => pending.fail(e)
            }
        }

        pending
    }

    /**
      * combines and maps two {{{Eventual}}}s into one.
      */
    def zipWith[U,R](other: Eventual[U])(f: (T,U) => R): Eventual[R] = zip(other).flatMapLifted {
        case Success((t,u)) => Eventual.done(f(t,u))
        case Failure(e) => Eventual.failed(e)
    }
}

/**
  * Represents a fully calculated value. No asynchronous behavior.
  */
final case class Done[+A](a: A) extends Eventual[A] {
    override val isFailure: Boolean = false
    override val hasValue: Boolean = true
    override def onComplete(pf: PartialFunction[Try[A], Unit]): Unit = pf(Success(a))
    override val get: A = a
    override val asOptionTry: Option[Try[A]] = Some(Success(a))
}

/**
  * Represents a failed task. No asynchronous behavior.
  */
final case class Failed[+A](problem: Throwable) extends Eventual[A] {
    override val isFailure: Boolean = true
    override val hasValue: Boolean = false
    override def onComplete(pf: PartialFunction[Try[A], Unit]): Unit = pf(Failure(problem))
    override def get: A = throw problem
    override val asOptionTry: Option[Try[A]] = Some(Failure(problem))
}

/**
  * Represents a task that is not yet calculated. Must resolve manually, like a {{{Promise}}}.
  */
final class Pending[A] extends Eventual[A] {
    private var value: Option[Try[A]] = None
    private val completionQueue = new mutable.Queue[PartialFunction[Try[A],Unit]]()

    def complete(a: A): Unit = completeWith(Success(a))
    def fail(t: Throwable): Unit = completeWith(Failure(t))

    def completeWith(tr: Try[A]): Unit = {
        this.synchronized {
            if (value.isDefined) {
                throw new IllegalStateException(s"completeWith($tr) when value is $value")
            }
            value = Option(tr)

            // drain completion queue
            completionQueue.dequeueAll(_ => true).foreach(pf => pf(tr))
        }
    }

    override def onComplete(pf: PartialFunction[Try[A], Unit]): Unit = {
        this.synchronized {
            if (value.isDefined) {
                pf(value.get)
            } else {
                completionQueue.enqueue(pf)
            }
        }
    }

    override def isFailure: Boolean = value.exists(_.isFailure)
    override def hasValue: Boolean = value.exists(_.isSuccess)
    override def asOptionTry: Option[Try[A]] = value

    override def get: A = {
        asOptionTry match {
            case Some(Success(a)) => a
            case Some(Failure(e)) => throw e
            case None => throw new NoSuchElementException
        }
    }

    override def toString = s"Pending(state=$value, completionQueue of ${completionQueue.length} values)"
}

object Pending {
    def unapply[A](p: Pending[A]): Option[Option[Try[A]]] = Option(p).map(_.value)
}

/**
  * An {{{Eventual}}} that never completes.
  */
case object Never extends Eventual[Nothing] {
    override val isFailure: Boolean = false
    override val hasValue: Boolean = false
    override def get: Nothing = throw new NoSuchElementException
    override val asOptionTry: Option[Try[Nothing]] = None
    override def onComplete(pf: PartialFunction[Try[Nothing], Unit]): Unit = ()
}

/**
  * An {{{Eventual}}} that completes immediately but has no useful value.
  * Mostly for starting fluent chains.
  */
case object Unit extends Eventual[Unit] {
    override val isFailure: Boolean = false
    override val hasValue: Boolean = true
    override val get: Unit = ()
    override val asOptionTry: Option[Try[Unit]] = Some(Success(Unit))
    override def onComplete(pf: PartialFunction[Try[Unit], Unit]): Unit = pf(Success(Unit))
}

/**
  * Represents a value that is lazily resolved.
  */
private[eventual] final class Lazy[A](f: => A) extends Eventual[A] {
    private val delegate: Pending[A] = new Pending[A]

    def loaded: Boolean = delegate.isComplete

    override def isFailure: Boolean = delegate.isFailure
    override def hasValue: Boolean = delegate.hasValue

    override def get: A = {
        if (delegate.hasNoValue) {
            delegate.completeWith(Try(f))
        }
        delegate.get
    }
    override def asOptionTry: Option[Try[A]] = delegate.asOptionTry
    override def onComplete(pf: PartialFunction[Try[A], Unit]): Unit = delegate.onComplete(pf)
}

/**
  *
  */
object Eventual {
    /**
      * @return a fresh {{{Eventual}}} instance.
      */
    def apply[A]: Eventual[A] = Never

    /**
      * @return a pattern-matching over the {{{Eventual}}}
      */
    def unapply[A](e: Eventual[A]): Option[Try[A]] = Option(e).flatMap(_.asOptionTry)

    /**
      * @return an instantly completed {{{Eventual}}}
      */
    def unit: Eventual[Unit] = Unit

    /**
      * @return an {{{Eventual}}} that completes when the {{{Future}}} does. Waits for the duration beforehand.
      */
    def fromFuture[A](f: Future[A], atMost: Duration)(implicit canAwait: CanAwait, ec: ExecutionContext): Eventual[A] = {
        fromFuture(Await.ready(f,atMost))
    }

    /**
      * @return an {{{Eventual}}} that completes when the {{{Future}}} does.
      */
    def fromFuture[A](f: Future[A])(implicit ec: ExecutionContext = ExecutionContext.Implicits.global): Eventual[A] = {
        val eventual = pending[A]
        f.onComplete {
            t: Try[A] => eventual.completeWith(t)
        }
        eventual
    }

    /**
      * @return an {{{Eventual}}} built from the given {{{Try}}}
      */
    def fromTry[A](ta: Try[A]): Eventual[A] = {
        val eventual = pending[A]
        eventual.completeWith(ta)
        eventual
    }

    /**
      * @return an instantly (successfully) completed {{{Eventual}}}.
      */
    def done[A](a: A): Done[A] = Done(a)

    /**
      * @return an instantly (failed) completed {{{Eventual}}}.
      */
    def failed[A](t: Throwable): Failed[A] = Failed(t)

    /**
      * @return an {{{Eventual}}} that completes as soon as the value is accessed.
      */
    def lazily[A](f: => A): Lazy[A] = new Lazy(f)

    /**
      * @return an {{{Eventual}}} that must be resolved manually, like a {{{Promise}}}.
      */
    def pending[A]: Pending[A] = new Pending[A]
}