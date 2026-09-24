package com.edge2.remote.ble.proto

import com.edge2.remote.ble.db.DeviceDefinition

/** Satisfyer: model from the advertisement (or the model characteristic), 500 ms keepalive. */
class Satisfyer(def: DeviceDefinition) : ProtocolHandler(def) {
    private val last = IntArray(def.outputFeatureCount.coerceAtLeast(1))
    override val keepaliveMs: Long = 500
    override fun vibrate(index: Int, speed: Int): List<Write> {
        last.setSafe(index, speed.u8())
        return listOf(w(Ep.TX, last.flatMap { v -> List(4) { v } }))
    }

    companion object {
        private const val COMPANY = 93

        private fun be32(d: ByteArray?): String =
            if (d == null || d.size != 4) "0"
            else (((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or
                ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)).toString()

        val spec = object : ProtocolSpec {
            override val id = "satisfyer"
            override suspend fun identify(io: DeviceIo): Identified {
                io.manufacturerData[COMPANY]?.let { return Identified(be32(it)) }
                return Identified(be32(io.read(Ep.RX_BLE_MODEL)))
            }
            override suspend fun create(def: DeviceDefinition, io: DeviceIo, identifier: String?): ProtocolHandler {
                io.write(w(Ep.COMMAND, 0x01, withResponse = true))
                return Satisfyer(def)
            }
        }
    }
}

/** Every protocol the app can drive, by device-database id. */
object Protocols {
    private val all: List<ProtocolSpec> = listOf(
        LovenseSpec,
        spec("activejoy", ::ActiveJoy),
        spec("adrienlastic", ::AdrienLastic),
        AmorelieJoy.spec,
        spec("aneros", ::Aneros),
        Ankni.spec,
        spec("bananasome", ::Bananasome),
        spec("cachito", ::Cachito),
        spec("cowgirl", ::Cowgirl),
        CowgirlCone.spec,
        spec("cupido", ::Cupido),
        spec("deepsire", ::DeepSire),
        spec("feelingso", ::FeelingSo),
        spec("fleshy-thrust", ::FleshyThrust),
        Fluffer.spec,
        FMachine.spec,
        Foreo.spec,
        spec("fox", ::Fox),
        Fredorch.spec,
        FredorchRotary.spec,
        Galaku.spec,
        spec("galaku-pump", ::GalakuPump),
        spec("hgod", ::Hgod),
        Hismith.spec,
        HismithMini.spec,
        HoneyPlayBox.spec,
        spec("htk_bm", ::HtkBm),
        spec("itoys", ::IToys),
        spec("jejoue", ::JeJoue),
        spec("joyhub", ::JoyHub),
        spec("kiiroo-powershot", ::KiirooPowerShot),
        spec("kiiroo-prowand", ::KiirooProWand),
        spec("kiiroo-spot", ::KiirooSpot),
        spec("kiiroo-spot-v2", ::KiirooSpotV2),
        KiirooV2.spec,
        spec("kiiroo-v2-vibrator", ::KiirooV2Vibrator),
        spec("kiiroo-v21") { KiirooV21(it) },
        KiirooV21Initialized.spec,
        spec("kiiroo-v3") { KiirooV21(it) },
        spec("kizuna", ::Kizuna),
        LeloF1s.spec,
        LeloF1s.specV2,
        LeloHarmony.spec,
        Leten.spec,
        spec("libo-elle", ::LiboElle),
        spec("libo-shark", ::LiboShark),
        spec("libo-vibes", ::LiboVibes),
        Lioness.spec,
        Loob.spec,
        LoveDistance.spec,
        spec("lovehoney-desire", ::LovehoneyDesire),
        spec("lovenuts", ::LoveNuts),
        spec("luvmazer", ::Luvmazer),
        spec("magic-motion-1", ::MagicMotionV1),
        spec("magic-motion-2", ::MagicMotionV2),
        spec("magic-motion-3", ::MagicMotionV3),
        spec("magic-motion-4", ::MagicMotionV4),
        spec("mannuo", ::ManNuo),
        spec("maxpro", ::Maxpro),
        spec("meese", ::Meese),
        spec("mizzzee", ::MizzZee),
        spec("mizzzee-v2", ::MizzZeeV2),
        spec("mizzzee-v3", ::MizzZeeV3),
        MonsterPub.spec,
        spec("motorbunny", ::Motorbunny),
        spec("mymuselinkplus", ::MyMuseLinkPlus),
        MysteryVibe.spec,
        MysteryVibe.specV2,
        spec("nexus-revo", ::NexusRevo),
        Nobra.spec,
        spec("omobo", ::Omobo),
        Ossm.spec,
        Patoo.spec,
        spec("picobong", ::Picobong),
        spec("pink_punch", ::PinkPunch),
        PrettyLove.spec,
        spec("realov", ::Realov),
        spec("sakuraneko", ::Sakuraneko),
        Satisfyer.spec,
        spec("sensee", ::Sensee),
        spec("sensee-capsule", ::SenseeCapsule),
        SenseeV2.spec,
        spec("serveu", ::ServeU),
        spec("sexverse-lg389", ::SexverseLG389),
        spec("sexverse-v1", ::SexverseV1),
        SexverseV2.spec,
        spec("sexverse-v3", ::SexverseV3),
        spec("sexverse-v4", ::SexverseV4),
        spec("sexverse-v5", ::SexverseV5),
        spec("sexverse-v6", ::SexverseV6),
        spec("svakom-alex", ::SvakomAlex),
        spec("svakom-alex-v2", ::SvakomAlexV2),
        spec("svakom-avaneo", ::SvakomAvaNeo),
        spec("svakom-barnard", ::SvakomBarnard),
        spec("svakom-barney", ::SvakomBarney),
        spec("svakom-dice", ::SvakomDice),
        spec("svakom-dt250a", ::SvakomDT250A),
        spec("svakom-fatima", ::SvakomFatima),
        spec("svakom-iker", ::SvakomIker),
        spec("svakom-jordan", ::SvakomJordan),
        spec("svakom-pulse", ::SvakomPulse),
        SvakomSam.spec,
        spec("svakom-sam2", ::SvakomSam2),
        spec("svakom-suitcase", ::SvakomSuitcase),
        spec("svakom-tarax", ::SvakomTaraX),
        spec("svakom-v1", ::SvakomV1),
        spec("svakom-v2", ::SvakomV2),
        spec("svakom-v3", ::SvakomV3),
        spec("svakom-v4", ::SvakomV4),
        spec("svakom-v5", ::SvakomV5),
        spec("svakom-v6", ::SvakomV6),
        spec("synchro", ::Synchro),
        spec("tryfun", ::TryFun),
        spec("tryfun-blackhole", ::TryFunBlackHole),
        spec("tryfun-meta2", ::TryFunMeta2),
        Umove.spec,
        spec("utimi", ::Utimi),
        AesTextVibrator.vibCrafter,
        AesTextVibrator.vibio,
        Vibratissimo.spec,
        VorzeSpec,
        WeToy.spec,
        WeVibe.spec,
        WeVibe.spec8Bit,
        WeVibe.specChorus,
        spec("xibao", ::Xibao),
        spec("xiuxiuda", ::Xiuxiuda),
        spec("xuanhuan", ::Xuanhuan),
        spec("yiciyuan", ::Yiciyuan),
        spec("youcups", ::Youcups),
        Youou.spec,
        spec("zalo", ::Zalo),
    )

    private val byId = all.associateBy { it.id }

    fun get(id: String): ProtocolSpec? = byId[id]
    val ids: Set<String> get() = byId.keys
}
