package dev.braindump.splitter.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.braindump.splitter.ui.dashboard.DashboardRoute
import dev.braindump.splitter.ui.theme.BrainDumpTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BrainDumpTheme {
                DashboardRoute()
            }
        }
    }
}
