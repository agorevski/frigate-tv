package com.frigatetv.app

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.frigatetv.app.ui.FrigateTvApp
import com.frigatetv.app.ui.FrigateTvTheme
import com.frigatetv.app.ui.Ink
import com.frigatetv.app.ui.SplashScreen

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Monitoring is a long-running foreground task; keep the screen on while the app is open.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            FrigateTvTheme {
                // The app starts immediately; the splash is an overlay that is removed after its hold.
                var showSplash by rememberSaveable { mutableStateOf(true) }
                Box(Modifier.fillMaxSize()) {
                    FrigateTvApp(viewModel)
                    if (showSplash) {
                        SplashScreen { showSplash = false }
                    } else {
                        LaunchedEffect(Unit) { dropSplashWindowBackground() }
                    }
                }
            }
        }
    }

    /**
     * The window background holds the splash (art in landscape, flat backdrop in portrait) for cold
     * start. Once Compose covers the window, swap it for a flat color so the bitmap is not redrawn
     * under every frame.
     */
    private fun dropSplashWindowBackground() {
        window.setBackgroundDrawable(ColorDrawable(Ink.toArgb()))
    }

    override fun onStart() {
        super.onStart()
        viewModel.setForeground(true)
    }

    override fun onStop() {
        viewModel.setForeground(false)
        super.onStop()
    }
}
