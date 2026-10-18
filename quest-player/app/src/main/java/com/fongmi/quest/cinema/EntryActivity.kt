package com.fongmi.quest.cinema

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Normal 2D launcher and FongMi external-player receiver; hands playback to the VR activity. */
class EntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND) {
            val uri = if (intent.action == Intent.ACTION_SEND) {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let(Uri::parse)
            } else intent.data
            if (!supported(uri)) {
                Toast.makeText(this, R.string.cinema_bad_request, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            openCinema(Intent(intent).setDataAndType(uri, intent.type), forwardResult = true)
            return
        }
        showLauncher()
    }

    private fun supported(uri: Uri?) = uri?.scheme?.lowercase() in setOf("http", "https", "content", "file")

    private fun showLauncher() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.rgb(14, 18, 26))
        }
        panel.addView(TextView(this).apply {
            text = getString(R.string.quest_cinema_title)
            textSize = 32f
            setTextColor(Color.WHITE)
        })
        panel.addView(TextView(this).apply {
            text = getString(R.string.cinema_intro)
            textSize = 20f
            setTextColor(Color.LTGRAY)
            setPadding(0, 24, 0, 24)
        })
        val input = EditText(this).apply {
            hint = getString(R.string.cinema_url_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
        }
        panel.addView(input)
        panel.addView(Button(this).apply {
            setText(R.string.cinema_open_url)
            setOnClickListener {
                val uri = Uri.parse(input.text.toString().trim())
                if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                    input.error = getString(R.string.cinema_bad_url)
                } else openCinema(Intent(Intent.ACTION_VIEW, uri), forwardResult = false)
            }
        })
        panel.addView(Button(this).apply {
            setText(R.string.cinema_scene_preview)
            setOnClickListener {
                startActivity(Intent(this@EntryActivity, StandaloneCinemaActivity::class.java)
                    .putExtra("preview_scene", true).addCategory("com.oculus.intent.category.VR"))
            }
        })
        panel.addView(Button(this).apply {
            setText(R.string.cinema_open_file)
            setOnClickListener {
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "video/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivityForResult(picker, 10)
            }
        })
        setContentView(panel)
    }

    private fun openCinema(source: Intent, forwardResult: Boolean) {
        val target = Intent(source).setClass(this, StandaloneCinemaActivity::class.java)
        val grantFlags = source.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        target.flags = grantFlags or if (forwardResult) Intent.FLAG_ACTIVITY_FORWARD_RESULT else 0
        target.addCategory("com.oculus.intent.category.VR")
        startActivity(target)
        if (forwardResult) finish()
    }

    @Deprecated("Legacy document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10 && resultCode == RESULT_OK && data?.data != null) {
            openCinema(Intent(Intent.ACTION_VIEW, data.data).setFlags(data.flags), forwardResult = false)
        }
    }
}
