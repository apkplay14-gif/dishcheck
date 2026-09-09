package ua.starlink.reader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.starlink.reader.R

/** Знак застосунку: літера Є у темному квадраті з підсвіткою. */
@Composable
fun AppLogo(size: Int = 36, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.28f).dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF0B1220), Color(0xFF1B2C4C))
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_logo),
            contentDescription = null,
            tint = Brand.TextPrimary,
            modifier = Modifier.size((size * 0.62f).dp),
        )
    }
}

/** Логотип із назвою: «Є» акцентом, «Starlink» звичайним. */
@Composable
fun WordMark(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppLogo(size = 30)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Brand.Accent, fontWeight = FontWeight.Bold)) {
                    append("Є ")
                }
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append("Starlink")
                }
            },
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

/**
 * Ледь помітні орбіти на тлі шапки. Це єдина декорація в застосунку —
 * далі все підпорядковано читабельності даних на сонці.
 */
@Composable
fun OrbitBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width * 0.78f, size.height * 0.35f)
        listOf(0.55f to 0.16f, 0.85f to 0.07f, 1.15f to 0.05f).forEach { (scale, alpha) ->
            rotate(degrees = -22f, pivot = center) {
                val w = size.width * scale
                val h = w * 0.34f
                drawOval(
                    color = Brand.Accent.copy(alpha = alpha),
                    topLeft = Offset(center.x - w / 2f, center.y - h / 2f),
                    size = Size(w, h),
                    style = Stroke(width = 1.2f.dp.toPx()),
                )
            }
        }
    }
}

/** Базова картка: поверхня + волосяна рамка, без важких тіней. */
@Composable
fun BrandCard(
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (accent) Brand.AccentSoft else Brand.Surface
        ),
        border = BorderStroke(1.dp, if (accent) Brand.Accent.copy(alpha = 0.35f) else Brand.Hairline),
        shape = MaterialTheme.shapes.large,
        content = content,
    )
}

/** Дрібна велика мітка секції: РОЗДІЛ. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Brand.TextMuted,
        modifier = modifier,
    )
}

/** Чіп статусу з кольоровою крапкою. */
@Composable
fun StatusPill(color: Color, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(color)
        )
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** Номер кроку 1..6 у кружечку. */
@Composable
fun StepBadge(number: Int, active: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) Brand.Accent.copy(alpha = 0.18f) else Brand.SurfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toString(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (active) Brand.Accent else Brand.TextMuted,
        )
    }
}

/** Іконка 20dp у приглушеному кольорі — типовий елемент рядка даних. */
@Composable
fun RowIcon(resId: Int, tint: Color = Brand.TextMuted, size: Int = 20) {
    Icon(
        painter = painterResource(resId),
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(size.dp),
    )
}

/** Заголовок картки: іконка + назва + необов'язковий хвіст справа. */
@Composable
fun CardHeader(
    iconRes: Int,
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RowIcon(iconRes)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        trailing?.invoke()
    }
}
