package arrow.effects

import arrow.core.*
import arrow.effects.internal.Platform.ArrayStack
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine

private typealias Current = BIOOf<Any?, Any?>
private typealias BindF = (Any?) -> BIO<Any?, Any?>
private typealias CallStack = ArrayStack<BindF>
private typealias Callback = (Either<Throwable, Any?>) -> Unit

@Suppress("UNCHECKED_CAST", "ReturnCount", "ComplexMethod")
internal object IORunLoop {

  fun <E, A> start(source: BIOOf<E, A>, cb: (Either<E, A>) -> Unit): Unit =
    loop(source, IOConnection.uncancelable, cb as Callback, null, null, null)

  /**
   * Evaluates the given `IO` reference, calling the given callback
   * with the result when completed.
   */
  fun <A> startCancelable(source: IOOf<A>, conn: IOConnection, cb: (Either<Throwable, A>) -> Unit): Unit =
    loop(source, conn, cb as Callback, null, null, null)

  fun <E, A> step(source: BIO<E, A>): BIO<E, A> {
    var currentIO: Current? = source
    var bFirst: BindF? = null
    var bRest: CallStack? = null
    var hasResult: Boolean = false
    var result: Any? = null

    do {
      when (currentIO) {
        is BIO.Pure<*, *> -> {
          result = currentIO.a
          hasResult = true
        }
        is BIO.RaiseError<*> -> {
          val errorHandler: IOFrame<Any?, BIO<Any?, Any?>>? = findErrorHandlerInCallStack(bFirst, bRest)
          when (errorHandler) {
            // Return case for unhandled errors
            null -> return currentIO
            else -> {
              val exception: Throwable = currentIO.exception
              currentIO = executeSafe { errorHandler.recover(exception) }
              bFirst = null
            }
          }
        }
        is BIO.Suspend<*, *> -> {
          val thunk: () -> BIOOf<Any?, Any?> = currentIO.thunk
          currentIO = executeSafe(currentIO.fe) { thunk() }
        }
        is BIO.Delay<*, *> -> {
          val recover = currentIO.fe
          try {
            result = currentIO.thunk()
            hasResult = true
            currentIO = null
          } catch (t: Throwable) {
            currentIO = BIO.RaiseError(recover(t))
          }
        }
        is BIO.Async<*, *> -> {
          // Return case for Async operations
          return suspendInAsync(currentIO as BIO<E, A>, bFirst, bRest, currentIO.k)
        }
        is BIO.Bind<*, *, *> -> {
          if (bFirst != null) {
            if (bRest == null) bRest = ArrayStack()
            bRest.push(bFirst)
          }
          bFirst = currentIO.g as BindF
          currentIO = currentIO.cont
        }
        is BIO.ContinueOn<*, *> -> {
          if (bFirst != null) {
            if (bRest == null) bRest = ArrayStack()
            bRest.push(bFirst)
          }
          val localCurrent = currentIO

          val currentCC = localCurrent.cc

          val localCont = currentIO.cont

          bFirst = { c: Any? -> IO.just(c) }

          currentIO = BIO.async { conn, cc ->
            loop(localCont, conn, cc.asyncCallback(currentCC), null, null, null)
          }
        }
        is BIO.Map<*, *, *> -> {
          if (bFirst != null) {
            if (bRest == null) {
              bRest = ArrayStack()
            }
            bRest.push(bFirst)
          }
          bFirst = currentIO as BindF
          currentIO = currentIO.source
        }
        null -> {
          currentIO = BIO.RaiseError(NullPointerException("Stepping on null IO"))
        }
      }

      if (hasResult) {

        val nextBind: BindF? = popNextBind(bFirst, bRest)

        // Return case when no there are no more binds left
        if (nextBind == null) {
          return sanitizedCurrentIO(currentIO, result)
        } else {
          currentIO = executeSafe { nextBind(result) }
          hasResult = false
          result = null
          bFirst = null
        }
      }

    } while (true)
  }

  private fun <E, A> sanitizedCurrentIO(currentIO: Current?, unboxed: Any?): BIO<E, A> =
    (currentIO ?: BIO.Pure<E, Any?>(unboxed)) as BIO<E, A>

