package com.nervus.act.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nervus.act.engine.Studio
import com.nervus.act.engine.Tab
import com.nervus.act.engine.arr
import com.nervus.act.engine.bool
import com.nervus.act.engine.dbl
import com.nervus.act.engine.fmt
import com.nervus.act.engine.get
import com.nervus.act.engine.int
import com.nervus.act.engine.obj
import com.nervus.act.engine.str
import kotlinx.serialization.json.JsonElement

private val JOINTS = listOf("joint1", "joint2", "joint3", "joint4", "joint5", "joint6", "gripper")

// ============================================================================================ Overview

@Composable
fun OverviewTab(studio: Studio) {
    val s = studio.status
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1.4f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val roles = s["cameras"].obj.keys.toList().ifEmpty { listOf("wrist", "scene") }
                for (r in roles) CameraView(studio, r, Modifier.weight(1f).aspectRatio(4f / 3f))
            }
            NextStep(studio, Modifier.fillMaxWidth())
            Spacer(Modifier.weight(1f))
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ArmPanel(studio)
            val w = s["worker"]
            Panel("Mac worker") {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Stat("state", if (w["alive"].bool) w["state"].str ?: "?" else "offline", if (w["alive"].bool) Sci.ok else Sci.bad)
                    Stat("job", w["job"].str ?: "–")
                    Stat("queued", "${w["queue"].arr.size}")
                    Stat("last seen", w["age_s"].int?.let { "${it}s ago" } ?: "never")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    listOfNotNull(w["device"].str?.let { "trains on $it" }, w["lerobot"].str?.let { "LeRobot $it" },
                        w["free_gb"].dbl?.let { "%.0f GB free".format(it) }).joinToString(" · ").ifEmpty { "no heartbeat yet" },
                    style = Sci.small,
                )
            }
            val link = s["link"]
            Panel("Link to the Mac") {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Stat("path", link["via"].str ?: if (link["available"].bool) "not connected" else "no Syncthing",
                        if (link["via"].str == "LAN") Sci.ok else Sci.warn)
                    Stat("Mac has", link["mac_completion"].fmt(0, " %"))
                    Stat("to send", link["mac_need_bytes"].dbl?.let { humanBytes(it) } ?: "–")
                }
                Spacer(Modifier.height(6.dp))
                Text("Jobs, datasets and policies travel through the shared folder: directly on the same network, " +
                    "through the server relay otherwise.", style = Sci.small)
            }
            Panel("Policies") {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Stat("current", s["current_policy"].str ?: "none", Sci.accent)
                    Stat("published", "${studio.policies.size}")
                    Stat("runs labelled", "${studio.trials.size}")
                }
                val pt = s["posttrain"]
                if (pt["state"].str != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("Post-training: ${pt["state"].str}" + (pt["message"].str?.let { ", $it" } ?: ""), style = Sci.small)
                }
            }
        }
    }
}

