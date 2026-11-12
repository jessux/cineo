package com.cineo.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Animation d'ouverture : triangle "lecture" qui se dessine, lettres qui montent une à une, halo, puis zoom-sortie. */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val p = remember { Animatable(0f) }
    val out = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        p.animateTo(1f, tween(1700, easing = LinearEasing))
        out.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
        onDone()
    }
    val t = p.value
    Box(
        Modifier.fillMaxSize().background(Color.Black).alpha(1f - out.value),
        contentAlignment = Alignment.Center,
    ) {
        // Halo violet
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension * (0.2f + 0.5f * t)
            drawCircle(Brush.radialGradient(listOf(Accent.copy(alpha = 0.35f * t), Color.Transparent), center, r), r, center)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.scale(1f + out.value * 2.5f),
        ) {
            // Triangle "play" qui se remplit
            val tri = (t / 0.35f).coerceIn(0f, 1f)
            Canvas(Modifier.size(72.dp).graphicsLayer { rotationZ = (1f - tri) * -90f; alpha = tri }) {
                val path = Path().apply {
                    moveTo(size.width * 0.2f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.2f, size.height); close()
                }
                drawPath(path, Brush.linearGradient(listOf(Accent, Gold), Offset.Zero, Offset(size.width, size.height)))
            }
            Spacer(Modifier.height(20.dp))
            Row {
                "CINÉO".forEachIndexed { i, ch ->
                    val lt = ((t - 0.3f - i * 0.08f) / 0.25f).coerceIn(0f, 1f)
                    Text(
                        ch.toString(), color = Gold, fontSize = 46.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp,
                        modifier = Modifier.graphicsLayer { alpha = lt; translationY = (1f - lt) * 60f },
                    )
                }
            }
            // Trait qui s'étire sous le logo
            val line = ((t - 0.75f) / 0.25f).coerceIn(0f, 1f)
            Box(Modifier.padding(top = 6.dp).height(3.dp).width((180 * line).dp).background(Brush.horizontalGradient(listOf(Accent, Gold))))
        }
    }
}
