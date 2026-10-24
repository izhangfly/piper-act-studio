package com.nervus.act.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nervus.act.engine.Studio
import com.nervus.act.engine.arr
import com.nervus.act.engine.bool
import com.nervus.act.engine.dbl
import com.nervus.act.engine.fmt
import com.nervus.act.engine.get
import com.nervus.act.engine.int
import com.nervus.act.engine.obj
import com.nervus.act.engine.str

/** The runner settings worth touching from the screen, with what each one does (the engine has the rest). */
private val PARAMS = listOf(
    Triple("speed_pct", "Speed", "Firmware joint speed limit, percent"),
    Triple("replan_every", "Replan every", "Steps between new plans; lower reacts faster and costs CPU"),
    Triple("crossfade", "Crossfade", "Steps blended when a new plan takes over"),
    Triple("ensemble", "Ensemble", "Average the newest N overlapping plans for the joints (never the gripper)"),
    Triple("step_limit_x", "Step limit", "Per-step change cap, times the demonstrations' 99th percentile (0 = off)"),
    Triple("lead_limit_x", "Lead limit", "How far commands may run ahead of the arm, times the demo p99"),
    Triple("envelope_margin", "Envelope margin", "Degrees allowed beyond what was demonstrated"),
    Triple("gripper_effort", "Grip force", "0.001 N·m; 1000 crushed a printed cube, 300–600 suits printed parts"),
    Triple("gripper_squeeze_mm", "Squeeze", "While holding, command this much tighter than the measured width"),
    Triple("gripper_release_steps", "Release after", "While holding, open requests in a row needed to let go"),
    Triple("max_duration_s", "Time limit", "A run stops by itself after this many seconds"),
)
private val SWITCHES = listOf(
    Triple("record_rollouts", "Record runs", "Save each run (video + joints) for post-training"),
    Triple("intervene_on_teach", "Take over on teach", "Pressing the arm's teach button mid-run records your correction instead of stopping"),
    Triple("dry_run", "Dry run", "Plan everything, send nothing to the arm"),
)

