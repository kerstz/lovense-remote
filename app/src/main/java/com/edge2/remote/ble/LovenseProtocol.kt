package com.edge2.remote.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import java.util.UUID

/**
 * GATT helpers. The Lovense command set itself lives in `proto/Lovense.kt`;
 * this keeps the endpoint heuristic used when a Lovense toy exposes a service
 * UUID the database doesn't list yet (see PROTOCOL.md).
 */
object LovenseProtocol {

    /** Standard CCCD (Client Characteristic Configuration Descriptor). */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** BLE name prefix advertised by all recent Lovense toys. */
    const val BLE_NAME_PREFIX = "LVS-"

    /**
     * Walks the GATT services and returns the toy's (TX write, RX notify).
     *
     * Robust heuristic (independent of the UUID revision): find the service
     * holding both a NOTIFY characteristic (RX) and a writable one (TX). That
     * is the "serial port" RX/TX layout of every Lovense toy.
     */
    fun findEndpoints(services: List<BluetoothGattService>): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        for (service in services) {
            val notify = service.characteristics.firstOrNull {
                it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            } ?: continue
            val write = service.characteristics.firstOrNull {
                val p = it.properties
                (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ||
                    p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) &&
                    it.uuid != notify.uuid
            } ?: continue
            return write to notify
        }
        return null
    }
}
