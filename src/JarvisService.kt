package com.ishansh.jarvis

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class JarvisService : Service(), TextToSpeech.OnInitListener {

    companion object {
        @Volatile
        var running = false
    }

    private enum class Mode { WAKE, COMMAND, CONFIRM }

    private var mode = Mode.WAKE
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val main = Handler(Looper.getMainLooper())
    private var pending: (() -> Unit)? = null
    private var afterSpeech: () -> Unit = { listen() }
    private var speaking = false
    private val history = JSONArray()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        running = true
        val channel = "jarvis"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(channel, "Jarvis", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val note = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis is listening")
            .setContentText("Say \"Hi Jarvis\"")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        tts = TextToSpeech(this, this)
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------- Voice out ----------

    override fun onInit(status: Int) {
        val t = tts ?: return
        t.language = Locale.US
        val deep = getSharedPreferences("jarvis", MODE_PRIVATE).getBoolean("deep", true)
        t.setPitch(if (deep) 0.8f else 1f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { finishSpeech() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { finishSpeech() } }
        })
        say("Jarvis is online.")
    }

    private fun finishSpeech() {
        speaking = false
        val next = afterSpeech
        afterSpeech = { listen() }
        next()
    }

    private fun say(text: String, after: () -> Unit = { listen() }) {
        recognizer?.cancel()
        speaking = true
        afterSpeech = after
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jv")
    }

    // ---------- Voice in ----------

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            handleText(text)
        }

        override fun onError(error: Int) {
            if (mode != Mode.WAKE && (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH)) {
                mode = Mode.WAKE
                pending = null
            }
            val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500L else 300L
            main.postDelayed({ listen() }, delay)
        }
    }

    private fun listen() {
        if (speaking || !running) return
        main.post {
            recognizer?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            r.setRecognitionListener(listener)
            recognizer = r
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            })
        }
    }

    private fun handleText(raw: String) {
        val text = raw.lowercase(Locale.US).trim()
        when (mode) {
            Mode.WAKE -> {
                val m = Regex("""(?:hi|hey|hello|ok|okay)?\s*(?:jarvis|jarvas|jarvice|jar vis)\b[,. ]*(.*)""").find(text)
                if (m == null) {
                    listen()
                    return
                }
                val rest = m.groupValues[1].trim()
                if (rest.isEmpty()) {
                    mode = Mode.COMMAND
                    say("Yes?")
                } else {
                    think(rest)
                }
            }
            Mode.COMMAND -> think(text)
            Mode.CONFIRM -> {
                mode = Mode.WAKE
                val yes = Regex("""\b(yes|yeah|yep|sure|ok|okay|confirm|go ahead|do it)\b""").containsMatchIn(text)
                val action = pending
                pending = null
                if (yes && action != null) {
                    action()
                    listen()
                } else {
                    say("Okay, cancelled.")
                }
            }
        }
    }

    // ---------- Brain ----------

    private fun think(text: String) {
        mode = Mode.WAKE
        val key = (getSharedPreferences("jarvis", MODE_PRIVATE).getString("key", "") ?: "").trim()
        if (key.isBlank()) {
            say("Please add your Gemini key in the Jarvis app.")
            return
        }
        recognizer?.cancel()
        thread {
            val reply = try {
                askGemini(key, text)
            } catch (e: Exception) {
                "Sorry. " + (e.message ?: "Something went wrong.").take(140)
            }
            main.post { act(reply) }
        }
    }

    private fun askGemini(key: String, text: String): String {
        history.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
        while (history.length() > 10) history.remove(0)
        while (history.length() > 0 && history.getJSONObject(0).getString("role") != "user") history.remove(0)

        val now = SimpleDateFormat("EEEE, MMMM d, h:mm a", Locale.US).format(Date())
        val system = """
            You are Jarvis, a warm, friendly voice assistant on the user's Android phone.
            Reply in one or two short spoken sentences, no markdown. Current time: $now.
            You can act on the phone, but only when the user asks. Add exactly one tag at the end of your reply:
            [[CALL:contact name]] to phone someone, [[TEXT:contact name|message]] to text someone,
            [[OPEN:app name]] to open an app, [[MAP:place]] for directions, [[SEARCH:query]] to search the web.
            The phone asks the user to confirm calls and texts, so do not ask yourself.
        """.trimIndent()

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", history)
            .toString()

        val models = listOf("gemini-2.5-flash", "gemini-flash-latest", "gemini-2.5-flash-lite")
        var lastError = "Unknown error"
        for (model in models) {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.doOutput = true
            c.connectTimeout = 15000
            c.readTimeout = 25000
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val resp = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
            if (code in 200..299) {
                val out = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                history.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", out))))
                return out
            }
            lastError = try {
                JSONObject(resp).getJSONObject("error").getString("message")
            } catch (e: Exception) {
                "Google returned error $code"
            }
        }
        throw Exception(lastError)
    }

    // ---------- Actions ----------

    private fun act(reply: String) {
        val tag = Regex("""\[\[(CALL|TEXT|OPEN|MAP|SEARCH):([^\]]*)\]\]""")
        val m = tag.find(reply)
        val speech = reply.replace(tag, "").trim().ifEmpty { "Okay." }
        if (m == null) {
            say(speech)
            return
        }
        val kind = m.groupValues[1]
        val parts = m.groupValues[2].split("|")
        val who = parts[0].trim()
        val extra = parts.getOrNull(1)?.trim() ?: ""

        when (kind) {
            "CALL" -> {
                val number = lookupNumber(who)
                if (number == null) {
                    say("I couldn't find $who in your contacts.")
                } else {
                    mode = Mode.CONFIRM
                    pending = { dial(number) }
                    say("Call $who? Say yes to confirm.")
                }
            }
            "TEXT" -> {
                val number = lookupNumber(who)
                if (number == null) {
                    say("I couldn't find $who in your contacts.")
                } else {
                    mode = Mode.CONFIRM
                    pending = {
                        launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", extra))
                    }
                    say("Text $who: $extra. Say yes to confirm.")
                }
            }
            "OPEN" -> say("Opening $who.") {
                if (!openApp(who)) say("I couldn't find an app called $who.") else listen()
            }
            "MAP" -> say(speech) {
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(who))))
                listen()
            }
            else -> say(speech) {
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(who))))
                listen()
            }
        }
    }

    private fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            say("I couldn't open that.")
        }
    }

    private fun dial(number: String) {
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        launch(Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri))
    }

    private fun lookupNumber(name: String): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val cursor = contentResolver.query(
            phone,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$name%"),
            null
        ) ?: return null
        cursor.use {
            if (it.moveToFirst()) return it.getString(1)
        }
        return null
    }

    private fun openApp(name: String): Boolean {
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(query, 0)
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().equals(name, true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().contains(name, true) }
            ?: return false
        val intent = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return false
        launch(intent)
        return true
    }
}
