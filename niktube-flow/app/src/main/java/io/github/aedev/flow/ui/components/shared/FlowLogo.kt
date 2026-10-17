package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R

@Composable
fun FlowLogo(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.niktube_host_logo), stringResource(R.string.app_name), modifier.aspectRatio(1f))
}