  private fun <E, A> suspendInAsync(
    currentIO: BIO<E, A>,
    bFirst: BindF?,
    bRest: CallStack?,
    register: IOProc<Any?>): BIO<E, A> =
  // Hitting an async boundary means we have to stop, however
  // if we had previous `flatMap` operations then we need to resume
  // the loop with the collected stack
    when {
      bFirst != null || (bRest != null && bRest.isNotEmpty()) ->
        BIO.Async { conn, cb ->
          val rcb = RestartCallback(conn, cb as Callback)
          rcb.prepare(bFirst, bRest)
          register(conn, rcb)
        }
      else -> currentIO
    }

  private fun loop(
    source: Current,
    cancelable: IOConnection,
    cb: (Either<Throwable, Any?>) -> Unit,
    rcbRef: RestartCallback?,
    bFirstRef: BindF?,
    bRestRef: CallStack?): Unit {
    var currentIO: Current? = source
    var conn: IOConnection = cancelable
    var bFirst: BindF? = bFirstRef
    var bRest: CallStack? = bRestRef
    var rcb: RestartCallback? = rcbRef
    // Values from Pure and Delay are unboxed in this var,
    // for code reuse between Pure and Delay
    var hasResult: Boolean = false
    var result: Any? = null

    do {
      if (conn.isCanceled()) {
        cb(Left(OnCancel.CancellationException))
        return
      }
      when (currentIO) {
        is BIO.Pure<*, *> -> {
          result = currentIO.a
          hasResult = true
        }
        is BIO.RaiseError<*> -> {
          val errorHandler: IOFrame<Any?, BIO<Any?, Any?>>? = findErrorHandlerInCallStack(bFirst, bRest)
          when (errorHandler) {
            // Return case for unhandled errors
            null -> {
              cb(Left(currentIO.exception))
              return
            }
            else -> {
              val exception: Throwable = currentIO.exception
              currentIO = executeSafe { errorHandler.recover(exception) }
              bFirst = null
            }
          }
        }
        is BIO.Suspend<*, *> -> {
          val thunk: () -> BIOOf<Any?, Any?> = currentIO.thunk
          currentIO = executeSafe(currentIO.fe) { thunk() }
        }
        is BIO.Delay<*, *> -> {
          val recover = currentIO.fe
          try {
            result = currentIO.thunk()
            hasResult = true
            currentIO = null
          } catch (t: Throwable) {
            currentIO = BIO.RaiseError(recover(t))
          }
        }
        is BIO.Async<*, *> -> {
          if (rcb == null) {
            rcb = RestartCallback(conn, cb)
          }
          rcb.prepare(bFirst, bRest)
          // Return case for Async operations
          currentIO.k(conn, rcb)
          return
        }
        is BIO.Bind<*, *, *> -> {
          if (bFirst != null) {
            if (bRest == null) bRest = ArrayStack()
            bRest.push(bFirst)
          }
          bFirst = currentIO.g as BindF
          currentIO = currentIO.cont
        }
        is BIO.ContinueOn<*, *> -> {
          if (bFirst != null) {
            if (bRest == null) bRest = ArrayStack()
            bRest.push(bFirst)
          }
          val localCurrent = currentIO

          val currentCC = localCurrent.cc

          val localCont = currentIO.cont

          bFirst = { c: Any? -> IO.just(c) }

          currentIO = BIO.async { _, callback ->
            loop(localCont, conn, callback.asyncCallback(currentCC), null, null, null)
          }
        }
        is BIO.Map<*, *, *> -> {
          if (bFirst != null) {
            if (bRest == null) {
              bRest = ArrayStack()
            }
            bRest.push(bFirst)
          }
          bFirst = currentIO as BindF
          currentIO = currentIO.source
        }
        is BIO.ContextSwitch<*, *> -> {
          val next = currentIO.source
          val modify = currentIO.modify
          val restore = currentIO.restore

          val old = conn
          conn = modify(old)
          currentIO = next
          if (conn != old) {
            rcb?.contextSwitch(conn)
            if (restore != null)
              currentIO = BIO.Bind(next, RestoreContext(old, restore))
          }
        }
        null -> {
          currentIO = BIO.RaiseError(NullPointerException("Looping on null IO"))
        }
      }

      if (hasResult) {

        val nextBind: BindF? = popNextBind(bFirst, bRest)

        // Return case when no there are no more binds left
        if (nextBind == null) {
          cb(Right(result))
          return
        } else {
          currentIO = executeSafe { nextBind(result) }
          hasResult = false
          result = null
          bFirst = null
        }
      }

    } while (true)
  }

