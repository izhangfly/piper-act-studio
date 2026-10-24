package com.nervus.act.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nervus.act.engine.Studio
import com.nervus.act.engine.dbl
import com.nervus.act.engine.get
import com.nervus.act.engine.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** A calm instrument-panel palette: deep navy, one cyan accent, and colour only where it means something. */
object Sci {
    val bg = Color(0xFF060A11)
    val panel = Color(0xFF0B121E)
    val panelHi = Color(0xFF101A2B)
    val line = Color(0xFF1C2B42)
    val accent = Color(0xFF52D6E8)
    val accentDim = Color(0xFF1E5664)
    val ok = Color(0xFF4FDE9F)
    val warn = Color(0xFFF2B44E)
    val bad = Color(0xFFFF5D6E)
    val violet = Color(0xFFA493FF)
    val text = Color(0xFFDCE6F2)
    val dim = Color(0xFF7B8CA4)
    val mono = FontFamily.Monospace

    val title = TextStyle(color = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
    val body = TextStyle(color = text, fontSize = 14.sp)
    val small = TextStyle(color = dim, fontSize = 12.sp)
    val num = TextStyle(color = text, fontSize = 15.sp, fontFamily = mono)
    val big = TextStyle(color = text, fontSize = 26.sp, fontFamily = mono, fontWeight = FontWeight.Light)

    val jointColors = listOf(
        Color(0xFF52D6E8), Color(0xFF4FDE9F), Color(0xFFF2B44E), Color(0xFFA493FF),
        Color(0xFFFF8BD1), Color(0xFF8FB8FF), Color(0xFFFFFFFF),
    )
}

@Composable
fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(Sci.panelHi, Sci.panel)))
            .border(1.dp, Sci.line, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.size(6.dp).background(Sci.accent, RoundedCornerShape(1.dp)))
            Spacer(Modifier.width(8.dp))
            Text(title.uppercase(), style = Sci.title)
            Spacer(Modifier.weight(1f))
            trailing()
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
fun Pill(text: String, color: Color = Sci.accent, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text, style = TextStyle(color = color, fontSize = 12.sp, fontFamily = Sci.mono, letterSpacing = 1.sp))
    }
}

@Composable
fun Stat(label: String, value: String, color: Color = Sci.text, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label.uppercase(), style = Sci.small.copy(letterSpacing = 1.sp, fontSize = 11.sp))
        Text(value, style = Sci.num.copy(color = color))
    }
}

enum class Kind { Primary, Normal, Danger, Quiet }

@Composable
fun SciButton(text: String, onClick: () -> Unit, kind: Kind = Kind.Normal, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val (bg, fg, edge) = when (kind) {
        Kind.Primary -> Triple(Sci.accent.copy(alpha = 0.18f), Sci.accent, Sci.accent)
        Kind.Danger -> Triple(Sci.bad.copy(alpha = 0.2f), Sci.bad, Sci.bad)
        Kind.Normal -> Triple(Sci.panelHi, Sci.text, Sci.line)
        Kind.Quiet -> Triple(Color.Transparent, Sci.dim, Sci.line)
    }
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier,
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(1.dp, if (enabled) edge.copy(alpha = 0.8f) else Sci.line),
        colors = ButtonDefaults.buttonColors(
            containerColor = bg, contentColor = fg,
            disabledContainerColor = Sci.panel, disabledContentColor = Sci.dim.copy(alpha = 0.5f),
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp))
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hint: String = "",
) {
    Column(modifier) {
        Text(label.uppercase(), style = Sci.small.copy(fontSize = 11.sp, letterSpacing = 1.sp))
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Sci.bg)
                .border(1.dp, if (enabled) Sci.line else Sci.line.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (value.isEmpty() && hint.isNotEmpty()) Text(hint, style = Sci.body.copy(color = Sci.dim.copy(alpha = 0.6f)))
            BasicTextField(
                value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
                textStyle = Sci.body.copy(color = if (enabled) Sci.text else Sci.dim),
                cursorBrush = SolidColor(Sci.accent),
                modifier = Modifier.fillMaxWidth().onFocusChanged { Typing.active = it.isFocused },
            )
        }
    }
}

