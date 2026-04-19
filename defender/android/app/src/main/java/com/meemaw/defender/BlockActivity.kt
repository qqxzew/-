package com.meemaw.defender

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.meemaw.defender.databinding.ActivityBlockBinding

/**
 * Full-screen red warning for score ≥ 90.
 * Blocks interaction until user acknowledges.
 */
class BlockActivity : AppCompatActivity() {

    private lateinit var b: ActivityBlockBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lock screen
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        b = ActivityBlockBinding.inflate(layoutInflater)
        setContentView(b.root)

        val explanation = intent.getStringExtra(EXTRA_EXPLANATION).orEmpty()
        val original    = intent.getStringExtra(EXTRA_ORIGINAL).orEmpty()
        val score       = intent.getIntExtra(EXTRA_SCORE, 0)

        b.txtScore.text       = getString(R.string.block_score, score)
        b.txtExplanation.text = explanation.ifBlank { getString(R.string.block_default_explanation) }
        b.txtOriginal.text    = original

        b.btnOk.setOnClickListener { finish() }
    }

    // Do not allow back press to dismiss
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() { /* ignore */ }

    companion object {
        const val EXTRA_EXPLANATION = "extra_explanation"
        const val EXTRA_ORIGINAL    = "extra_original"
        const val EXTRA_SCORE       = "extra_score"
    }
}
