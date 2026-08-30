package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = MinDarkPrimary,
    onPrimary = MinDarkOnPrimary,
    secondary = MinDarkSecondary,
    onSecondary = MinDarkOnSecondary,
    background = MinDarkBackground,
    onBackground = MinDarkOnBackground,
    surface = MinDarkSurface,
    onSurface = MinDarkOnSurface,
    surfaceVariant = MinDarkSurfaceVariant,
    onSurfaceVariant = MinDarkOnSurfaceVariant,
    outline = MinDarkOutline
  )

private val LightColorScheme =
  lightColorScheme(
    primary = MinLightPrimary,
    onPrimary = MinLightOnPrimary,
    secondary = MinLightSecondary,
    onSecondary = MinLightOnSecondary,
    background = MinLightBackground,
    onBackground = MinLightOnBackground,
    surface = MinLightSurface,
    onSurface = MinLightOnSurface,
    surfaceVariant = MinLightSurfaceVariant,
    onSurfaceVariant = MinLightOnSurfaceVariant,
    outline = MinLightOutline
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is disabled by default to preserve the Clean Minimalism theme
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
