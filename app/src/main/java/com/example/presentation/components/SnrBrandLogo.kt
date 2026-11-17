package com.example.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CyberConsole
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.CyberVoid

@Composable
fun SnrBrandLogo(
    modifier: Modifier = Modifier,
    size: Dp = 56.dp
) {
    val shape = RoundedCornerShape(size * 0.22f)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(CyberVoid, CyberConsole, Color(0xFF0A252B))
                )
            )
            .border(
                width = (size * 0.032f).coerceAtLeast(1.dp),
                brush = Brush.linearGradient(colors = listOf(CyberEmerald, CyberCyan)),
                shape = shape
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size * 0.68f)) {
            val w = this.size.width
            val h = this.size.height

            // Hexagonal security shield perimeter
            val hexPath = Path().apply {
                moveTo(w * 0.50f, h * 0.06f)
                lineTo(w * 0.90f, h * 0.26f)
                lineTo(w * 0.90f, h * 0.66f)
                lineTo(w * 0.50f, h * 0.94f)
                lineTo(w * 0.10f, h * 0.66f)
                lineTo(w * 0.10f, h * 0.26f)
                close()
            }
            drawPath(
                path = hexPath,
                brush = Brush.linearGradient(colors = listOf(CyberEmerald, CyberCyan)),
                style = Stroke(width = w * 0.075f, cap = StrokeCap.Square, join = StrokeJoin.Miter)
            )

            // Stylized cyber 'D' stem + angular node curve
            drawLine(
                color = CyberEmerald,
                start = Offset(w * 0.37f, h * 0.30f),
                end = Offset(w * 0.37f, h * 0.68f),
                strokeWidth = w * 0.09f,
                cap = StrokeCap.Square
            )
            val dPath = Path().apply {
                moveTo(w * 0.37f, h * 0.30f)
                lineTo(w * 0.57f, h * 0.30f)
                lineTo(w * 0.70f, h * 0.43f)
                lineTo(w * 0.70f, h * 0.55f)
                lineTo(w * 0.57f, h * 0.68f)
                lineTo(w * 0.37f, h * 0.68f)
            }
            drawPath(
                path = dPath,
                color = Color.White,
                style = Stroke(width = w * 0.08f, cap = StrokeCap.Square, join = StrokeJoin.Miter)
            )

            // Active node telemetry dot
            drawCircle(
                color = CyberCyan,
                radius = w * 0.065f,
                center = Offset(w * 0.77f, h * 0.22f)
            )
        }
    }
}
