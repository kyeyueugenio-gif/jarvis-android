package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "Jarvis setup"
        title.textSize = 24f
        root.addView(title)

        status = TextView(this)
        status.setPadding(0, pad, 0, pad)
        root.addView(status)

        val keyBox = EditText(this)
        keyBox.hint = "Paste Gemini API key"
        keyBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        root.addView(keyBox)

        root.addView(makeButton("1. Save API key") {
            val k = keyBox.text.toString().trim()
            if (k.isNotEmpty()) {
                prefs.edit().putString("gemini_key", k).apply()
                keyBox.setText("")
                Toast.makeText(this, "Key saved", Toast.LENGTH_SHORT).show()
                refresh()
            }
        })

        root.addView(makeButton("2. Grant mic, calls, contacts") {
            val list = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CONTACTS
            )
            if (Build.VERSION.SDK_INT >= 33) {
                list.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            requestPermissions(list.toTypedArray(), 1)
        })

        root.addView(makeButton("3. Allow draw over other apps") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)
                )
            )
        })

        root.addView(makeButton("4. Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        root.addView(makeButton("5. Set battery to unrestricted") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun makeButton(label: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setOnClickListener { action() }
        return b
    }

    private fun refresh() {
        val prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val needed = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        val permsOk = needed.all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        fun yn(b: Boolean): String = if (b) "Yes" else "Not yet"
        status.text = "API key saved: " + yn(prefs.contains("gemini_key")) + "\n" +
            "Mic, calls, contacts: " + yn(permsOk) + "\n" +
            "Draw over apps: " + yn(Settings.canDrawOverlays(this)) + "\n" +
            "Battery unrestricted: " + yn(pm.isIgnoringBatteryOptimizations(packageName))
    }
}