@Composable
fun ArmPanel(studio: Studio) {
    val arm = studio.status["arm"]
    val limits = arm["limits"]
    Panel("Arm", trailing = { Text(arm["firmware"].str?.let { "firmware $it" } ?: "", style = Sci.small) }) {
        val joints = arm["joints"].arr.map { it.dbl }
        for (i in 0 until 7) {
            val v = if (i < 6) joints.getOrNull(i) else arm["gripper_mm"].dbl
            val lim = if (i < 6) limits[(i + 1).toString()].arr.map { it.dbl ?: 0.0 } else arm["gripper_range"].arr.map { it.dbl ?: 0.0 }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(if (i < 6) "J${i + 1}" else "GRIP", style = Sci.small, modifier = Modifier.width(44.dp))
                Meter(v, lim.getOrElse(0) { -180.0 }, lim.getOrElse(1) { 180.0 }, Sci.jointColors[i], Modifier.weight(1f).height(14.dp))
                Text(v?.let { "%7.1f".format(it) + if (i < 6) "°" else " mm" } ?: "      –", style = Sci.num.copy(fontSize = 13.sp),
                    modifier = Modifier.width(92.dp).padding(start = 8.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SciButton("Enable", { studio.act("Enable", "Motors on, holding position") { post("/api/arm/enable", timeoutMs = 10_000) } })
            SciButton("Recover", { studio.act("Recover", "Fault cleared") { post("/api/arm/recover", timeoutMs = 12_000) } })
            SciButton("Release", { studio.act("Release", "Released control of the arm") { post("/api/arm/release") } }, Kind.Quiet)
        }
    }
}

@Composable
private fun NextStep(studio: Studio, modifier: Modifier) {
    val s = studio.status
    val (title, text, tab) = when {
        studio.datasets.isEmpty() -> Triple("Record demonstrations",
            "Press the arm's teach button, then record 30 to 50 takes of the task on the Record tab: start and end at rest, " +
                "and vary where the object is.", Tab.Record)
        studio.policies.isEmpty() && studio.jobs.isEmpty() -> Triple("Train a policy",
            "Check the takes on the Data tab (trim, exclude the bad ones), then send a training job to the Mac.", Tab.Train)
        studio.policies.isEmpty() -> Triple("Training on the Mac",
            "The Train tab shows the job's progress. Published checkpoints appear under Policies.", Tab.Train)
        s["runner"]["policy"].str == null -> Triple("Run a policy",
            "Load CURRENT on the Run tab, check the scene, go to the start pose and run it. Label every run: the labels decide promotion.", Tab.Run)
        else -> Triple("Correct it when it fails",
            "Press C (or Correct) and finish the task by hand: corrections are what post-training learns from.", Tab.PostTrain)
    }
    Panel("Next", modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Sci.body.copy(fontSize = 17.sp, color = Sci.accent))
                Spacer(Modifier.height(4.dp))
                Text(text, style = Sci.body.copy(color = Sci.dim))
            }
            SciButton("Open ${tab.label}", { studio.tab = tab }, Kind.Primary)
        }
    }
}

fun humanBytes(b: Double): String = when {
    b >= 1e9 -> "%.1f GB".format(b / 1e9)
    b >= 1e6 -> "%.0f MB".format(b / 1e6)
    b >= 1e3 -> "%.0f kB".format(b / 1e3)
    else -> "%.0f B".format(b)
}

// ============================================================================================ Record

