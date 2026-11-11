package com.nikhil.niktv.discovery

import android.content.res.Configuration
import com.nikhil.niktv.ui.usesPhoneLayout

internal fun usesDiscoveryPhoneLayout(configuration: Configuration, television: Boolean): Boolean =
    configuration.usesPhoneLayout(television)
