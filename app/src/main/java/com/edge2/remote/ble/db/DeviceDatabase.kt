package com.edge2.remote.ble.db

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Device database generated from the Buttplug device config by
 * `tools/gen_devices.py` (assets/devices.json). It tells us, for ~140 BLE
 * protocols and ~900 models, how a toy advertises itself and which outputs
 * (vibrate, rotate, heat, LED…) it has, with their ranges.
 */
@Serializable
data class DbFile(val source: String = "", val protocols: List<DbProtocol> = emptyList())

@Serializable
data class DbProtocol(
    val id: String,
    val btle: DbBtle,
    val defaults: DbDevice? = null,
    val configs: List<DbDevice> = emptyList(),
)

@Serializable
data class DbBtle(
    val names: List<String> = emptyList(),
    /** service UUID → (endpoint name → characteristic UUID). */
    val services: Map<String, Map<String, String>> = emptyMap(),
    val mfr: List<DbMfr> = emptyList(),
    val adv: List<String> = emptyList(),
)

@Serializable
data class DbMfr(val c: Int, val d: List<Int> = emptyList())

@Serializable
data class DbDevice(
    val name: String,
    val ids: List<String> = emptyList(),
    val f: List<DbFeature>? = null,
    val variant: String? = null,
)

@Serializable
data class DbFeature(
    val i: Int,
    val o: Map<String, DbOutput> = emptyMap(),
    val battery: Boolean = false,
    val desc: String? = null,
)

@Serializable
data class DbOutput(val r: List<Int>, val d: List<Int>? = null)

/** Kinds of output a toy feature can drive. */
enum class OutputType(val key: String) {
    VIBRATE("vibrate"),
    ROTATE("rotate"),
    OSCILLATE("oscillate"),
    CONSTRICT("constrict"),
    TEMPERATURE("temperature"),
    LED("led"),
    SPRAY("spray"),
    POSITION("position"),
    HW_POSITION("hw_position_with_duration");

    companion object {
        fun of(key: String): OutputType? = entries.firstOrNull { it.key == key }
    }
}

/** One output of a feature, with its accepted value range. */
data class OutputDef(val type: OutputType, val min: Int, val max: Int, val maxDurationMs: Int? = null)

/** A device feature (index as used by the protocol) and its outputs. */
data class FeatureDef(
    val index: Int,
    val outputs: List<OutputDef>,
    val battery: Boolean,
    val description: String?,
) {
    fun output(type: OutputType): OutputDef? = outputs.firstOrNull { it.type == type }
}

/** A resolved model: display name + features (+ protocol variant, if any). */
data class DeviceDefinition(
    val protocolId: String,
    val name: String,
    val features: List<FeatureDef>,
    val variant: String? = null,
) {
    fun count(type: OutputType) = features.count { f -> f.outputs.any { it.type == type } }
    val outputFeatureCount: Int get() = features.count { it.outputs.isNotEmpty() }
    fun has(type: OutputType) = count(type) > 0
}

/** What a scan advertisement looked like (the inputs to identification). */
data class Advertisement(
    val name: String?,
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    val serviceUuids: List<UUID> = emptyList(),
)

class DeviceDatabase(file: DbFile) {

    val protocols: List<DbProtocol> = file.protocols
    private val byId = protocols.associateBy { it.id }

    fun protocol(id: String): DbProtocol? = byId[id]

    /**
     * Protocols matching an advertisement, best first: exact name, then
     * wildcard name (`SF *`), then manufacturer data, then advertised service.
     * Same rules as Buttplug's BluetoothLESpecifier.
     */
    fun identify(ad: Advertisement): DbProtocol? {
        val name = ad.name?.trim().orEmpty()
        if (name.isNotEmpty()) {
            protocols.firstOrNull { p -> p.btle.names.any { it == name } }?.let { return it }
            protocols.firstOrNull { p ->
                p.btle.names.any { it.endsWith("*") && name.startsWith(it.dropLast(1)) }
            }?.let { return it }
        }
        if (ad.manufacturerData.isNotEmpty()) {
            protocols.firstOrNull { p ->
                p.btle.mfr.any { m -> ad.manufacturerData[m.c]?.let { mfrMatches(m.d, it) } == true }
            }?.let { return it }
        }
        if (ad.serviceUuids.isNotEmpty()) {
            val adv = ad.serviceUuids.map { it.toString().lowercase() }.toSet()
            protocols.firstOrNull { p -> p.btle.adv.any { it.lowercase() in adv } }?.let { return it }
        }
        return null
    }

    /** Model for [identifier] within [protocolId], falling back to the protocol defaults. */
    fun definition(protocolId: String, identifier: String?): DeviceDefinition? {
        val p = byId[protocolId] ?: return null
        val cfg = identifier?.let { id -> p.configs.firstOrNull { id in it.ids } }
        val base = p.defaults
        val name = cfg?.name ?: base?.name ?: protocolId
        val feats = cfg?.f ?: base?.f ?: emptyList()
        return DeviceDefinition(
            protocolId = protocolId,
            name = name,
            features = feats.map { it.toDef() }.sortedBy { it.index },
            variant = cfg?.variant ?: base?.variant,
        )
    }

    /** Display name guess at scan time (BLE name as identifier). */
    fun guessName(p: DbProtocol, bleName: String?): String =
        (bleName?.let { n -> p.configs.firstOrNull { n in it.ids }?.name } ?: p.defaults?.name ?: p.id)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): DeviceDatabase = DeviceDatabase(json.decodeFromString(DbFile.serializer(), text))

        /** Buttplug's manufacturer-data rule: the shorter array must appear inside the longer one. */
        internal fun mfrMatches(expected: List<Int>, actual: ByteArray): Boolean {
            val a = expected.map { it.toByte() }.toByteArray()
            val (needle, haystack) = if (a.size <= actual.size) a to actual else actual to a
            if (needle.isEmpty()) return true
            for (start in 0..(haystack.size - needle.size)) {
                if ((needle.indices).all { haystack[start + it] == needle[it] }) return true
            }
            return false
        }

        /** Human brand from the protocol defaults name ("JoyHub Device" → "JoyHub"). */
        fun brandOf(p: DbProtocol): String =
            (p.defaults?.name ?: p.id).removeSuffix(" Device").removeSuffix(" device").trim()
                .ifEmpty { p.id }
    }
}

private fun DbFeature.toDef() = FeatureDef(
    index = i,
    outputs = o.mapNotNull { (k, v) ->
        OutputType.of(k)?.let { OutputDef(it, v.r.getOrElse(0) { 0 }, v.r.getOrElse(1) { 0 }, v.d?.getOrNull(1)) }
    },
    battery = battery,
    description = desc,
)
