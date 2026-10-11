package com.hmaossdetector

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import kotlin.concurrent.thread

/**
 * Runs each scan in a separate process and exits after returning the result.
 * A new process avoids reusing package-query results from an earlier scan.
 */
class ScanService : Service() {
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            val replyTo = message.replyTo ?: return
            thread(name = "hma-scan") {
                val report = runCatching { HmaScan(this@ScanService).run() }
                    .getOrElse { error ->
                        ScanReport(error = "Scan failed: ${error.javaClass.simpleName} ${error.message.orEmpty()}")
                    }
                val reply = Message.obtain()
                reply.data = Bundle().apply { report.writeTo(this) }
                runCatching { replyTo.send(reply) }
                mainExecutor.execute {
                    stopSelf()
                    Process.killProcess(Process.myPid())
                }
            }
        }
    }
    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent): IBinder = messenger.binder

}
