package com.edge2.remote.remote

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /**
     * Local IPv4 of a **Wi-Fi / Ethernet** interface (never cellular), or null.
     * Cellular (`rmnet…`) is excluded: its `10.x` address is site-local but sits
     * behind carrier NAT → a LAN link on it is unreachable. LAN sharing only
     * makes sense on Wi-Fi / Ethernet / tethering.
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

    /** Wi-Fi / Ethernet / hotspot — excludes cellular (rmnet, ccmni, pdp…). */
    private fun isLanInterface(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("wlan") || n.startsWith("eth") || n.startsWith("ap") || n.startsWith("swlan")
    }

    /** Generates a black/white QR code bitmap for [content]. */
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
