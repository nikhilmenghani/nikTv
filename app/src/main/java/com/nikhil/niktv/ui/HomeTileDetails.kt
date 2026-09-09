package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Full focused-item text stays outside the uniformly sized poster cards. */
@Composable
internal fun HomeTileDetails(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 18.dp, vertical = 2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFBFC3CA),
            maxLines = 1
        )
    }
}
