package com.universalglasses.device.sim

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SimDisplayActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sim_display)

        val tv = findViewById<TextView>(R.id.tvText)
        tv.text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
    }

    companion object {
        private const val EXTRA_TEXT = "text"

        fun newIntent(context: Context, text: String): Intent {
            return Intent(context, SimDisplayActivity::class.java).apply {
                putExtra(EXTRA_TEXT, text)
            }
        }
    }
}
