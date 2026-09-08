package com.hz.ch341eeprom

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val ACTION_USB_PERMISSION = "com.hz.ch341eeprom.USB_PERMISSION"

    private lateinit var usbManager: UsbManager
    private val ch341 = Ch341I2c()
    private var eeprom: Eeprom24c256? = null
    private var busy = false

    private lateinit var tvProgrammer: TextView
    private lateinit var tvEeprom: TextView
    private lateinit var tvRange: TextView
    private lateinit var tvLog: TextView
    private lateinit var etSkip: EditText
    private lateinit var progress: ProgressBar
    private lateinit var btnWrite: Button
    private lateinit var btnRead: Button
    private lateinit var btnVerify: Button
    private lateinit var btnRefresh: Button

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION,
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> refresh()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        tvProgrammer = findViewById(R.id.tvProgrammer)
        tvEeprom = findViewById(R.id.tvEeprom)
        tvRange = findViewById(R.id.tvRange)
        tvLog = findViewById(R.id.tvLog)
        etSkip = findViewById(R.id.etSkip)
        progress = findViewById(R.id.progress)
        btnWrite = findViewById(R.id.btnWrite)
        btnRead = findViewById(R.id.btnRead)
        btnVerify = findViewById(R.id.btnVerify)
        btnRefresh = findViewById(R.id.btnRefresh)

        btnRefresh.setOnClickListener { refresh() }
        btnRead.setOnClickListener { doRead() }
        btnWrite.setOnClickListener { confirmWrite() }
        btnVerify.setOnClickListener { doVerify() }

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        refresh()
    }

    override fun onDestroy() {
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        ch341.close()
        super.onDestroy()
    }

    private fun startAddress(): Int {
        val rows = etSkip.text.toString().toIntOrNull() ?: 6
        return (rows.coerceIn(0, 128)) * 16
    }

    private fun log(s: String) {
        runOnUiThread { tvLog.text = "${tvLog.text}\n$s" }
    }

    private fun setButtons(enabled: Boolean) {
        btnWrite.isEnabled = enabled
        btnRead.isEnabled = enabled
        btnVerify.isEnabled = enabled
    }

    private fun refresh() {
        tvRange.text = String.format(
            "سيتم المسح من 0x%04X إلى 0x%04X", startAddress(), Eeprom24c256.SIZE - 1
        )

        val device: UsbDevice? = Ch341I2c.findDevice(usbManager)
        if (device == null) {
            ch341.close()
            eeprom = null
            tvProgrammer.text = "المبرمجة: غير متصلة ❌"
            tvEeprom.text = "الايبروم: —"
            setButtons(false)
            return
        }

        if (!usbManager.hasPermission(device)) {
            tvProgrammer.text = "المبرمجة: بانتظار الصلاحية…"
            setButtons(false)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
            )
            usbManager.requestPermission(device, pi)
            return
        }

        if (!ch341.isOpen) {
            val err = ch341.open(usbManager, device)
            if (err != null) {
                tvProgrammer.text = "المبرمجة: خطأ - $err"
                setButtons(false)
                return
            }
            eeprom = Eeprom24c256(ch341)
        }

        tvProgrammer.text = String.format(
            "المبرمجة: متصلة ✅  (CH341  %04X:%04X)", device.vendorId, device.productId
        )
        setButtons(true)

        Thread {
            val ok = eeprom?.present() == true
            runOnUiThread {
                tvEeprom.text = if (ok) "الايبروم 24C256: متصل ✅ (0xA0)"
                else "الايبروم: غير مستجيب ⚠️ تحقق من التوصيلات"
            }
        }.start()
    }

    private fun doRead() {
        val e = eeprom ?: return
        if (busy) return
        busy = true; setButtons(false)
        Thread {
            val sb = StringBuilder("قراءة أول 64 بايت:\n")
            for (row in 0 until 4) {
                val d = e.read(row * 16, 16)
                sb.append(String.format("%04X: ", row * 16))
                if (d == null) sb.append("فشل القراءة")
                else for (b in d) sb.append(String.format("%02X ", b))
                sb.append("\n")
            }
            runOnUiThread {
                tvLog.text = sb.toString()
                busy = false; setButtons(true)
            }
        }.start()
    }

    private fun confirmWrite() {
        val start = startAddress()
        AlertDialog.Builder(this)
            .setTitle("تأكيد الكتابة")
            .setMessage(
                String.format(
                    "سيتم كتابة 0xFF من 0x%04X حتى 0x%04X\nالصفوف الأولى (%d بايت) لن تتغير.\nهل تريد المتابعة؟",
                    start, Eeprom24c256.SIZE - 1, start
                )
            )
            .setPositiveButton("نعم، ابدأ") { _, _ -> doWrite(start) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun doWrite(start: Int) {
        val e = eeprom ?: return
        if (busy) return
        busy = true; setButtons(false)
        progress.progress = 0
        tvLog.text = "بدء الكتابة…"
        val t0 = System.currentTimeMillis()
        Thread {
            val errors = e.fill(start, 0xFF.toByte(), { false }) { pct, _ ->
                runOnUiThread { progress.progress = pct }
            }
            val sec = (System.currentTimeMillis() - t0) / 1000
            runOnUiThread {
                progress.progress = 100
                tvLog.text = if (errors == 0)
                    "تمت الكتابة بنجاح في $sec ثانية ✅"
                else "انتهت الكتابة مع $errors مقطع فاشل ⚠️ ($sec ثانية)"
                busy = false; setButtons(true)
            }
        }.start()
    }

    private fun doVerify() {
        val e = eeprom ?: return
        if (busy) return
        busy = true; setButtons(false)
        progress.progress = 0
        tvLog.text = "جاري التحقق…"
        val start = startAddress()
        Thread {
            val bad = e.verify(start, 0xFF.toByte(), { false }) { pct ->
                runOnUiThread { progress.progress = pct }
            }
            runOnUiThread {
                tvLog.text = if (bad < 0) "التحقق ناجح: كل البايتات = FF ✅"
                else String.format("اختلاف عند العنوان 0x%04X ❌", bad)
                busy = false; setButtons(true)
            }
        }.start()
    }
}