  private inline fun executeSafe(mapError: (Throwable) -> (Any?) = ::identity, crossinline f: () -> BIOOf<Any?, Any?>): BIO<Any?, Any?> =
    try {
      f().fix()
    } catch (e: Throwable) {
      BIO.RaiseError(mapError(e))
    }

  /**
   * Pops the next bind function from the stack, but filters out
   * `IOFrame.ErrorHandler` references, because we know they won't do
   * anything — an optimization for `handleError`.
   */
  private fun popNextBind(bFirst: BindF?, bRest: CallStack?): BindF? =
    if ((bFirst != null) && bFirst !is IOFrame.Companion.ErrorHandler)
      bFirst
    else if (bRest != null) {
      var cursor: BindF? = null
      while (cursor == null && bRest.isNotEmpty()) {
        val ref = bRest.pop()
        if (ref !is IOFrame.Companion.ErrorHandler) cursor = ref
      }
      cursor
    } else {
      null
    }

  private fun findErrorHandlerInCallStack(bFirst: BindF?, bRest: CallStack?): IOFrame<Any?, BIO<Any?, Any?>>? {
    if (bFirst != null && bFirst is IOFrame) {
      return bFirst
    } else if (bRest == null) {
      return null
    }

    var result: IOFrame<Any?, BIO<Any?, Any?>>? = null
    var cursor: BindF? = bFirst

    @Suppress("LoopWithTooManyJumpStatements")
    do {
      if (cursor != null && cursor is IOFrame) {
        result = cursor
        break
      } else {
        cursor = if (bRest.isNotEmpty()) {
          bRest.pop()
        } else {
          break
        }
      }
    } while (true)
    return result
  }

  /**
   * A `RestartCallback` gets created only once, per [startCancelable] (`unsafeRunAsync`) invocation, once an `Async`
   * state is hit, its job being to resume the loop after the boundary, but with the bind call-stack restored.
   */
  private data class RestartCallback(val connInit: IOConnection, val cb: Callback) : Callback {

    private var conn: IOConnection = connInit
    private var canCall = false
    private var bFirst: BindF? = null
    private var bRest: CallStack? = null

    fun contextSwitch(conn: IOConnection): Unit {
      this.conn = conn
    }

    fun prepare(bFirst: BindF?, bRest: CallStack?): Unit {
      canCall = true
      this.bFirst = bFirst
      this.bRest = bRest
    }

    override operator fun invoke(either: Either<Throwable, Any?>): Unit {
      if (canCall) {
        canCall = false
        when (either) {
          is Either.Left -> loop(BIO.RaiseError(either.a), conn, cb, this, bFirst, bRest)
          is Either.Right -> loop(BIO.Pure(either.b), conn, cb, this, bFirst, bRest)
        }
      }
    }
  }

  private fun <T> ((Either<Throwable, T>) -> Unit).asyncCallback(currentCC: CoroutineContext): (Either<Throwable, T>) -> Unit =
    { result ->
      val func: suspend () -> Unit = { this(result) }

      val normalResume: Continuation<Unit> = object : Continuation<Unit> {
        override val context: CoroutineContext = currentCC

        override fun resume(value: Unit) {
        }

        override fun resumeWithException(exception: Throwable) {
          this@asyncCallback(Either.left(exception))
        }

      }

      func.startCoroutine(normalResume)
    }

  private class RestoreContext(
    val old: IOConnection,
    val restore: (Any?, Throwable?, IOConnection, IOConnection) -> IOConnection) : IOFrame<Any?, BIO<Any?, Any?>> {

    override fun invoke(a: Any?): BIO<Any?, Any?> = BIO.ContextSwitch(BIO.Pure(a), { current -> restore(a, null, old, current) }, null)

    override fun recover(e: Throwable): BIO<Any, Any> =
      BIO.ContextSwitch(BIO.RaiseError(e), { current ->
        restore(null, e, old, current)
      }, null)
  }
}
