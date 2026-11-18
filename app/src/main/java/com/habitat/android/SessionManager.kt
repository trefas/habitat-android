package com.habitat.android

import android.os.Handler
import android.os.Looper

/**
 * Delivers "forced logout" events (refresh token rejected by the server) to the
 * currently visible screen.
 *
 * Events are posted to the main thread. If no screen is listening at the moment
 * the event fires, it is kept pending and delivered when a listener registers.
 */
object SessionManager {
    interface Listener {
        fun onForcedLogout()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var pending = false

    @Volatile
    private var generation = 0

    fun registerListener(newListener: Listener) {
        listener = newListener

        if (pending) {
            pending = false
            generation++
            mainHandler.post {
                if (listener === newListener) {
                    newListener.onForcedLogout()
                }
            }
        }
    }

    fun unregisterListener(oldListener: Listener) {
        if (listener === oldListener) {
            listener = null
        }
    }

    fun notifyForcedLogout() {
        pending = true
        val eventGeneration = ++generation

        mainHandler.post {
            val currentListener = listener
            if (currentListener != null && pending && generation == eventGeneration) {
                pending = false
                currentListener.onForcedLogout()
            }
        }
    }

    /** Clears an event that is no longer relevant (e.g. after an explicit logout). */
    fun clearPending() {
        pending = false
        generation++
    }
}
