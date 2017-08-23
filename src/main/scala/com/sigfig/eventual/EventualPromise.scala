package com.sigfig.eventual

import scala.concurrent.Promise
import scala.util.{Failure, Success, Try}

sealed trait EventualPromise[T] {

    /**
      * @return - the Scala Promise best representing this EventualPromise
      */
    def asScala: Promise[T]

    /** Returns whether the promise has already been completed with
      *  a value or an exception.
      *
      *  $nonDeterministic
      *
      *  @return    `true` if the promise is already completed, `false` otherwise
      */
    def isCompleted: Boolean

    /** Completes the promise with either an exception or a value.
      *
      *  @param result     Either the value or the exception to complete the promise with.
      *
      *  $promiseCompletion
      */
    def complete(result: Try[T]): this.type =
        if (tryComplete(result)) this else throw new IllegalStateException("Promise already completed.")

    /** Tries to complete the promise with either a value or the exception.
      *
      *  $nonDeterministic
      *
      *  @return    If the promise has already been completed returns `false`, or `true` otherwise.
      */
    def tryComplete(result: Try[T]): Boolean

    /** Completes this promise with the specified future, once that future is completed.
      *
      *  @return   This promise
      */
    final def completeWith(other: Eventual[T]): this.type = tryCompleteWith(other)

    /** Attempts to complete this promise with the specified future, once that future is completed.
      *
      *  @return   This promise
      */
    def tryCompleteWith(other: Eventual[T]): this.type

    /** Completes the promise with a value.
      *
      *  @param value The value to complete the promise with.
      *
      *  $promiseCompletion
      */
    def success(@deprecatedName('v) value: T): this.type = complete(Success(value))

    /** Tries to complete the promise with a value.
      *
      *  $nonDeterministic
      *
      *  @return    If the promise has already been completed returns `false`, or `true` otherwise.
      */
    def trySuccess(value: T): Boolean = tryComplete(Success(value))

    /** Completes the promise with an exception.
      *
      *  @param cause    The throwable to complete the promise with.
      *
      *  $allowedThrowables
      *
      *  $promiseCompletion
      */
    def failure(@deprecatedName('t) cause: Throwable): this.type = complete(Failure(cause))

    /** Tries to complete the promise with an exception.
      *
      *  $nonDeterministic
      *
      *  @return    If the promise has already been completed returns `false`, or `true` otherwise.
      */
    def tryFailure(@deprecatedName('t) cause: Throwable): Boolean = tryComplete(Failure(cause))
}

object EventualPromise {
    /** Creates a promise object which can be completed with a value.
      *
      *  @tparam T       the type of the value in the promise
      *  @return         the newly created `Promise` object
      */
    //def apply[T](): Promise[T] = new impl.Promise.DefaultPromise[T]()
    def apply[T](): EventualPromise[T] = ???

    /** Creates an already completed Promise with the specified result or exception.
      *
      *  @tparam T       the type of the value in the promise
      *  @return         the newly created `Promise` object
      */
    def fromTry[T](result: Try[T]): EventualPromise[T] = ???

    /** Creates an already completed Promise with the specified exception.
      *
      *  @tparam T       the type of the value in the promise
      *  @return         the newly created `Promise` object
      */
    def failed[T](exception: Throwable): EventualPromise[T] = fromTry(Failure(exception))

    /** Creates an already completed Promise with the specified result.
      *
      *  @tparam T       the type of the value in the promise
      *  @return         the newly created `Promise` object
      */
    def successful[T](result: T): EventualPromise[T] = fromTry(Success(result))
}