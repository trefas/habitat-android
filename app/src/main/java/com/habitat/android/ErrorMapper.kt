package com.habitat.android

import android.content.Context
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/** Maps exceptions thrown by [ApiClient] to user-facing localized messages. */
object ErrorMapper {
    fun message(context: Context, error: Throwable): String = when (error) {
        is ApiException -> when (error.statusCode) {
            401 -> context.getString(R.string.error_auth_failed)
            403 -> context.getString(R.string.error_user_blocked)
            404 -> context.getString(R.string.error_not_found)
            else -> error.message?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.error_server)
        }

        is SocketTimeoutException -> context.getString(R.string.error_timeout)
        is InterruptedIOException -> context.getString(R.string.error_timeout)
        is IOException -> context.getString(R.string.error_network)
        else -> error.message?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.error_unknown)
    }
}
