package com.nikhil.niktv.sports

/** Catalogue protocols are separate even when their channel names overlap. */
internal enum class SportsCatalogueSource(val title: String, val storagePrefix: String) {
    CRICFY("Sports", "sports"), PLAYFY("Playfy TV", "playfy")
}
