package com.ishansh.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged

class MainActivity : AppCompatActivity() {

    private lateinit var reactor: ReactorView
    private lateinit var status: TextView

    private val perms: Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0B1038")) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(20), px(40), px(20), px(70))
        }
        root.addView(ScrollView(this).apply { addView(col) })

        col.addView(TextView(this).apply {
            text = "J.A.R.V.I.S."
            textSize = 28f
            setTextColor(Color.parseColor("#4DE3FF"))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
        })

        reactor = ReactorView(this)
        reactor.setOnClickListener { toggle() }
        col.addView(reactor, LinearLayout.LayoutParams(px(260), px(260)).apply { topMargin = px(16) })

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#A9B4D0"))
            gravity = Gravity.CENTER
            textSize = 15f
        }
        col.addView(status)

        fun btn(label: String, action: () -> Unit) {
            col.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) })
        }

        btn("1. Allow microphone, contacts and calls") {
            ActivityCompat.requestPermissions(this, perms, 1)
        }
        btn("2. Allow display over other apps") {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Already allowed", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        btn("3. Allow background running") {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "Already allowed", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }

        col.addView(EditText(this).apply {
            hint = "Paste your free Gemini API key"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
            doAfterTextChanged { prefs.edit().putString("key", it.toString().trim()).apply() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(14) })

        col.addView(Switch(this).apply {
            text = "Deeper voice (restart Jarvis after changing)"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("deep", true)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("deep", checked).apply() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(14) })

        root.addView(TextView(this).apply {
            text = "Created by Ishansh Sharma"
            setTextColor(Color.parseColor("#F5B301"))
            typeface = Typeface.DEFAULT_BOLD
            textSize = 13f
            setPadding(px(12), px(6), px(12), px(6))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, px(10), px(10))
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun hasPerms() = perms.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun toggle() {
        val svc = Intent(this, JarvisService::class.java)
        if (JarvisService.running) {
            stopService(svc)
        } else {
            if (!hasPerms()) {
                status.text = "Tap button 1 first and allow everything."
                return
            }
            ContextCompat.startForegroundService(this, svc)
        }
        reactor.postDelayed({ refresh() }, 400)
    }

    private fun refresh() {
        reactor.active = JarvisService.running
        status.text = if (JarvisService.running) "Listening in the background. Say \"Hi Jarvis\"." else "Tap the reactor to start Jarvis."
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }
}
