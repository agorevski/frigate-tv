package com.frigatetv.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import com.frigatetv.app.R
import kotlinx.coroutines.delay

/**
 * Launch artwork drawn edge to edge over the already-composing app. Landscape (TV) uses the 16:9
 * art, which matches the window background exactly; portrait resolves a lens-centred variant
 * (drawable-port-nodpi) so the center-crop never cuts the lens off. Both sit on the art's edge
 * colour, which is also the system-splash and portrait window colour, so the handoff is seamless.
 * Holds briefly, fades out, then calls [onFinished]. The app underneath connects in parallel, so
 * the hold costs no startup time.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        delay(Motion.SplashHoldMs.toLong())
        alpha.animateTo(0f, tween(Motion.ExitMs))
        onFinished()
    }
    Image(
        painter = painterResource(R.drawable.splash),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        // Alpha is read in the draw phase only, so the fade never recomposes.
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            .background(colorResource(R.color.splash_backdrop)),
    )
}
