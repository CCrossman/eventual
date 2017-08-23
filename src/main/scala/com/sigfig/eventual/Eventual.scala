package com.sigfig.eventual

import scala.concurrent.duration.Duration
import scala.concurrent._
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
sealed trait Eventual[+T] extends Awaitable[T] {

    /**
      * @return - true if this instance has calculated a value OR entered a failure state.
      */
    def isComplete: Boolean = hasValue || isFailed

    /**
      * @return - true if this instance has entered a failure state.
      */
    def isFailed: Boolean = getValueOrFailure.exists(_.isFailure)

    /**
      * @return - true if this instance has calculated a value.
      */
    def hasValue: Boolean = getValueOrFailure.exists(_.isSuccess)

    /**
      * @return - None if this instance has not calculated a value or entered a failure state.
      *           Some(T) otherwise.
      */
    def getOption: Option[T] = getValueOrFailure.flatMap(t => if (t.isSuccess) Some(t.get) else None)

    /**
      * @return - Some(Success(T)) if a value was calculated.
      *         - Some(Failure(Throwable)) if entered a failure state.
      *         - None otherwise
      */
    def getValueOrFailure: Option[Try[T]]

    /**
      * blocks on calculated of the value.
      * @throws NoSuchElementException if entered a failure state.
      * @return - a T instance
      */
    @throws[NoSuchElementException]
    def get: T = getValueOrFailure match {
        case Some(Success(t)) => t
        case Some(Failure(t)) => throw t
        case None => throw new NoSuchElementException
    }

    /**
      * @return - true if this instance has not calculated a value AND has not entered a failure state.
      */
    def notComplete: Boolean = !isComplete

    /**
      * @return - true if this instance has not entered a failure state.
      */
    def notFailed: Boolean = !isFailed
}

/**
  * Represents a fully calculated value. No asynchronous behavior.
  */
final case class Done[+A](a: A) extends Eventual[A] {
    override val isFailed: Boolean = false
    override val hasValue: Boolean = true
    override val getValueOrFailure: Option[Try[A]] = Some(Success(a))

    override def ready(atMost: Duration)(implicit permit: CanAwait): Done.this.type = this
    override def result(atMost: Duration)(implicit permit: CanAwait): A = get
}

/**
  * Represents a failed task. No asynchronous behavior.
  */
final case class Failed[+A](problem: Throwable) extends Eventual[A] {
    override val isFailed: Boolean = true
    override val hasValue: Boolean = false
    override val getValueOrFailure: Option[Try[A]] = Some(Failure(problem))

    override def ready(atMost: Duration)(implicit permit: CanAwait): Failed.this.type = this
    override def result(atMost: Duration)(implicit permit: CanAwait): A = get
}

/**
  * Represents a task that never completes on its own.
  */
case object Never extends Eventual[Nothing] {
    override val isFailed: Boolean = false
    override val hasValue: Boolean = false
    override val getValueOrFailure: Option[Try[Nothing]] = None

    override def ready(atMost: Duration)(implicit permit: CanAwait): Never.type = this
    override def result(atMost: Duration)(implicit permit: CanAwait): Nothing = get
}

/**
  * Represents a task that is not yet calculated. Must resolve manually.
  */
final case class Pending[A](private var value: Option[Try[A]] = None) extends Eventual[A] {
    def complete(a: A): Unit = completeWith(Success(a))
    def fail(t: Throwable): Unit = completeWith(Failure(t))

    def completeWith(tr: Try[A]): Unit = {
        this.synchronized {
            if (value.isDefined) {
                throw new IllegalStateException
            }
            value = Option(tr)
        }
    }
    override def getValueOrFailure: Option[Try[A]] = value
    override def ready(atMost: Duration)(implicit permit: CanAwait): Pending.this.type = this
    override def result(atMost: Duration)(implicit permit: CanAwait): A = get
}

/**
  * Represents a value that is lazily resolved.
  */
final class Lazy[+A](f: => A) extends Eventual[A] {
    private lazy val value: Option[Try[A]] = Option(Try(f))

    override def getValueOrFailure: Option[Try[A]] = value
    override def ready(atMost: Duration)(implicit permit: CanAwait): Lazy.this.type = this
    override def result(atMost: Duration)(implicit permit: CanAwait): A = get
}

/**
  *
  */
object Eventual {
    def apply[A](f: => A): Eventual[A] = new Lazy[A](f)
    def unapply[A](e: Eventual[A]): Option[Try[A]] = Option(e).flatMap(_.getValueOrFailure)

    def fromFuture[A](f: Future[A])(implicit ec: ExecutionContext = ExecutionContext.Implicits.global): Eventual[A] = {
        val eventual = new Pending[A]
        f.onComplete {
            t: Try[A] => eventual.completeWith(t)
        }
        eventual
    }

    def done[A](a: A): Done[A] = Done(a)

    def failed[A](t: Throwable): Failed[A] = Failed(t)

    def lazily[A](f: => A): Lazy[A] = new Lazy(f)

    def never: Never.type = Never

    def pending[A]: Pending[A] = new Pending[A]
}