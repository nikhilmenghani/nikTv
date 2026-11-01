package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.DashboardSurface

internal fun modernCatalogSurface(type: CatalogType): DashboardSurface? = when (type) {
    CatalogType.MOVIES -> DashboardSurface.MOVIES
    CatalogType.SERIES -> DashboardSurface.SERIES
    else -> null
}
