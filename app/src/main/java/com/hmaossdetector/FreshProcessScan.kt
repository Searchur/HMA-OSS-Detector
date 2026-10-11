package com.hmaossdetector

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal const val SAMPLING_ROUNDS = 5

/** Waits for both the reply and process exit before allowing the next sampling round. */
internal suspend fun scanInFreshProcess(context: Context): ScanReport = suspendCancellableCoroutine { continuation ->
    val handler = Handler(Looper.getMainLooper())
    var reply: ScanReport? = null
    var processExited = false
    var finished = false
    var bound = false
    var binder: IBinder? = null
    var deathRecipient: IBinder.DeathRecipient? = null
    lateinit var connection: ServiceConnection
    lateinit var timeout: Runnable

    fun cleanup() {
        handler.removeCallbacks(timeout)
        deathRecipient?.let { recipient -> runCatching { binder?.unlinkToDeath(recipient, 0) } }
        if (bound) {
            bound = false
            runCatching { context.unbindService(connection) }
        }
    }

    fun finish(report: ScanReport) {
        if (finished) return
        finished = true
        cleanup()
        if (continuation.isActive) continuation.resume(report)
    }

    fun finishIfReady() {
        if (processExited) reply?.let { finish(it) }
    }

    timeout = Runnable { finish(ScanReport(error = "Scan failed: sampling round timed out.")) }
    connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (finished || binder != null) return
            binder = service
            deathRecipient = IBinder.DeathRecipient {
                handler.post {
                    processExited = true
                    finishIfReady()
                }
            }
            val replyTo = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(message: Message) {
                    reply = runCatching { ScanReport.from(message.data) }.getOrElse { error ->
                        ScanReport(error = "Scan failed: ${error.javaClass.simpleName} ${error.message.orEmpty()}")
                    }
                    finishIfReady()
                }
            })
            runCatching {
                service.linkToDeath(requireNotNull(deathRecipient), 0)
                Messenger(service).send(Message.obtain().apply { this.replyTo = replyTo })
            }.onFailure { error ->
                finish(ScanReport(error = "Scan failed: ${error.javaClass.simpleName} ${error.message.orEmpty()}"))
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            processExited = true
            finishIfReady()
        }

        override fun onNullBinding(name: ComponentName) {
            finish(ScanReport(error = "Scan failed: no sampling service binder."))
        }

        override fun onBindingDied(name: ComponentName) {
            finish(ScanReport(error = "Scan failed: sampling service binding died."))
        }
    }
    continuation.invokeOnCancellation {
        handler.post {
            if (!finished) {
                finished = true
                cleanup()
            }
        }
    }
    handler.postDelayed(timeout, 15_000L)
    runCatching {
        bound = context.bindService(Intent(context, ScanService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) finish(ScanReport(error = "Scan failed: could not bind the sampling service."))
    }.onFailure { error ->
        finish(ScanReport(error = "Scan failed: ${error.javaClass.simpleName} ${error.message.orEmpty()}"))
    }
}
