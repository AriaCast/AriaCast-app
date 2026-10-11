package com.aria.ariacast.airplay

/**
 * Logging facade for the protocol clients (AirPlay 1/2, Google Cast).
 *
 * The clients are plain JVM code so they can be exercised by unit and
 * end-to-end tests against real receivers; android.util.Log isn't available
 * there, so this falls back to stdout when the Android implementation throws.
 */
object ProtoLog {
    interface Sink {
        fun log(level: Int, tag: String, message: String, error: Throwable?)
    }

    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    private object DefaultSink : Sink {
        @Volatile private var androidAvailable = true

        override fun log(level: Int, tag: String, message: String, error: Throwable?) {
            if (androidAvailable) {
                try {
                    when (level) {
                        DEBUG -> android.util.Log.d(tag, message, error)
                        INFO -> android.util.Log.i(tag, message, error)
                        WARN -> android.util.Log.w(tag, message, error)
                        else -> android.util.Log.e(tag, message, error)
                    }
                    return
                } catch (e: Throwable) {
                    // Not running on Android (unit tests) - "Method not mocked".
                    androidAvailable = false
                }
            }
            println("[$tag] $message" + (error?.let { " ($it)" } ?: ""))
        }
    }

    @Volatile var sink: Sink = DefaultSink

    fun d(tag: String, message: String) = sink.log(DEBUG, tag, message, null)
    fun i(tag: String, message: String) = sink.log(INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = sink.log(WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = sink.log(ERROR, tag, message, error)
}
