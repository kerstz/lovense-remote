package com.edge2.remote.ble

import com.edge2.remote.ble.proto.Hint

/** BLE scan state (add-a-toy screen). */
sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState
    /** Failure (missing permission, Bluetooth off, scanner unavailable…). */
    data class Error(val reason: String) : ScanState
}

/** BLE link state of ONE toy. */
sealed interface LinkState {
    /** GATT connection + service discovery + handshake in progress. */
    data object Connecting : LinkState
    /** Ready to drive. */
    data object Connected : LinkState
    /** Link lost → automatic reconnection in progress. */
    data object Reconnecting : LinkState
    /** Permanent failure (service not found, GATT error…). */
    data class Error(val reason: String) : LinkState
}

/**
 * Snapshot of a managed toy (connected or connecting), exposed to the UI, to
 * sharing and to the notification. [levels] = level actually sent to each actuator.
 */
data class ToyStatus(
    val address: String,
    val toy: ToyType,
    val link: LinkState,
    val battery: Int? = null,
    val levels: List<Int> = List(toy.actuators.size) { 0 },
    /** Something the user must do for the connection to complete (e.g. press the power button). */
    val hint: Hint? = null,
) {
    val isReady: Boolean get() = link is LinkState.Connected
    val displayName: String get() = toy.displayName
}

/**
 * A toy seen during the scan (before connecting). The list feeds the add-a-toy
 * screen: only toys that are actually visible nearby are shown.
 */
data class DiscoveredToy(
    val address: String,
    val bleName: String,
    val rssi: Int,
    val protocolId: String,
    val brand: String,
    val displayName: String,
    /** false = recognised but its protocol isn't implemented yet (shown greyed out). */
    val supported: Boolean,
) {
    val experimental: Boolean get() = protocolId != "lovense"
}
