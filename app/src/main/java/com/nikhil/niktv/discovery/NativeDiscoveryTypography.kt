package com.nikhil.niktv.discovery

import android.graphics.Typeface
import android.os.Build

/** Match Compose's explicit SemiBold captions; never inherit a native theme's bold face. */
internal val discoveryCaptionTypeface: Typeface by lazy {
    if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 600, false)
    else Typeface.create("sans-serif-medium", Typeface.NORMAL)
}
