package dev.sleepalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = AlarmController(context)
        when (intent.action) {
            "dismiss" -> c.dismiss(intent.getStringExtra("nonce") ?: return)
            "snooze-action" -> runCatching { c.snooze(intent.getStringExtra("nonce") ?: return) }
            else -> c.deliver(intent.getStringExtra("id") ?: return, intent.action ?: return, intent.getLongExtra("at", 0))
        }
    }
}

class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { AlarmController(context).restore() }
    }
}
