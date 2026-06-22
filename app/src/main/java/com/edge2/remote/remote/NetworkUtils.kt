package com.edge2.remote.remote

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /**
     * IPv4 locale d'une interface **Wi-Fi / Ethernet** (jamais cellulaire), ou
     * null. On exclut le cellulaire (`rmnet…`) : son IP `10.x` est site-local
     * mais derrière le NAT opérateur → un lien LAN dessus est injoignable. Le
     * partage LAN n'a de sens que sur Wi-Fi / Ethernet / partage de connexion.
     */
    fun lanIpv4(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && isLanInterface(it.name) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }

    /** Wi-Fi / Ethernet / hotspot — exclut le cellulaire (rmnet, ccmni, pdp…). */
    private fun isLanInterface(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("wlan") || n.startsWith("eth") || n.startsWith("ap") || n.startsWith("swlan")
    }

    /** Génère un QR code (bitmap noir/blanc) pour [content]. */
    fun qrBitmap(content: String, size: Int = 512): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}
