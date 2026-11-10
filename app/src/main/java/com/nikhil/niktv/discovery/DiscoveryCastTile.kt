package com.nikhil.niktv.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

@Composable
internal fun DiscoveryCastTile(person: DiscoveryPerson, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(onClick = onClick, enabled = (person.id ?: 0) > 0,
        modifier = modifier.width(102.dp).onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(12.dp), color = if (focused) Color(0xFF303641) else Color.Transparent,
        border = if (focused) BorderStroke(2.dp, Color.White) else null) {
        Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AsyncImage(person.portrait, null, Modifier.size(80.dp).clip(CircleShape).background(Color(0xFF20232A)), contentScale = ContentScale.Crop)
            Text(person.name, color = Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(person.role, color = Color.Gray, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