@Composable
fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled) { onChange(!checked) }) {
        Checkbox(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = Sci.accent, uncheckedColor = Sci.dim, checkmarkColor = Sci.bg),
        )
        Text(label, style = Sci.body)
    }
}

@Composable
fun LabeledSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit,
    format: (Float) -> String, help: String = "", onDone: () -> Unit = {}, steps: Int = 0,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Sci.body, modifier = Modifier.weight(1f))
            Text(format(value), style = Sci.num.copy(color = Sci.accent))
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps, onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(thumbColor = Sci.accent, activeTrackColor = Sci.accent, inactiveTrackColor = Sci.line),
        )
        if (help.isNotEmpty()) Text(help, style = Sci.small)
    }
}

/** A row of label + value pairs, for the dense facts panels. */
@Composable
fun Facts(vararg rows: Pair<String, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for ((k, v) in rows) {
            Row {
                Text(k, style = Sci.small, modifier = Modifier.width(150.dp))
                Text(v, style = Sci.num.copy(fontSize = 13.sp))
            }
        }
    }
}

fun ByteArray.toImageBitmap(): ImageBitmap? = try {
    org.jetbrains.skia.Image.makeFromEncoded(this).toComposeImageBitmap()
} catch (e: Exception) {
    null
}

/**
 * A live camera from the engine (JPEG over loopback, ~8 frames a second while it is on screen), framed with
 * corner brackets. It stops fetching when it leaves the screen.
 */
@Composable
fun CameraView(studio: Studio, role: String, modifier: Modifier = Modifier, label: String = role) {
    var image by remember(role) { mutableStateOf<ImageBitmap?>(null) }
    var alive by remember { mutableStateOf(true) }
    DisposableEffect(role) { onDispose { alive = false } }
    LaunchedEffect(role) {
        while (studio.previews && isActive && alive) {
            val bytes = withContext(Dispatchers.IO) { studio.engine.bytes("/api/camera/$role.jpg") }
            image = bytes?.toImageBitmap()
            delay(120)
        }
    }
    val cam = studio.status["cameras"][role]
    val fps = cam["fps"].dbl ?: 0.0
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
        val img = image
        if (img != null) {
            Canvas(Modifier.fillMaxSize()) {
                val s = minOf(size.width / img.width, size.height / img.height)
                val w = img.width * s
                val h = img.height * s
                drawImage(
                    img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                    dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
                    dstSize = IntSize(w.toInt(), h.toInt()),
                )
            }
        } else {
            Text(
                cam["error"].str ?: if (cam == null) "no $role camera configured" else "waiting for frames…",
                style = Sci.small, modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val c = if (fps >= 10) Sci.accent else if (fps > 0) Sci.warn else Sci.bad
            val l = 22f
            val st = Stroke(2f)
            for ((x, y, dx, dy) in listOf(
                listOf(4f, 4f, 1f, 1f), listOf(size.width - 4f, 4f, -1f, 1f),
                listOf(4f, size.height - 4f, 1f, -1f), listOf(size.width - 4f, size.height - 4f, -1f, -1f),
            )) {
                drawLine(c, Offset(x, y), Offset(x + dx * l, y), st.width)
                drawLine(c, Offset(x, y), Offset(x, y + dy * l), st.width)
            }
        }
        Row(Modifier.align(Alignment.TopStart).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(label.uppercase(), Sci.accent)
            Spacer(Modifier.width(6.dp))
            Pill("%.1f fps".format(fps), if (fps >= 10) Sci.ok else if (fps > 0) Sci.warn else Sci.bad)
        }
    }
}