@Composable
fun RecordTab(studio: Studio) {
    val rec = studio.status["recorder"]
    val state = rec["state"].str ?: "closed"
    var name by remember { mutableStateOf("") }
    var task by remember { mutableStateOf("") }
    val roles = studio.status["cameras"].obj.keys.toList()
    var chosen by remember { mutableStateOf<Set<String>?>(null) }
    val use = chosen ?: roles.toSet()
    LaunchedEffect(rec["dataset"].str) {
        rec["dataset"].str?.let { name = it }
        rec["task"].str?.takeIf { it.isNotEmpty() }?.let { task = it }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(400.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel("Dataset") {
                val open = state != "closed" && state != "error"
                Field("name", name, { name = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }, enabled = !open,
                    hint = "pick_cube")
                Spacer(Modifier.height(10.dp))
                Field("task, in one sentence", task, { task = it }, enabled = !open, hint = "pick up the cube and put it on the red square")
                Spacer(Modifier.height(10.dp))
                Text("CAMERAS", style = Sci.small.copy(fontSize = 11.sp))
                for (r in roles) Check(r, r in use, { on -> chosen = if (on) use + r else use - r }, enabled = !open)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!open) {
                        SciButton("Open", {
                            studio.act("Open dataset") { post("/api/record/open", mapOf("name" to name, "task" to task, "cameras" to use.toList()), 120_000) }
                        }, Kind.Primary, enabled = name.isNotEmpty() && task.isNotBlank() && use.isNotEmpty())
                    } else {
                        SciButton("Close", { studio.act("Close dataset", "Dataset closed") { post("/api/record/close", timeoutMs = 120_000) } },
                            enabled = state == "idle")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("An existing name adds takes to that dataset (same cameras only).", style = Sci.small)
            }
            Panel("Take") {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Stat("state", state, when (state) {
                        "recording" -> Sci.bad
                        "idle" -> Sci.ok
                        "error" -> Sci.bad
                        else -> Sci.dim
                    })
                    Stat("this take", rec["episode_seconds"].fmt(1, " s"))
                    Stat("saved", "${rec["saved_episodes"].int ?: 0}")
                    Stat("loop", rec["loop_fps"].fmt(1, " Hz"))
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state == "recording") {
                        SciButton("STOP  (space)", { studio.act("Stop") { post("/api/record/stop") } }, Kind.Danger)
                        SciButton("Discard", { studio.act("Discard") { post("/api/record/stop", mapOf("discard" to true)) } }, Kind.Quiet)
                    } else {
                        SciButton("START  (space)", { studio.act("Start") { post("/api/record/start") } }, Kind.Primary, enabled = state == "idle")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(rec["message"].str ?: "", style = Sci.body.copy(color = Sci.dim))
                if (rec["teaching"].bool) {
                    Spacer(Modifier.height(6.dp))
                    Pill("TEACH MODE ON: move the arm by hand", Sci.ok)
                } else if (state == "idle") {
                    Spacer(Modifier.height(6.dp))
                    Pill("Press the arm's teach button to move it by hand", Sci.warn)
                }
            }
            Panel("Good takes") {
                Text(
                    "Start and end at the same rest pose; a second or two of stillness is trimmed off automatically. " +
                        "Vary the object's position and angle from take to take, and keep the cameras where they will be " +
                        "when the policy runs: moving a camera later makes the policy sit still.",
                    style = Sci.small.copy(fontSize = 13.sp),
                )
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (r in roles.ifEmpty { listOf("wrist") }) CameraView(studio, r, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

// ============================================================================================ Data

@Composable
fun DataTab(studio: Studio) {
    var selected by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<JsonElement?>(null) }
    var episode by remember { mutableStateOf<Int?>(null) }
    var curve by remember { mutableStateOf<JsonElement?>(null) }
    var trim by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    fun reload(name: String) = studio.load({ get("/api/datasets/$name/episodes", 30_000) }) { detail = it }
    LaunchedEffect(selected) { selected?.let { reload(it) } }
    LaunchedEffect(selected, episode) {
        val n = selected
        val e = episode
        if (n != null && e != null) studio.load({ get("/api/datasets/$n/curve/$e", 30_000) }) { curve = it }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel("Datasets", Modifier.width(300.dp).fillMaxHeight()) {
            if (studio.datasets.isEmpty()) Text("Nothing recorded yet.", style = Sci.small)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(studio.datasets) { d ->
                    val n = d["name"].str ?: ""
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .background(if (n == selected) Sci.accent.copy(alpha = 0.1f) else Sci.bg)
                            .clickable { selected = n; episode = null; curve = null; trim = null }.padding(10.dp),
                    ) {
                        Text(n, style = Sci.body.copy(color = if (n == selected) Sci.accent else Sci.text))
                        Text("${d["episodes"].int ?: 0} takes · ${d["seconds"].fmt(0, " s")} · ${d["cameras"].arr.joinToString { it.str ?: "" }}" +
                            if (d["edits"].bool) " · edited" else "", style = Sci.small)
                    }
                }
            }
        }
        val det = detail
        val name = selected
        if (name == null || det == null) {
            Panel("Takes", Modifier.weight(1f).fillMaxHeight()) { Text("Pick a dataset.", style = Sci.small) }
        } else Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val edits = det["edits"]
            Panel("Takes in $name", Modifier.fillMaxWidth().weight(1f), trailing = {
                Text(det["task"].str ?: "", style = Sci.small)
            }) {
                Row(Modifier.padding(bottom = 6.dp)) {
                    for ((h, w) in listOf("#" to 40, "length" to 80, "moved" to 90, "gripper mm" to 110, "trains" to 120, "idle lead / tail" to 140, "" to 120)) {
                        Text(h.uppercase(), style = Sci.small.copy(fontSize = 11.sp), modifier = Modifier.width(w.dp))
                    }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(det["episodes"].arr) { e ->
                        val ep = e["episode"].int ?: 0
                        val manual = e["manual"]
                        val auto = e["auto_trim"].arr.mapNotNull { it.int }
                        val trains = if (manual["start"].int != null || manual["end"].int != null)
                            "${manual["start"].int ?: 0}–${manual["end"].int ?: e["frames"].int} ✎"
                        else "${auto.getOrNull(0)}–${auto.getOrNull(1)}"
                        val ex = e["exclude"].bool
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                                .background(if (ep == episode) Sci.accent.copy(alpha = 0.08f) else Color.Transparent)
                                .clickable { episode = ep; curve = null; trim = null }.padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val c = if (ex) Sci.dim.copy(alpha = 0.6f) else Sci.text
                            Text("$ep", style = Sci.num.copy(color = c, fontSize = 13.sp), modifier = Modifier.width(40.dp))
                            Text(e["seconds"].fmt(1, " s"), style = Sci.num.copy(color = c, fontSize = 13.sp), modifier = Modifier.width(80.dp))
                            Text(e["travel_deg"].fmt(0, "°"), style = Sci.num.copy(color = c, fontSize = 13.sp), modifier = Modifier.width(90.dp))
                            Text(e["gripper_range_mm"].arr.joinToString("–") { it.fmt(0) }, style = Sci.num.copy(color = c, fontSize = 13.sp), modifier = Modifier.width(110.dp))
                            Text(trains, style = Sci.num.copy(color = c, fontSize = 13.sp), modifier = Modifier.width(120.dp))
                            Text("${e["idle_lead_s"].fmt(1)} / ${e["idle_tail_s"].fmt(1)} s", style = Sci.num.copy(color = c, fontSize = 13.sp), modifier = Modifier.width(140.dp))
                            if (!e["action_shift_ok"].bool) Pill("action≠next state", Sci.bad)
                            Spacer(Modifier.weight(1f))
                            SciButton(if (ex) "Include" else "Exclude", {
                                studio.act(if (ex) "Include" else "Exclude", if (ex) "Take $ep included" else "Take $ep excluded from training") {
                                    post("/api/datasets/$name/edits", mapOf("episodes" to mapOf("$ep" to mapOf("exclude" to !ex))))
                                }
                                reload(name)
                            }, Kind.Quiet)
                        }
                    }
                }
            }
            val e = det["episodes"].arr.firstOrNull { it["episode"].int == episode }
            val cv = curve
            Panel("Trim", Modifier.fillMaxWidth().height(330.dp), trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Check("auto-trim stillness", edits["auto_trim"].bool, { on ->
                        studio.act("Auto-trim") { post("/api/datasets/$name/edits", mapOf("auto_trim" to on)) }
                        reload(name)
                    })
                }
            }) {
                if (e == null || cv == null) {
                    Text("Pick a take to see its joints and set where training starts and ends.", style = Sci.small)
                } else {
                val frames = cv["frames"].int ?: 1
                val auto = e["auto_trim"].arr.mapNotNull { it.int }.let { (it.getOrElse(0) { 0 }) to (it.getOrElse(1) { frames }) }
                val manual = e["manual"]
                val cur = trim ?: ((manual["start"].int ?: auto.first) to (manual["end"].int ?: auto.second))
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        TrimEditor(
                            series = JOINTS.map { j -> cv["joints"][j].arr.mapNotNull { it.dbl } },
                            index = cv["index"].arr.mapNotNull { it.int }, frames = frames,
                            start = cur.first, end = cur.second, auto = auto,
                            onChange = { s, en -> trim = s to en },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("trains frames ${cur.first}–${cur.second} of $frames (%.1f s)".format((cur.second - cur.first) / 15.0), style = Sci.small)
                            Spacer(Modifier.weight(1f))
                            SciButton("Use automatic", {
                                studio.act("Reset trim", "Take $episode uses the automatic trim") {
                                    post("/api/datasets/$name/edits", mapOf("episodes" to mapOf("$episode" to mapOf("start" to null, "end" to null))))
                                }
                                trim = null
                                reload(name)
                            }, Kind.Quiet)
                            SciButton("Save trim", {
                                studio.act("Save trim", "Take $episode trains frames ${cur.first}–${cur.second}") {
                                    post("/api/datasets/$name/edits", mapOf("episodes" to mapOf("$episode" to mapOf("start" to cur.first, "end" to cur.second))))
                                }
                                reload(name)
                            }, Kind.Primary, enabled = trim != null)
                        }
                    }
                    Thumbs(studio, name, e["thumbs"].arr.mapNotNull { it.str }, Modifier.width(200.dp).fillMaxHeight())
                }
                }
            }
        }
    }
}

@Composable
private fun Thumbs(studio: Studio, dataset: String, files: List<String>, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (f in files) {
            var img by remember(f) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(f) { studio.load({ bytes("/api/datasets/$dataset/thumb/$f") }) { b -> img = b?.toImageBitmap() } }
            val bmp = img
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
                if (bmp != null) Image(bmp, f, Modifier.fillMaxSize())
                Text(if (f.endsWith("_0.jpg")) "first" else "last", style = Sci.small.copy(fontSize = 10.sp),
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
            }
        }
    }
}
