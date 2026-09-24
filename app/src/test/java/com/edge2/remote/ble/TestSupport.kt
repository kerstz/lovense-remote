package com.edge2.remote.ble

import com.edge2.remote.ble.db.DeviceDatabase
import com.edge2.remote.ble.proto.DeviceIo
import com.edge2.remote.ble.proto.Hint
import com.edge2.remote.ble.proto.Write
import java.io.File

/** Loads the real generated database (assets/devices.json). */
object TestDb {
    val db: DeviceDatabase by lazy {
        val f = listOf("src/main/assets/devices.json", "app/src/main/assets/devices.json")
            .map(::File).firstOrNull { it.exists() }
            ?: File(System.getProperty("devicesJson") ?: error("devices.json not found"))
        DeviceDatabase.parse(f.readText())
    }
}

/** In-memory device: records writes, answers reads/notifications from queues. */
class FakeIo(
    override val name: String = "Fake",
    private val endpointNames: Set<String> = setOf("tx", "rx"),
    override val manufacturerData: Map<Int, ByteArray> = emptyMap(),
) : DeviceIo {
    val writes = mutableListOf<Write>()
    val reads = HashMap<String, ByteArray>()
    val pendingNotifications = ArrayDeque<ByteArray>()
    var hintShown: Hint? = null

    override fun hint(hint: Hint?) { if (hint != null) hintShown = hint }
    override fun hasEndpoint(endpoint: String) = endpoint in endpointNames
    override suspend fun write(w: Write): Boolean { writes += w; return true }
    override suspend fun read(endpoint: String): ByteArray? = reads[endpoint]
    override suspend fun subscribe(endpoint: String) = true
    override suspend fun unsubscribe(endpoint: String) {}
    override suspend fun awaitNotification(endpoint: String?, timeoutMs: Long): ByteArray? = pendingNotifications.removeFirstOrNull()
}

fun ints(b: ByteArray) = b.map { it.toInt() and 0xff }
