package com.jarvis.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class JarvisService : AccessibilityService() {

    companion object {
        const val MODEL = "gemini-3.8-flash"
        const val PROMPT = """You are Jarvis, a voice assistant on an Android tablet.
Turn the user's request into ONE JSON object and output only that JSON.
Allowed actions:
{"action":"open_app","app":"<app name>"}
{"action":"call","contact":"<contact name>"}
{"action":"whatsapp","contact":"<contact name>","message":"<text to send>"}
{"action":"back"}
{"action":"home"}
{"action":"recents"}
{"action":"say","text":"<short spoken reply>"}
Use say for questions, chat, or anything you cannot do. Keep spoken replies under 30 words."""
    }

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var bubble: TextView? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var listening = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
            }
        }
        addBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        bubble?.let {
            try {
                wm?.removeView(it)
            } catch (e: Exception) {
            }
        }
        bubble = null
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------- Floating bubble ----------

    private fun addBubble() {
        if (bubble != null) return
        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()

        val v = TextView(this)
        v.text = "J"
        v.textSize = 22f
        v.setTextColor(Color.WHITE)
        v.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor("#1565C0"))
        v.background = bg

        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (16 * density).toInt()
        lp.y = (200 * density).toInt()

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        var downTime = 0L

        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                    downTime = e.eventTime
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        wm?.updateViewLayout(v, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (e.eventTime - downTime > 800) {
                            stopJarvis()
                        } else {
                            startListening()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        wm?.addView(v, lp)
        bubble = v
    }

    private fun setListening(on: Boolean) {
        listening = on
        bubble?.text = if (on) "..." else "J"
    }

    private fun stopJarvis() {
        confirm("Turn Jarvis off? You can switch it back on in Accessibility settings.") {
            disableSelf()
        }
    }

    private fun confirm(text: String, onYes: () -> Unit) {
        val ctx = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog_Alert)
        val dialog = AlertDialog.Builder(ctx)
            .setMessage(text)
            .setPositiveButton("Yes") { _, _ -> onYes() }
            .setNegativeButton("No", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dialog.show()
    }

    private fun tell(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    // ---------- Listening ----------

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            tell("Please grant the microphone permission in the Jarvis app")
            return
        }
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            tell("Speech recognition is not available on this device")
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    setListening(false)
                    tell("I did not catch that (error " + error + ")")
                }

                override fun onResults(results: Bundle?) {
                    setListening(false)
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val said = list?.firstOrNull()
                    if (said.isNullOrBlank()) {
                        tell("I did not catch that")
                    } else {
                        handleSpeech(said)
                    }
                }
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        setListening(true)
        recognizer?.startListening(intent)
    }

    // ---------- Gemini ----------

    private fun handleSpeech(said: String) {
        val key = getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("gemini_key", null)
        if (key.isNullOrBlank()) {
            tell("Open the Jarvis app and save your Gemini key first")
            return
        }
        Toast.makeText(this, "You said: " + said, Toast.LENGTH_SHORT).show()
        Thread {
            val reply: JSONObject? = try {
                askGemini(key, said)
            } catch (e: Exception) {
                null
            }
            main.post {
                if (reply == null) {
                    tell("I could not reach Gemini")
                } else {
                    runCommand(reply)
                }
            }
        }.start()
    }

    private fun askGemini(key: String, said: String): JSONObject? {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent")

        val body = JSONObject()
        body.put(
            "systemInstruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", PROMPT)))
        )
        body.put(
            "contents",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", said)))
            )
        )
        body.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", key)
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        conn.disconnect()

        if (code !in 200..299) {
            return JSONObject().put("action", "say").put("text", "Gemini returned error " + code)
        }
        val resp = JSONObject(text)
        val out = resp.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
            .getString("text")
        return JSONObject(out)
    }

    // ---------- Actions ----------

    private fun runCommand(cmd: JSONObject) {
        when (cmd.optString("action")) {
            "open_app" -> openApp(cmd.optString("app"))

            "call" -> {
                val name = cmd.optString("contact")
                val number = findNumber(name)
                if (number == null) {
                    tell("I could not find " + name + " in your contacts")
                } else {
                    confirm("Call " + name + "?") {
                        val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number))
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(i)
                    }
                }
            }

            "whatsapp" -> {
                val name = cmd.optString("contact")
                val msg = cmd.optString("message")
                val number = findNumber(name)
                if (number == null) {
                    tell("I could not find " + name + " in your contacts")
                } else {
                    confirm("Send this WhatsApp message to " + name + "? " + msg) {
                        val digits = number.replace(Regex("[^0-9]"), "")
                        val i = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://wa.me/" + digits + "?text=" + Uri.encode(msg))
                        )
                        i.setPackage("com.whatsapp")
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val ok = try {
                            startActivity(i)
                            true
                        } catch (e: Exception) {
                            false
                        }
                        if (ok) {
                            main.postDelayed({ pressSend() }, 3000)
                        } else {
                            tell("WhatsApp is not installed")
                        }
                    }
                }
            }

            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)

            else -> tell(cmd.optString("text", "Sorry, I did not get that"))
        }
    }

    private fun openApp(name: String) {
        if (name.isBlank()) {
            tell("Which app?")
            return
        }
        val query = Intent(Intent.ACTION_MAIN)
        query.addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(query, 0)
        val wanted = name.lowercase()
        var match = apps.firstOrNull { it.loadLabel(packageManager).toString().lowercase() == wanted }
        if (match == null) {
            match = apps.firstOrNull { it.loadLabel(packageManager).toString().lowercase().contains(wanted) }
        }
        if (match == null) {
            tell("I could not find an app called " + name)
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(match.activityInfo.packageName)
        if (launch == null) {
            tell("I could not open " + name)
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
    }

    private fun findNumber(name: String): String? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%" + name + "%"),
            null
        ) ?: return null
        var number: String? = null
        cursor.use {
            if (it.moveToFirst()) {
                number = it.getString(0)
            }
        }
        return number
    }

    private fun pressSend() {
        val root = rootInActiveWindow
        if (root == null) {
            tell("I could not see the screen")
            return
        }
        val node = findSend(root)
        if (node != null) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            tell("I could not find the Send button")
        }
    }

    private fun findSend(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        if (node.isClickable && (desc.equals("Send", ignoreCase = true) || id.endsWith(":id/send"))) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = findSend(node.getChild(i))
            if (found != null) return found
        }
        return null
    }
}
