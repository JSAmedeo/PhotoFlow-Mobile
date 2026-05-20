package com.photoflowmobile.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class AppColorScheme(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val border: Color,
    val borderActive: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val green: Color,
    val blue: Color,
    val warning: Color,
    val error: Color,
)

val darkAppColors = AppColorScheme(
    background    = Color(0xFF0B0D0F),
    surface       = Color(0xFF111519),
    surfaceRaised = Color(0xFF191E24),
    border        = Color(0xFF1C2228),
    borderActive  = Color(0xFF262F38),
    textPrimary   = Color(0xFFF3F7FA),
    textSecondary = Color(0xFF7B8A96),
    textDisabled  = Color(0xFF485260),
    green         = Color(0xFF4DD962),  // vivid mint green — used for status/success
    blue          = Color(0xFF4DD962),  // mint green as primary accent (replaces steel blue)
    warning       = Color(0xFFFFBB57),
    error         = Color(0xFFE05870),
)

val lightAppColors = AppColorScheme(
    background    = Color(0xFFEFF2F5),
    surface       = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFE2E7EC),
    border        = Color(0xFFBFC8D0),
    borderActive  = Color(0xFF8E9AA4),
    textPrimary   = Color(0xFF151D24),
    textSecondary = Color(0xFF4A5660),
    textDisabled  = Color(0xFF8A949C),
    green         = Color(0xFF1E9E32),
    blue          = Color(0xFF1E9E32),  // mint green primary in light mode too
    warning       = Color(0xFFC07A00),
    error         = Color(0xFF9E1E32),
)

val LocalAppColors = staticCompositionLocalOf { darkAppColors }
