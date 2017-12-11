package com.tribbloids.spookystuff.utils

import org.apache.spark.ml.dsl.utils.FlowUtils
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try}

/**
  * Created by peng on 18/09/16.
  */
class Bypassing {

  def apply(e: Throwable) = {
    e match {
      case ee: ExceptionWrapper => ee
      case _ => new ExceptionWrapper(e)
    }
  }

  class ExceptionWrapper(cause: Throwable) extends RuntimeException("Bypassing: " + this.getClass.getSimpleName, cause)

  def mapException[T](f: =>T): T ={
    try {
      f
    }
    catch {
      case e: Throwable => throw apply(e)
    }
  }
}

case object NoRetry extends Bypassing
case object SilentRetry extends Bypassing

object RetryFixedInterval {

  def apply(
             n: Int,
             interval: Long = 0L,
             silent: Boolean = false,
             callerStr: String = null
           ) = Retry(n, {_ => interval}, silent, callerStr)
}

object RetryExponentialBackoff {

  def apply(
             n: Int,
             longestInterval: Long = 0L,
             silent: Boolean = false,
             callerStr: String = null
           ) = Retry(n,
    {
      n =>
        (longestInterval / Math.pow(2, n)).asInstanceOf[Long]
    },
    silent, callerStr)
}

case class Retry(
                  n: Int,
                  intervalFactory: Int => Long = {_ => 0L},
                  silent: Boolean = false,
                  callerStr: String = null
                ) {

  def apply[T](fn: =>T) = {

    new RetryImpl[T](fn).get(this)
  }
}

class RetryImpl[T](
                    fn: =>T
                  ) {

  @annotation.tailrec
  final def get(
                 retry: Retry
               ): T = {

    import retry._

    var _callerStr = callerStr
    if (callerStr == null)
      _callerStr = FlowUtils.callerShowStr()
    val interval = intervalFactory(n)
    Try { fn } match {
      case Success(x) =>
        x
      case Failure(e: NoRetry.ExceptionWrapper) =>
        throw e.getCause
      case Failure(e) if n > 1 =>
        if (!(silent || e.isInstanceOf[SilentRetry.ExceptionWrapper])) {
          val logger = LoggerFactory.getLogger(this.getClass)
          logger.warn(
            s"Retrying locally on ${e.getClass.getSimpleName} in ${interval.toDouble/1000} second(s)... ${n-1} time(s) left" +
              "\t@ " + _callerStr +
              "\n" + e.getMessage
          )
          logger.debug("\t\\-->", e)
        }
        Thread.sleep(interval)
        get(retry.copy(n = n - 1))
      case Failure(e) =>
        throw e
    }
  }


  def map[T2](g: Try[T] => T2): RetryImpl[T2] = {

    val effectiveG: (Try[T]) => T2 = {
      case Failure(ee: NoRetry.ExceptionWrapper) =>
        NoRetry.mapException {
          g(Failure[T](ee.getCause))
        }
      case v =>
        g(v)
    }

    new RetryImpl[T2](
      effectiveG(Try{fn})
    )
  }

  def mapSuccess[T2](g: T => T2): RetryImpl[T2] = {
    val effectiveG: (Try[T]) => T2 = {
      case Success(v) => g(v)
      case Failure(ee) => throw ee
    }

    map(effectiveG)
  }
}