package io.github.woodypl.doubletake

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dtcore.mobile.Mobile

class MainActivity : Activity(), MirrorController.Observer {
    companion object {
        private const val REQUEST_PERMISSIONS = 1
        private const val REQUEST_PROJECTION = 2
        private const val REQUEST_SAVE_LOG = 3
        private const val LOG_LINES_SHOWN = 400
    }

    private lateinit var hostField: EditText
    private lateinit var audioBox: CheckBox
    private lateinit var debugBox: CheckBox
    private lateinit var repairBox: CheckBox
    private lateinit var bitrateField: EditText
    private lateinit var latencyField: EditText
    private lateinit var volumeField: EditText
    private lateinit var startButton: Button
    private lateinit var statusText: TextView
    private lateinit var receiversRow: LinearLayout
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private var discovery: Discovery? = null
    private var codeDialog: AlertDialog? = null

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MirrorController.init()
        buildUi()
        hostField.setText(prefs.getString("host", ""))
        audioBox.isChecked = prefs.getBoolean("audio", true)
        debugBox.isChecked = prefs.getBoolean("debug", true)
        bitrateField.setText(prefs.getInt("bitrate", 8000).toString())
        latencyField.setText(prefs.getInt("latency", 0).toString())
        volumeField.setText(prefs.getString("volume", "keep"))
    }

    override fun onStart() {
        super.onStart()
        MirrorController.addObserver(this)
        logText.text = MirrorController.recentLog(LOG_LINES_SHOWN).joinToString("\n")
        scrollLogToEnd()
        onStateChanged(MirrorController.state, MirrorController.stateDetail)
        MirrorController.pendingCodeKind?.let { onCodeRequested(it) }
        discovery = Discovery(this) { showReceivers(it) }.also { it.start() }
    }

    override fun onStop() {
        MirrorController.removeObserver(this)
        discovery?.stop()
        discovery = null
        super.onStop()
    }

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(text: String) = TextView(this).apply { this.text = text; setPadding(0, pad / 2, 0, 0) }

        column.addView(label("Receiver IP address (or pick one found below)"))
        hostField = EditText(this).apply {
            hint = "192.168.1.50"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        column.addView(hostField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        receiversRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(receiversRow)

        audioBox = CheckBox(this).apply { text = "Send audio" }
        debugBox = CheckBox(this).apply { text = "Verbose protocol log" }
        repairBox = CheckBox(this).apply { text = "Pair again (ignore saved pairing)" }
        column.addView(audioBox)
        column.addView(debugBox)
        column.addView(repairBox)

        val numbers = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bitrateField = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "kbps" }
        latencyField = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "ms" }
        numbers.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Bitrate (kbps)"))
            addView(bitrateField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        numbers.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Latency (ms, 0 = auto)"))
            addView(latencyField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        column.addView(numbers)

        column.addView(label("Projector volume on connect: keep, or -30 (quiet) to 0 (full) dB"))
        volumeField = EditText(this).apply {
            hint = "keep"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        column.addView(volumeField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        startButton = Button(this).apply { setOnClickListener { onStartStop() } }
        column.addView(startButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        statusText = TextView(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, pad / 2, 0, pad / 2)
            textSize = 16f
        }
        column.addView(statusText)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        actions.addView(action("Share log") { shareLog() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        actions.addView(action("Save log") { saveLog() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        actions.addView(action("Copy log") { copyLog() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        actions.addView(action("Forget pairing") { forgetPairing() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        column.addView(actions)

        logText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply { addView(logText) }
        column.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // targetSdk 35+ draws edge to edge; keep content clear of the bars and keyboard.
        column.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(column)
    }

    private fun showReceivers(receivers: List<Receiver>) {
        receiversRow.removeAllViews()
        for (r in receivers) {
            receiversRow.addView(Button(this).apply {
                text = "${r.name}  (${r.host})"
                isAllCaps = false
                setOnClickListener { hostField.setText(r.host) }
            })
        }
    }

    // --- Start / stop ---------------------------------------------------------

    private fun onStartStop() {
        if (MirrorController.isActive) {
            startService(Intent(this, MirrorService::class.java).setAction(MirrorService.ACTION_STOP))
            return
        }
        if (hostField.text.toString().trim().isEmpty()) {
            Toast.makeText(this, "Enter the projector's IP address", Toast.LENGTH_SHORT).show()
            return
        }
        saveSettings()
        val needed = mutableListOf(Manifest.permission.POST_NOTIFICATIONS)
        if (audioBox.isChecked) needed += Manifest.permission.RECORD_AUDIO
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            requestProjection()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != REQUEST_PERMISSIONS) return
        val audioDenied = permissions.indices.any {
            permissions[it] == Manifest.permission.RECORD_AUDIO && results[it] != PackageManager.PERMISSION_GRANTED
        }
        if (audioDenied) {
            MirrorController.log("audio permission denied; mirroring without sound")
            audioBox.isChecked = false
        }
        requestProjection()
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    @Deprecated("Activity result API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_PROJECTION -> {
                if (resultCode != RESULT_OK || data == null) {
                    MirrorController.log("screen capture permission declined")
                    return
                }
                val host = hostField.text.toString().trim()
                val (address, port) = splitHostPort(host)
                val intent = Intent(this, MirrorService::class.java)
                    .setAction(MirrorService.ACTION_START)
                    .putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(MirrorService.EXTRA_RESULT_DATA, data)
                    .putExtra(MirrorService.EXTRA_HOST, address)
                    .putExtra(MirrorService.EXTRA_PORT, port)
                    .putExtra(MirrorService.EXTRA_AUDIO, audioBox.isChecked)
                    .putExtra(MirrorService.EXTRA_DEBUG, debugBox.isChecked)
                    .putExtra(MirrorService.EXTRA_FORCE_PAIR, repairBox.isChecked)
                    .putExtra(MirrorService.EXTRA_BITRATE, bitrateField.text.toString().toIntOrNull() ?: 8000)
                    .putExtra(MirrorService.EXTRA_LATENCY, latencyField.text.toString().toIntOrNull() ?: 0)
                    .putExtra(MirrorService.EXTRA_VOLUME, volumeSetting())
                repairBox.isChecked = false
                startForegroundService(intent)
            }
            REQUEST_SAVE_LOG -> {
                val uri = data?.data ?: return
                if (resultCode != RESULT_OK) return
                contentResolver.openOutputStream(uri)?.use { it.write(MirrorController.logText().toByteArray()) }
                Toast.makeText(this, "Log saved", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** "keep" unless the field holds something; the Go side validates it. */
    private fun volumeSetting(): String = volumeField.text.toString().trim().ifEmpty { "keep" }

    private fun splitHostPort(text: String): Pair<String, Int> {
        val colon = text.lastIndexOf(':')
        if (colon > 0 && text.indexOf(':') == colon) {
            text.substring(colon + 1).toIntOrNull()?.let { return text.substring(0, colon) to it }
        }
        return text to 7000
    }

    private fun saveSettings() {
        prefs.edit()
            .putString("host", hostField.text.toString().trim())
            .putBoolean("audio", audioBox.isChecked)
            .putBoolean("debug", debugBox.isChecked)
            .putInt("bitrate", bitrateField.text.toString().toIntOrNull() ?: 8000)
            .putInt("latency", latencyField.text.toString().toIntOrNull() ?: 0)
            .putString("volume", volumeSetting())
            .apply()
    }

    // --- Observer -------------------------------------------------------------

    override fun onStateChanged(state: String, detail: String) {
        val active = MirrorController.isActive
        startButton.text = if (active) "Stop mirroring" else "Start mirroring"
        statusText.text = when (state) {
            Mobile.StateConnecting -> "Connecting…"
            Mobile.StatePairing -> "Pairing…"
            Mobile.StateNeedCode -> "Waiting for code…"
            Mobile.StateSettingUp -> "Setting up the stream…"
            Mobile.StateStreaming -> "Mirroring"
            Mobile.StateFailed -> "Failed: $detail"
            else -> "Idle"
        }
        val editable = !active
        listOf(hostField, audioBox, debugBox, repairBox, bitrateField, latencyField, volumeField).forEach { it.isEnabled = editable }
        if (state != Mobile.StateNeedCode) {
            codeDialog?.dismiss()
            codeDialog = null
        }
    }

    override fun onCodeRequested(kind: String) {
        if (codeDialog?.isShowing == true) return
        val isPin = kind == Mobile.CodePIN
        val input = EditText(this).apply {
            inputType = if (isPin) InputType.TYPE_CLASS_NUMBER
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        codeDialog = AlertDialog.Builder(this)
            .setTitle(if (isPin) "Enter the PIN shown on the projector" else "Enter the receiver's password")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> MirrorController.submitCode(input.text.toString().trim()) }
            .setNegativeButton("Cancel") { _, _ -> MirrorController.submitCode("") }
            .show()
        input.requestFocus()
    }

    override fun onLog(line: String) {
        logText.append(if (logText.length() == 0) line else "\n$line")
        if (logText.lineCount > LOG_LINES_SHOWN * 2) {
            logText.text = MirrorController.recentLog(LOG_LINES_SHOWN).joinToString("\n")
        }
        scrollLogToEnd()
    }

    private fun scrollLogToEnd() = logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }

    // --- Log and pairing actions ---------------------------------------------

    private fun shareLog() {
        try {
            val uri = LogProvider.writeSnapshot(this, MirrorController.logText())
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Doubletake log")
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("Doubletake log", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            MirrorController.log("share log failed: $e")
            Toast.makeText(this, "Sharing failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveLog() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "doubletake-android.log")
        }, REQUEST_SAVE_LOG)
    }

    private fun copyLog() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Doubletake log", MirrorController.logText().takeLast(400_000)))
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
    }

    private fun forgetPairing() {
        if (MirrorController.isActive) {
            Toast.makeText(this, "Stop mirroring first", Toast.LENGTH_SHORT).show()
            return
        }
        val deleted = MirrorService.credentialsFile(this).delete()
        MirrorController.log(if (deleted) "saved pairing deleted" else "no saved pairing to delete")
    }
}
