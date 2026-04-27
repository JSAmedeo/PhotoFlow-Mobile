package com.photoflowmobile.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val DarkMaterialScheme = darkColorScheme(
    primary        = darkAppColors.blue,
    background     = darkAppColors.background,
    surface        = darkAppColors.surface,
    surfaceVariant = darkAppColors.surfaceRaised,
    onBackground   = darkAppColors.textPrimary,
    onSurface      = darkAppColors.textPrimary,
    error          = darkAppColors.error,
)

private val LightMaterialScheme = lightColorScheme(
    primary        = lightAppColors.blue,
    background     = lightAppColors.background,
    surface        = lightAppColors.surface,
    surfaceVariant = lightAppColors.surfaceRaised,
    onBackground   = lightAppColors.textPrimary,
    onSurface      = lightAppColors.textPrimary,
    error          = lightAppColors.error,
)

@Composable
fun PhotoFlowMobileTheme(
    darkMode: Boolean = true,
    content: @Composable () -> Unit
) {
    val appColors = if (darkMode) darkAppColors else lightAppColors
    val materialScheme = if (darkMode) DarkMaterialScheme else LightMaterialScheme
    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = Typography,
            content = content
        )
    }
}