@Composable
fun RunTab(studio: Studio) {
    val r = studio.status["runner"]
    val arm = studio.status["arm"]
    val state = r["state"].str ?: "idle"
    val run = r["run"]
    var pick by remember { mutableStateOf<String?>(null) }
    var case by remember { mutableStateOf("A") }
    var note by remember { mutableStateOf("") }
    val loaded = r["policy"].str
    val choice = pick ?: loaded ?: studio.status["current_policy"].str
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(420.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel("Policy") {
                for (p in studio.policies.take(8)) {
                    val n = p["name"].str ?: continue
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp))
                            .background(if (n == choice) Sci.accent.copy(alpha = 0.1f) else Sci.bg)
                            .clickable { pick = n }.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(n, style = Sci.body.copy(color = if (n == choice) Sci.accent else Sci.text, fontSize = 13.sp), modifier = Modifier.weight(1f))
                        if (p["current"].bool) Pill("CURRENT", Sci.accent)
                        if (n == loaded) {
                            Spacer(Modifier.width(6.dp))
                            Pill("LOADED", Sci.ok)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SciButton("Load", { studio.act("Load", "Loaded $choice") { post("/api/run/load", mapOf("name" to choice), 300_000) } },
                        Kind.Primary, enabled = choice != null && state in setOf("idle", "ready", "stopped"))
                    SciButton("Unload", { studio.act("Unload") { post("/api/run/unload") } }, Kind.Quiet, enabled = loaded != null && state !in setOf("running", "moving"))
                }
                Spacer(Modifier.height(8.dp))
                Text(r["message"].str ?: "", style = Sci.body.copy(color = Sci.dim))
                r["latency_ms"].int?.let { Text("one plan takes about $it ms on this CPU", style = Sci.small) }
            }
            Panel("Scene check") {
                val pv = r["preview"]
                Text("Asks the policy what it would do from here, without moving. A policy that would sit still is " +
                    "seeing a scene unlike its demonstrations: fix the light or the camera first.", style = Sci.small)
                Spacer(Modifier.height(8.dp))
                SciButton("Check scene", { studio.act("Scene check") { post("/api/run/preview", timeoutMs = 60_000) } }, enabled = loaded != null)
                if (pv["score"].dbl != null) {
                    Spacer(Modifier.height(8.dp))
                    val c = if (pv["good"].bool) Sci.ok else if (pv["weak"].bool) Sci.warn else Sci.bad
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(pv["score"].fmt(1, "°"), style = Sci.big.copy(color = c))
                        Column {
                            Text(pv["verdict"].str ?: "", style = Sci.body.copy(color = c))
                            Text("at ${pv["at"].str} · brightness " + pv["brightness"].obj.entries.joinToString { "${it.key} ${it.value.fmt(2)}" }, style = Sci.small)
                        }
                    }
                }
            }
            Panel("Settings") {
                val params = r["params"]
                val limits = r["limits"]
                for ((k, label, help) in PARAMS) key(k) {
                    val lim = limits[k].arr.mapNotNull { it.dbl }
                    if (lim.size >= 2) {
                        var v by remember(params[k].dbl) { mutableStateOf((params[k].dbl ?: lim[0]).toFloat()) }
                        val isInt = (params[k].str ?: "").let { !it.contains('.') }
                        LabeledSlider(label, v, lim[0].toFloat()..lim[1].toFloat(), { v = it },
                            { if (isInt) "%.0f".format(it) else "%.2f".format(it) }, help,
                            onDone = { studio.act("Setting", "$label: ${if (isInt) "%.0f".format(v) else "%.2f".format(v)}") { post("/api/run/params", mapOf(k to v)) } })
                    }
                }
                for ((k, label, help) in SWITCHES) {
                    Check(label, (params[k].int ?: 0) == 1, { on -> studio.act("Setting", "$label ${if (on) "on" else "off"}") { post("/api/run/params", mapOf(k to if (on) 1 else 0)) } },
                        enabled = state !in setOf("running", "moving"))
                    Text(help, style = Sci.small, modifier = Modifier.padding(start = 48.dp))
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val roles = r["cameras"].arr.mapNotNull { it.str }.ifEmpty { studio.status["cameras"].obj.keys.toList() }
                for (role in roles) CameraView(studio, role, Modifier.weight(1f).aspectRatio(4f / 3f))
            }
            Panel("Run") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SciButton("Enable", { studio.act("Enable", "Motors on, holding position") { post("/api/arm/enable", timeoutMs = 10_000) } },
                        enabled = !arm["enabled"].bool || arm["teaching"].bool)
                    SciButton("Go to start", { studio.act("Start pose") { post("/api/run/goto_start") } },
                        enabled = loaded != null && state in setOf("ready", "stopped") && arm["enabled"].bool)
                    SciButton("RUN", { studio.act("Run") { post("/api/run/start") } }, Kind.Primary,
                        enabled = loaded != null && state in setOf("ready", "stopped"))
                    SciButton("Correct  (C)", { studio.act("Correct") { post("/api/run/correct") } }, enabled = state == "running" && run["segment"].str == "policy")
                    SciButton("Stop  (Esc)", { studio.now("Stopped") { post("/api/run/stop") } }, enabled = state in setOf("running", "moving"))
                    Spacer(Modifier.weight(1f))
                    Pill(state.uppercase(), when (state) {
                        "running" -> if (run["segment"].str == "human") Sci.warn else Sci.ok
                        "moving", "loading" -> Sci.accent
                        "stopped" -> Sci.dim
                        else -> Sci.dim
                    })
                    if (run["dry_run"].bool) Pill("DRY RUN", Sci.violet)
                    if (state == "running" && run["segment"].str == "human") Pill("YOUR CORRECTION", Sci.warn)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    Stat("time", run["elapsed_s"].fmt(1, " s"))
                    Stat("steps", "${run["steps"].int ?: 0}")
                    Stat("plan", r["latency_ms"].int?.let { "$it ms" } ?: "–")
                    Stat("starved", "${run["starved"].int ?: 0}", if ((run["starved"].int ?: 0) > 0) Sci.warn else Sci.text)
                    Stat("late ticks", "${run["overruns"].int ?: 0}")
                    Stat("corrections", "${run["interventions"].int ?: 0}")
                    Stat("recorded", "${run["recorded_frames"].int ?: 0} frames")
                }
                run["stop_reason"].str?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("ended: $it", style = Sci.body.copy(color = Sci.dim))
                }
            }
            if (state == "stopped" && run["id"].str != null && !run["dry_run"].bool) {
                Panel("How did it go?", Modifier.border(1.dp, if (run["labelled"].str == null) Sci.accent.copy(alpha = 0.5f) else Sci.line, RoundedCornerShape(10.dp))) {
                    if (run["labelled"].str != null) {
                        Text("Labelled ${run["labelled"].str}. The run went to the Mac for post-training.", style = Sci.body.copy(color = Sci.ok))
                    } else {
                        Text("Label it: success only if the task was done with no help. A run you corrected is a fail, " +
                            "and your correction is still used.", style = Sci.small)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                            Field("case", case, { case = it.take(20) }, Modifier.width(120.dp))
                            Field("note", note, { note = it.take(300) }, Modifier.weight(1f))
                            SciButton("Success", { studio.act("Label") { post("/api/run/mark", mapOf("outcome" to "success", "case" to case, "note" to note)) } }, Kind.Primary)
                            SciButton("Fail", { studio.act("Label") { post("/api/run/mark", mapOf("outcome" to "fail", "case" to case, "note" to note)) } }, Kind.Danger)
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================================ System

@Composable
fun SystemTab(studio: Studio) {
    val s = studio.status
    val arm = s["arm"]
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel("Engine") {
                Facts(
                    "process" to "pid ${s["engine"]["pid"].str ?: "?"}, since ${s["engine"]["started"].str ?: "?"}",
                    "hardware" to if (s["engine"]["sim"].bool) "simulated arm and cameras" else "PiPER driver + USB cameras",
                    "shared folder" to (s["shared"].str ?: "?"),
                )
            }
            Panel("Arm driver") {
                Facts(
                    "reachable" to if (arm["reachable"].bool) "yes" else (arm["error"].str ?: "no"),
                    "feedback" to (if (arm["fresh"].bool) "fresh" else "stale") + arm["feedback_age_ms"].dbl?.let { ", %.0f ms old".format(it) }.orEmpty(),
                    "CAN" to arm["can_fps"].fmt(0, " frames/s"),
                    "mode" to "${arm["ctrl_mode"].str ?: "?"}, ${arm["arm_status"].str ?: "?"}" + if (arm["soft_fault"].bool) " (soft fault)" else "",
                    "control" to (arm["owner"]["client"].str?.let { "held by $it" } ?: "free"),
                    "firmware" to (arm["firmware"].str ?: "?"),
                )
                Spacer(Modifier.height(10.dp))
                ArmButtons(studio)
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel("Cameras") {
                for ((role, c) in s["cameras"].obj) {
                    Text(role.uppercase(), style = Sci.title.copy(fontSize = 12.sp, color = Sci.accent))
                    Facts(
                        "device" to (c["device"].str ?: "not found"),
                        "matched by" to (c["match"].str ?: "?"),
                        "rate" to c["fps"].fmt(1, " fps") + if (c["active"].bool) "" else " (released: nobody needs it)",
                        "frame age" to (c["age_ms"].int?.let { "$it ms" } ?: "–"),
                        "problem" to (c["error"].str ?: "none"),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Text("Cameras are matched by their /dev/v4l/by-id name, never by index: the index order changes " +
                    "whenever a USB device comes or goes.", style = Sci.small)
            }
            val link = s["link"]
            Panel("Syncthing link to the Mac") {
                Facts(
                    "Syncthing" to if (link["available"].bool) "running" else (link["error"].str ?: "not reachable"),
                    "Mac" to if (link["mac_connected"].bool) "connected (${link["via"].str})" else "not connected",
                    "peer" to (link["peer"].str ?: "–"),
                    "address" to (link["address"].str ?: "–"),
                    "folder" to "${link["folder_state"].str ?: "?"}, ${link["need_files"].int ?: 0} file(s) to fetch",
                    "Mac has" to link["mac_completion"].fmt(1, " % of it"),
                )
            }
            val w = s["worker"]
            Panel("Mac worker") {
                Facts(
                    "host" to (w["host"].str ?: "–"),
                    "state" to (w["state"].str ?: "–") + (w["job"].str?.let { ", $it" } ?: ""),
                    "queue" to "${w["queue"].arr.size} job(s)",
                    "software" to "LeRobot ${w["lerobot"].str ?: "?"}, torch ${w["torch"].str ?: "?"} on ${w["device"].str ?: "?"}",
                    "disk" to w["free_gb"].fmt(0, " GB free"),
                    "heartbeat" to (w["age_s"].int?.let { "$it s ago" } ?: "never"),
                )
            }
        }
    }
}

@Composable
fun ArmButtons(studio: Studio) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SciButton("Enable", { studio.act("Enable", "Motors on, holding position") { post("/api/arm/enable", timeoutMs = 10_000) } })
        SciButton("Recover from fault", { studio.act("Recover", "Fault cleared, motors on") { post("/api/arm/recover", timeoutMs = 12_000) } })
        SciButton("Release control", { studio.act("Release", "Released the arm for other apps") { post("/api/arm/release") } }, Kind.Quiet)
    }
}
