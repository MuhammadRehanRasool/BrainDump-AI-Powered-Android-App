package dev.braindump.splitter.ui.capture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import dev.braindump.splitter.ui.theme.BrainDumpTheme

/** Transparent host for [CaptureSheet], launched from the tile and launcher shortcuts. */
@AndroidEntryPoint
class CaptureActivity : ComponentActivity() {

    private val viewModel: CaptureViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startWithVoice = intent.getBooleanExtra(EXTRA_START_WITH_VOICE, false)
        setContent {
            BrainDumpTheme {
                CaptureSheet(
                    startWithVoice = startWithVoice,
                    onSubmit = { idea ->
                        viewModel.submit(idea)
                        finish()
                    },
                    onDismiss = { if (!isFinishing) finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_START_WITH_VOICE = "start_with_voice"

        fun intent(context: Context, startWithVoice: Boolean = false): Intent =
            Intent(context, CaptureActivity::class.java)
                .putExtra(EXTRA_START_WITH_VOICE, startWithVoice)
    }
}
