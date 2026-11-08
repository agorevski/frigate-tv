package com.frigatetv.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.frigatetv.app.ui.FrigateTvApp
import com.frigatetv.app.ui.FrigateTvTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Monitoring is a long-running foreground task; keep the screen on while the app is open.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            FrigateTvTheme {
                FrigateTvApp(viewModel)
            }
        }
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
