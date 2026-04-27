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
    background    = Color(0xFF07090B),
    surface       = Color(0xFF101316),
    surfaceRaised = Color(0xFF181C20),
    border        = Color(0xFF2A3035),
    borderActive  = Color(0xFF3A4148),
    textPrimary   = Color(0xFFE8ECEF),
    textSecondary = Color(0xFF8E969E),
    textDisabled  = Color(0xFF5F676E),
    green         = Color(0xFF7AC36A),
    blue          = Color(0xFF5DADEC),
    warning       = Color(0xFFFFB74D),
    error         = Color(0xFFCF6679),
)

val lightAppColors = AppColorScheme(
    background    = Color(0xFFF0F2F4),
    surface       = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFE4E7EA),
    border        = Color(0xFFC4C9CE),
    borderActive  = Color(0xFF8E969E),
    textPrimary   = Color(0xFF1A2229),
    textSecondary = Color(0xFF4F5A63),
    textDisabled  = Color(0xFF8A949C),
    green         = Color(0xFF2E8A3D),
    blue          = Color(0xFF1F6FA6),
    warning       = Color(0xFFC27700),
    error         = Color(0xFFA02040),
)

val LocalAppColors = staticCompositionLocalOf { darkAppColors }
