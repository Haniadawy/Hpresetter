package com.hz.ch341eeprom

/** عمليات ايبروم 24C256 : 32768 بايت، عنونة 16 بت، زمن كتابة الصفحة ~5ms */
class Eeprom24c256(private val dev: Ch341I2c, private val devAddr: Int = 0xA0) {

    companion object {
        const val SIZE = 32768        // 32 كيلوبايت
        const val CHUNK = 16          // بايت في كل عملية كتابة
        const val WRITE_DELAY_MS = 6L // زمن دورة الكتابة الداخلية
    }

    fun present(): Boolean = dev.probe(devAddr)

    fun read(addr: Int, len: Int): ByteArray? = dev.read(devAddr, addr, len)

    /**
     * كتابة قيمة ثابتة (0xFF افتراضياً) من العنوان start حتى نهاية الشريحة
     * @param onProgress يرجع النسبة 0..100
     * @return عدد المقاطع التي فشلت
     */
    fun fill(
        start: Int,
        value: Byte = 0xFF.toByte(),
        cancel: () -> Boolean = { false },
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Int {
        val block = ByteArray(CHUNK) { value }
        var errors = 0
        var addr = start
        val total = SIZE - start
        while (addr < SIZE) {
            if (cancel()) break
            val len = minOf(CHUNK, SIZE - addr)
            if (!dev.writePage(devAddr, addr, block, 0, len)) errors++
            Thread.sleep(WRITE_DELAY_MS)
            addr += len
            onProgress(((addr - start) * 100) / total, addr)
        }
        return errors
    }

    /** التحقق أن كل البايتات بعد start تساوي القيمة، يرجع أول عنوان مخالف أو -1 */
    fun verify(
        start: Int,
        value: Byte = 0xFF.toByte(),
        cancel: () -> Boolean = { false },
        onProgress: (Int) -> Unit = { }
    ): Int {
        var addr = start
        val total = SIZE - start
        while (addr < SIZE) {
            if (cancel()) break
            val len = minOf(CHUNK, SIZE - addr)
            val data = dev.read(devAddr, addr, len) ?: return addr
            for (i in 0 until len) {
                if (data[i] != value) return addr + i
            }
            addr += len
            onProgress(((addr - start) * 100) / total)
        }
        return -1
    }
}
