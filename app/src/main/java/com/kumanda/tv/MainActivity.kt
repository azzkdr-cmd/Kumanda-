package com.kumanda.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class MainActivity : Activity(), AtvRemote.Listener {

    private lateinit var remote: AtvRemote
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("kumanda", Context.MODE_PRIVATE) }

    private var host: String? = null
    private var deviceName: String? = null
    private var started = false
    private var pairing = false
    private var dialog: AlertDialog? = null

    private val reconnect = Runnable {
        val h = host
        if (started && !pairing && h != null &&
            (remote.state == AtvRemote.State.DISCONNECTED || remote.state == AtvRemote.State.UNREACHABLE)
        ) remote.connect(h)
    }

    // ------------------------------------------------------------ yasam dongusu

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        remote = AtvRemote(this, this)
        host = prefs.getString("host", null)
        deviceName = prefs.getString("name", null)
        setContentView(buildUi())
        updateStatus(AtvRemote.State.DISCONNECTED)
    }

    override fun onStart() {
        super.onStart()
        started = true
        val h = host
        if (h == null) showDevicePicker() else if (!pairing) remote.connect(h)
    }

    override fun onStop() {
        super.onStop()
        started = false
        handler.removeCallbacks(reconnect)
        if (!pairing) remote.disconnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        dialog?.dismiss()
        remote.disconnect()
    }

    // Telefonun ses tuslari TV'nin sesini ayarlar.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            press(keyCode)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true
        return super.onKeyUp(keyCode, event)
    }

    // ------------------------------------------------------------ protokol olaylari

    override fun onState(state: AtvRemote.State) {
        updateStatus(state)
        handler.removeCallbacks(reconnect)
        when (state) {
            AtvRemote.State.NEEDS_PAIRING -> askPairing()
            AtvRemote.State.DISCONNECTED, AtvRemote.State.UNREACHABLE ->
                if (started) handler.postDelayed(reconnect, 3000)
            else -> {}
        }
    }

    override fun onPairingCodeRequested() {
        val input = EditText(this).apply {
            hint = "A1B2C3"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(6), InputFilter.AllCaps())
            gravity = Gravity.CENTER
            textSize = 28f
            letterSpacing = 0.3f
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        show(
            AlertDialog.Builder(this)
                .setTitle("Eşleştirme kodu")
                .setMessage("TV ekranında görünen 6 haneli kodu girin.")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Tamam") { _, _ ->
                    status.text = "Eşleştiriliyor…"
                    remote.finishPairing(input.text.toString())
                }
                .setNegativeButton("İptal") { _, _ ->
                    pairing = false
                    remote.disconnect()
                    updateStatus(AtvRemote.State.NEEDS_PAIRING)
                }
        )
        input.requestFocus()
    }

    override fun onPairingResult(ok: Boolean, error: String?) {
        pairing = false
        val h = host ?: return
        if (ok) {
            toast("Eşleştirildi")
            remote.connect(h)
        } else {
            toast(error ?: "Eşleştirme başarısız")
            updateStatus(AtvRemote.State.NEEDS_PAIRING)
            askPairing()
        }
    }

    private fun askPairing() {
        val h = host ?: return
        if (!started) return
        show(
            AlertDialog.Builder(this)
                .setTitle("Eşleştirme gerekli")
                .setMessage("TV açık olsun. \"Eşleştir\"e basınca TV ekranında bir kod görünecek.")
                .setPositiveButton("Eşleştir") { _, _ ->
                    pairing = true
                    status.text = "Kod isteniyor…"
                    remote.startPairing(h)
                }
                .setNeutralButton("Başka cihaz") { _, _ -> showDevicePicker() }
                .setNegativeButton("İptal", null)
        )
    }

    private fun updateStatus(state: AtvRemote.State) {
        val name = deviceName ?: host
        status.text = when {
            host == null -> "Cihaz seçmek için dokunun"
            state == AtvRemote.State.CONNECTED -> "● $name"
            state == AtvRemote.State.CONNECTING -> "Bağlanıyor… $name"
            state == AtvRemote.State.NEEDS_PAIRING -> "Eşleştirme gerekli – $name"
            state == AtvRemote.State.UNREACHABLE -> "Ulaşılamıyor – $name"
            else -> "Bağlı değil – $name"
        }
        status.setTextColor(if (state == AtvRemote.State.CONNECTED) GREEN else GRAY)
    }

    // ------------------------------------------------------------ cihaz secimi

    private fun selectDevice(newHost: String, name: String?) {
        host = newHost
        deviceName = name
        prefs.edit().putString("host", newHost).putString("name", name).apply()
        pairing = false
        remote.connect(newHost)
    }

    private fun showDevicePicker() {
        val names = ArrayList<String>()
        val found = ArrayList<Pair<String, String>>() // host, ad
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        var active = true

        fun resolve(info: NsdServiceInfo, attempt: Int) {
            if (!active || attempt > 5) return
            try {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) {
                        handler.postDelayed({ resolve(info, attempt + 1) }, 600)
                    }

                    override fun onServiceResolved(i: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val address = i.host?.hostAddress ?: return
                        val name = i.serviceName ?: address
                        handler.post {
                            if (active && found.none { it.first == address }) {
                                found.add(address to name)
                                names.add("$name\n$address")
                                adapter.notifyDataSetChanged()
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                handler.postDelayed({ resolve(info, attempt + 1) }, 600)
            }
        }

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                handler.post { resolve(info, 0) }
            }
        }

        val d = show(
            AlertDialog.Builder(this)
                .setTitle("Ağdaki Android TV'ler aranıyor…")
                .setAdapter(adapter) { _, i ->
                    if (found[i].first.isEmpty()) askIp() else selectDevice(found[i].first, found[i].second)
                }
                .setNeutralButton("IP adresi gir") { _, _ -> askIp() }
                .setNegativeButton("Kapat", null)
        )
        d.setOnDismissListener {
            active = false
            runCatching { nsd.stopServiceDiscovery(discovery) }
        }
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }

        // mDNS calismazsa diye: yerel agda kumanda portu acik cihazlari tara.
        thread(name = "scan") {
            val prefix = localPrefix()
            if (prefix == null) {
                handler.post { if (active) d.setTitle("Wi‑Fi bağlantısı yok – önce Wi‑Fi'a bağlanın") }
                return@thread
            }
            val alive = AtomicInteger()
            val pool = Executors.newFixedThreadPool(32)
            val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            // Iki tur: ilk turda yanit vermeyen (uykudaki/yavas) cihazlar ikinci turda yakalanir.
            for (round in 1..2) for (i in 1..254) {
                val address = "$prefix.$i"
                pool.execute {
                    if (!active || address in seen) return@execute
                    val ok = try {
                        Socket().use { it.connect(InetSocketAddress(address, AtvRemote.REMOTE_PORT), 1200) }
                        true
                    } catch (e: ConnectException) {
                        // Baglanti reddedildi: cihaz var ama kumanda portu kapali.
                        if (e.message?.contains("refused", true) == true && seen.add(address)) alive.incrementAndGet()
                        false
                    } catch (e: Exception) {
                        false
                    }
                    if (ok && seen.add(address)) alive.incrementAndGet()
                    if (ok) handler.post {
                        if (active && found.none { it.first == address }) {
                            found.add(address to "Android TV")
                            names.add("Android TV\n$address")
                            adapter.notifyDataSetChanged()
                        }
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(60, TimeUnit.SECONDS)
            handler.post {
                if (active) {
                    if (found.isNotEmpty()) {
                        d.setTitle("Cihaz seçin")
                    } else {
                        d.setTitle("TV bulunamadı")
                        found.add("" to "")
                        names.add("Taranan ağ: $prefix.x\nYanıt veren cihaz: ${alive.get()}")
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    /** Telefonun Wi-Fi IPv4 adresinin ilk uc okteti (or. "192.168.1"). */
    private fun localPrefix(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress?.substringBeforeLast('.')
    } catch (e: Exception) {
        null
    }

    private fun askIp() {
        val input = EditText(this).apply {
            hint = "192.168.1.50"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(host ?: "")
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        show(
            AlertDialog.Builder(this)
                .setTitle("TV'nin IP adresi")
                .setMessage("TV'de: Ayarlar › Ağ ve İnternet › bağlı ağ › IP adresi")
                .setView(box)
                .setPositiveButton("Bağlan") { _, _ ->
                    val ip = input.text.toString().trim()
                    if (ip.isNotEmpty()) selectDevice(ip, null)
                }
                .setNegativeButton("İptal", null)
        )
    }

    private fun show(builder: AlertDialog.Builder): AlertDialog {
        dialog?.let { if (it.isShowing) it.dismiss() }
        val d = builder.create()
        dialog = d
        d.show()
        return d
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------ tuslar

    private fun press(keyCode: Int) {
        val h = host
        if (h == null) {
            showDevicePicker()
            return
        }
        if (remote.state == AtvRemote.State.CONNECTED) {
            remote.sendKey(keyCode)
        } else if (remote.state != AtvRemote.State.CONNECTING && !pairing) {
            handler.removeCallbacks(reconnect)
            remote.connect(h)
        }
    }

    private fun launchApp(link: String) {
        if (remote.state == AtvRemote.State.CONNECTED) remote.launch(link) else press(KeyEvent.KEYCODE_UNKNOWN)
    }

    // ------------------------------------------------------------ arayuz

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun button(label: String, color: Int = BTN, textSize: Float = 22f, round: Boolean = false): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            isClickable = true
            isFocusable = true
            val shape = GradientDrawable().apply {
                setColor(color)
                cornerRadius = if (round) dp(100).toFloat() else dp(16).toFloat()
            }
            background = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), shape, shape)
        }

    /** Tek basis; repeat=true ise basili tutunca tekrarlar. */
    @SuppressLint("ClickableViewAccessibility")
    private fun key(
        label: String, keyCode: Int, repeat: Boolean = false,
        color: Int = BTN, textSize: Float = 22f, round: Boolean = false
    ): TextView {
        val b = button(label, color, textSize, round)
        if (!repeat) {
            b.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                press(keyCode)
            }
            return b
        }
        val repeater = object : Runnable {
            override fun run() {
                press(keyCode)
                handler.postDelayed(this, 110)
            }
        }
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    press(keyCode)
                    handler.postDelayed(repeater, 450)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeater)
                }
            }
            true
        }
        return b
    }

    private fun app(label: String, color: Int, link: String): TextView {
        val b = button(label, color, 12f)
        b.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            launchApp(link)
        }
        return b
    }

    private fun row(height: Int, vararg views: View?): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (v in views) {
                val child = v ?: View(this@MainActivity)
                addView(child, LinearLayout.LayoutParams(0, dp(height), 1f).apply {
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                })
            }
        }

    private fun gap(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height))
    }

    private fun buildUi(): View {
        status = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setTextColor(GRAY)
            isClickable = true
            val shape = GradientDrawable().apply {
                setColor(PANEL)
                cornerRadius = dp(14).toFloat()
            }
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, shape)
            setOnClickListener { showDevicePicker() }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))

            addView(status, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) })

            addView(row(
                58,
                key("Güç", KeyEvent.KEYCODE_POWER, color = RED, textSize = 15f),
                key("Ayarlar", KeyEvent.KEYCODE_SETTINGS, textSize = 15f),
                key("Sessiz", KeyEvent.KEYCODE_VOLUME_MUTE, textSize = 15f)
            ))
            addView(gap(8))

            // Yon tuslari
            addView(row(76, null, key("▲", KeyEvent.KEYCODE_DPAD_UP, repeat = true), null))
            addView(row(
                76,
                key("◀︎", KeyEvent.KEYCODE_DPAD_LEFT, repeat = true),
                key("OK", KeyEvent.KEYCODE_DPAD_CENTER, color = ACCENT, round = true),
                key("▶︎", KeyEvent.KEYCODE_DPAD_RIGHT, repeat = true)
            ))
            addView(row(76, null, key("▼", KeyEvent.KEYCODE_DPAD_DOWN, repeat = true), null))
            addView(gap(8))

            addView(row(
                58,
                key("Geri", KeyEvent.KEYCODE_BACK, textSize = 16f),
                key("Ana Ekran", KeyEvent.KEYCODE_HOME, textSize = 16f),
                key("Menü", KeyEvent.KEYCODE_MENU, textSize = 16f)
            ))
            addView(row(
                58,
                key("Ses −", KeyEvent.KEYCODE_VOLUME_DOWN, repeat = true, textSize = 17f),
                key("Asistan", KeyEvent.KEYCODE_SEARCH, textSize = 15f),
                key("Ses +", KeyEvent.KEYCODE_VOLUME_UP, repeat = true, textSize = 17f)
            ))
            addView(row(
                54,
                key("⏮︎", KeyEvent.KEYCODE_MEDIA_PREVIOUS),
                key("⏪︎", KeyEvent.KEYCODE_MEDIA_REWIND),
                key("⏯︎", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
                key("⏩︎", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD),
                key("⏭︎", KeyEvent.KEYCODE_MEDIA_NEXT)
            ))
            addView(gap(8))
            addView(row(
                50,
                app("YouTube", 0xFFC62828.toInt(), "https://www.youtube.com"),
                app("Netflix", 0xFF8E0E13.toInt(), "https://www.netflix.com/title"),
                app("Prime", 0xFF1565C0.toInt(), "https://app.primevideo.com"),
                app("Disney+", 0xFF1A237E.toInt(), "https://www.disneyplus.com")
            ))
        }

        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BG)
            addView(column)
        }
    }

    companion object {
        private const val SERVICE_TYPE = "_androidtvremote2._tcp."
        private const val BG = 0xFF121418.toInt()
        private const val PANEL = 0xFF1B1F26.toInt()
        private const val BTN = 0xFF272C36.toInt()
        private const val ACCENT = 0xFFFF6900.toInt()
        private const val RED = 0xFFB3261E.toInt()
        private const val GREEN = 0xFF7BD88F.toInt()
        private const val GRAY = 0xFFB0B6C0.toInt()
    }
}
