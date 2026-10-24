package com.nervus.act.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nervus.act.engine.Studio
import com.nervus.act.engine.Tab
import com.nervus.act.engine.bool
import com.nervus.act.engine.dbl
import com.nervus.act.engine.get
import com.nervus.act.engine.obj
import com.nervus.act.engine.str
import kotlinx.coroutines.delay

/** Set while a text field has focus, so typing a space in a dataset name doesn't start a take. */
object Typing {
    @Volatile var active = false
}

@Composable
fun StudioScreen(studio: Studio) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        Modifier.fillMaxSize().background(Sci.bg)
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    e.key == Key.Escape -> { studio.now("Stopped") { post("/api/run/stop"); post("/api/arm/stop") }; true }
                    Typing.active -> false
                    e.key == Key.Spacebar && studio.tab == Tab.Record -> { toggleTake(studio); true }
                    e.key == Key.C && studio.tab == Tab.Run -> { studio.act("Correct") { post("/api/run/correct") }; true }
                    else -> false
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar(studio)
            Row(Modifier.fillMaxSize()) {
                Nav(studio)
                Box(Modifier.fillMaxSize().padding(16.dp)) {
                    when (studio.tab) {
                        Tab.Overview -> OverviewTab(studio)
                        Tab.Record -> RecordTab(studio)
                        Tab.Data -> DataTab(studio)
                        Tab.Train -> TrainTab(studio)
                        Tab.Policies -> PoliciesTab(studio)
                        Tab.Run -> RunTab(studio)
                        Tab.PostTrain -> PostTrainTab(studio)
                        Tab.System -> SystemTab(studio)
                    }
                }
            }
        }
        if (!studio.connected) Offline(studio, Modifier.align(Alignment.Center))
        NoticeBar(studio, Modifier.align(Alignment.BottomEnd).padding(18.dp))
    }
}

fun toggleTake(studio: Studio) {
    val state = studio.status["recorder"]["state"].str
    when (state) {
        "recording" -> studio.act("Stop") { post("/api/record/stop") }
        "idle" -> studio.act("Start") { post("/api/record/start") }
        else -> studio.notice = com.nervus.act.engine.Notice("Open a dataset on the Record tab first", true)
    }
}

@Composable
private fun TopBar(studio: Studio) {
    val s = studio.status
    val arm = s["arm"]
    Row(
        Modifier.fillMaxWidth().background(Sci.panel).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("ACT STUDIO", style = Sci.title.copy(fontSize = 16.sp, letterSpacing = 4.sp, color = Sci.accent))
        if (s["engine"]["sim"].bool) Pill("SIMULATED ARM", Sci.violet)
        Spacer(Modifier.width(8.dp))
        val (armText, armColor) = when {
            !studio.connected -> "ENGINE OFFLINE" to Sci.bad
            !arm["reachable"].bool -> "ARM DRIVER OFFLINE" to Sci.bad
            arm["estopped"].bool -> "E-STOPPED" to Sci.bad
            !arm["fresh"].bool -> "ARM SILENT" to Sci.warn
            arm["teaching"].bool -> "TEACH MODE" to Sci.warn
            arm["enabled"].bool -> "ARM ENABLED" to Sci.ok
            else -> "MOTORS OFF" to Sci.dim
        }
        Pill(armText, armColor)
        val owner = arm["owner"]["client"].str
        if (owner != null && owner != "act") Pill("ARM HELD BY ${owner.uppercase()}", Sci.warn)
        for ((role, cam) in s["cameras"].obj) {
            val fps = cam["fps"].dbl ?: 0.0
            Pill("${role.uppercase()} %.0f".format(fps), if (fps >= 10) Sci.ok else if (fps > 0) Sci.warn else Sci.dim)
        }
        val w = s["worker"]
        Pill(
            when {
                !w["seen"].bool -> "MAC NEVER SEEN"
                w["alive"].bool -> "MAC ${(w["state"].str ?: "?").uppercase()}"
                else -> "MAC OFFLINE"
            },
            if (w["alive"].bool) Sci.ok else Sci.bad,
        )
        val link = s["link"]
        val via = link["via"].str
        Pill(if (via != null) "LINK ${via.uppercase()}" else "NO LINK", when (via) {
            "LAN" -> Sci.ok
            null -> Sci.bad
            else -> Sci.warn
        })
        Spacer(Modifier.weight(1f))
        Text("CURRENT ", style = Sci.small)
        Text(s["current_policy"].str ?: "none", style = Sci.num.copy(fontSize = 13.sp, color = Sci.accent))
        Spacer(Modifier.width(12.dp))
        SciButton("STOP", { studio.now("Stopped: holding position") { post("/api/run/stop"); post("/api/arm/stop") } })
        SciButton("E-STOP", { studio.now("EMERGENCY STOP sent") { post("/api/arm/estop") } }, Kind.Danger)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Sci.line))
}

@Composable
private fun Nav(studio: Studio) {
    Column(
        Modifier.fillMaxHeight().width(168.dp).background(Sci.panel).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (t in Tab.entries) {
            val sel = studio.tab == t
            Row(
                Modifier.fillMaxWidth().clickable { studio.tab = t }
                    .background(if (sel) Sci.accent.copy(alpha = 0.08f) else Color.Transparent)
                    .padding(vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(3.dp).height(18.dp).background(if (sel) Sci.accent else Color.Transparent))
                Spacer(Modifier.width(16.dp))
                Text(
                    t.label.uppercase(),
                    style = Sci.title.copy(fontSize = 12.sp, color = if (sel) Sci.accent else Sci.dim,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            "Esc stops a run\nSpace starts/stops a take\nC takes over a run",
            style = Sci.small.copy(fontSize = 11.sp), modifier = Modifier.padding(horizontal = 18.dp),
        )
    }
}

@Composable
private fun Offline(studio: Studio, modifier: Modifier) {
    Panel("ACT engine", modifier.widthIn(max = 560.dp).border(1.dp, Sci.bad.copy(alpha = 0.6f), RoundedCornerShape(10.dp))) {
        Text(studio.lastError ?: "Connecting…", style = Sci.body)
        Spacer(Modifier.height(8.dp))
        Text(
            "The engine runs as nervus-act.service and starts with NervusOS. If it does not come up, its log is " +
                "`journalctl -u nervus-act -e`.",
            style = Sci.small,
        )
    }
}

@Composable
private fun NoticeBar(studio: Studio, modifier: Modifier) {
    val n = studio.notice ?: return
    LaunchedEffect(n) {
        delay(if (n.error) 9_000 else 5_000)
        if (studio.notice == n) studio.notice = null
    }
    Box(
        modifier.widthIn(max = 620.dp)
            .background(Sci.panelHi, RoundedCornerShape(8.dp))
            .border(1.dp, (if (n.error) Sci.bad else Sci.accent).copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .clickable { studio.notice = null }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (n.error) Sci.bad else Sci.ok, RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(10.dp))
            Text(n.text, style = Sci.body)
        }
    }
}
