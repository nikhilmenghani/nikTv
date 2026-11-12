package com.nikhil.niktv.ui

import com.nikhil.niktv.model.PortalProfile

/** Callers supply only connections allowed for the viewer. A removed last-used
 * connection falls back directly to an available one instead of a landing page. */
internal fun preferredViewerPortal(available: List<PortalProfile>, rememberedKey: String?): PortalProfile? =
    available.firstOrNull { it.cacheKey() == rememberedKey } ?: available.firstOrNull()
