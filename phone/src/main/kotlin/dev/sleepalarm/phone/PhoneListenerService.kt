package dev.sleepalarm.phone

import com.google.android.gms.wearable.*
import dev.sleepalarm.settings.SettingsStore
import dev.sleepalarm.sync.*
import java.time.*
import java.time.format.DateTimeFormatter

class PhoneListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val sync = WatchSync(this)
        events.forEach { if (it.type == DataEvent.TYPE_CHANGED) sync.ingest(it.dataItem) }
    }
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != SyncProtocol.ACK) return
        runCatching {
            val j = SyncProtocol.parse(event.data)
            val store = SettingsStore(this)
            if (j.getString("request") != store.pendingRequest()) return
            val deadline = if (j.getBoolean("ok")) " · " + Instant.ofEpochMilli(j.getLong("deadline"))
                .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM/dd HH:mm")) else ""
            store.ack(j.getString("message") + deadline)
        }
    }
}
