package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Pagination shares the queue's fixed row/card bounds, including its busy state. */
@Composable
internal fun PlayerQueuePaginationContent(label: String, icon: ImageVector, loading: Boolean, list: Boolean) {
    val glyph: @Composable () -> Unit = {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
        else Icon(icon, null, Modifier.size(18.dp), tint = Color.White)
    }
    if (list) Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        glyph()
        Text(label, Modifier.weight(1f), color = Color.White, fontSize = 12.sp, lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    } else Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        glyph()
        Text(label, color = Color.White, fontSize = 12.sp, lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
