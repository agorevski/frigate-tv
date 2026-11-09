package com.frigatetv.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.frigatetv.app.data.frigateImageLoader

/** Supplies the app-wide Coil loader, so every image request uses the shared HTTP client. */
class FrigateTvApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = frigateImageLoader(this)
}
