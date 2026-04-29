package com.photoflowmobile.app.ui.screens

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.photoflowmobile.app.R
import com.photoflowmobile.app.ui.theme.LocalAppColors
import kotlinx.coroutines.delay

@Composable
fun PhotoFlowSplash(onComplete: () -> Unit) {
    val colors = LocalAppColors.current

    LaunchedEffect(Unit) {
        delay(1400)
        onComplete()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AndroidView(
            modifier = Modifier.size(160.dp),
            factory = { ctx ->
                ImageView(ctx).apply {
                    setImageDrawable(ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher))
                }
            }
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = "PhotoFlow Mobile",
            color = colors.textPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
    }
}
