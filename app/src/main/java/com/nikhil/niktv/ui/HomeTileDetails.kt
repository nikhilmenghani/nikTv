package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            minLines = 2
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFBFC3CA),
            minLines = 2
        )
    }
}
