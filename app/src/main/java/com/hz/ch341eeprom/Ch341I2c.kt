package com.hz.ch341eeprom

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * طبقة الاتصال مع مبرمجة CH341A في وضع I2C عبر USB Host
 *
 * أوامر بروتوكول CH341 :
 *  0xAA  بداية حزمة I2C
 *  0x60|s  ضبط السرعة (0=20k, 1=100k, 2=400k, 3=750k)
 *  0x74  START
 *  0x75  STOP
 *  0x80|n  إرسال n بايت
 *  0xC0|n  استقبال n بايت
 *  0x00  نهاية الحزمة
 */
class Ch341I2c {

    companion object {
        const val VID = 0x1A86
        val PIDS = intArrayOf(0x5512, 0x5523, 0x55DB, 0x5518)

        const val CMD_I2C_STREAM = 0xAA
        const val CMD_SET = 0x60
        const val CMD_STA = 0x74
        const val CMD_STO = 0x75
        const val CMD_OUT = 0x80
        const val CMD_IN = 0xC0
        const val CMD_END = 0x00

        const val PACKET = 32

        fun findDevice(manager: UsbManager): UsbDevice? {
            for (d in manager.deviceList.values) {
                if (d.vendorId == VID && PIDS.contains(d.productId)) return d
            }
            // بعض النسخ المقلدة تستخدم PID مختلف، نقبل أي جهاز من WCH
            for (d in manager.deviceList.values) {
                if (d.vendorId == VID) return d
            }
            return null
        }
    }

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var epOut: UsbEndpoint? = null
    private var epIn: UsbEndpoint? = null

    val isOpen: Boolean get() = connection != null && epOut != null && epIn != null

    fun open(manager: UsbManager, device: UsbDevice): String? {
        close()
        val conn = manager.openDevice(device) ?: return "تعذر فتح الجهاز (تحقق من الصلاحية)"
        var iface: UsbInterface? = null
        var out: UsbEndpoint? = null
        var inp: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val f = device.getInterface(i)
            var o: UsbEndpoint? = null
            var n: UsbEndpoint? = null
            for (e in 0 until f.endpointCount) {
                val ep = f.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_OUT && o == null) o = ep
                    if (ep.direction == UsbConstants.USB_DIR_IN && n == null) n = ep
                }
            }
            if (o != null && n != null) { iface = f; out = o; inp = n; break }
        }

        if (iface == null) { conn.close(); return "لم يتم العثور على منافذ Bulk" }
        if (!conn.claimInterface(iface, true)) { conn.close(); return "تعذر حجز الواجهة" }

        connection = conn
        usbInterface = iface
        epOut = out
        epIn = inp

        setSpeed(1) // 100kHz
        return null
    }

    fun close() {
        try {
            usbInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        } catch (_: Exception) { }
        connection = null; usbInterface = null; epOut = null; epIn = null
    }

    private fun bulkOut(buf: ByteArray, len: Int): Boolean {
        val c = connection ?: return false
        val e = epOut ?: return false
        return c.bulkTransfer(e, buf, len, 2000) >= 0
    }

    private fun bulkIn(buf: ByteArray, len: Int): Int {
        val c = connection ?: return -1
        val e = epIn ?: return -1
        return c.bulkTransfer(e, buf, len, 2000)
    }

    fun setSpeed(speed: Int): Boolean {
        val b = byteArrayOf(
            CMD_I2C_STREAM.toByte(),
            (CMD_SET or (speed and 0x03)).toByte(),
            CMD_END.toByte()
        )
        return bulkOut(b, b.size)
    }

    /** اختبار وجود الشريحة على العنوان المحدد (ACK) */
    fun probe(devAddr: Int): Boolean {
        val b = byteArrayOf(
            CMD_I2C_STREAM.toByte(),
            CMD_STA.toByte(),
            (CMD_OUT or 0).toByte(),   // بايت واحد مع إرجاع حالة ACK
            devAddr.toByte(),
            CMD_STO.toByte(),
            CMD_END.toByte()
        )
        if (!bulkOut(b, b.size)) return false
        val r = ByteArray(PACKET)
        val n = bulkIn(r, PACKET)
        if (n <= 0) return false
        return (r[0].toInt() and 0x80) == 0   // 0 = ACK
    }

    /** كتابة حتى 16 بايت داخل صفحة واحدة (عنوان 16 بت) */
    fun writePage(devAddr: Int, memAddr: Int, data: ByteArray, offset: Int, len: Int): Boolean {
        if (len > 16) return false
        val b = ByteArray(PACKET)
        var i = 0
        b[i++] = CMD_I2C_STREAM.toByte()
        b[i++] = CMD_STA.toByte()
        b[i++] = (CMD_OUT or (3 + len)).toByte()
        b[i++] = devAddr.toByte()
        b[i++] = ((memAddr shr 8) and 0xFF).toByte()
        b[i++] = (memAddr and 0xFF).toByte()
        System.arraycopy(data, offset, b, i, len)
        i += len
        b[i++] = CMD_STO.toByte()
        b[i++] = CMD_END.toByte()
        return bulkOut(b, i)
    }

    /** قراءة حتى 16 بايت بدءاً من عنوان 16 بت */
    fun read(devAddr: Int, memAddr: Int, len: Int): ByteArray? {
        if (len < 1 || len > 16) return null
        val b = ByteArray(PACKET)
        var i = 0
        b[i++] = CMD_I2C_STREAM.toByte()
        b[i++] = CMD_STA.toByte()
        b[i++] = (CMD_OUT or 3).toByte()
        b[i++] = devAddr.toByte()
        b[i++] = ((memAddr shr 8) and 0xFF).toByte()
        b[i++] = (memAddr and 0xFF).toByte()
        b[i++] = CMD_STA.toByte()               // Repeated START
        b[i++] = (CMD_OUT or 1).toByte()
        b[i++] = (devAddr or 0x01).toByte()     // عنوان القراءة
        if (len > 1) b[i++] = (CMD_IN or (len - 1)).toByte()
        b[i++] = CMD_IN.toByte()                // آخر بايت مع NACK
        b[i++] = CMD_STO.toByte()
        b[i++] = CMD_END.toByte()
        if (!bulkOut(b, i)) return null

        val r = ByteArray(PACKET)
        val n = bulkIn(r, PACKET)
        if (n < len) return null
        // البيانات تأتي في نهاية الحزمة الراجعة
        return r.copyOfRange(n - len, n)
    }
}
