package com.nervus.act.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

private val TERMINAL = setOf("finished", "failed", "cancelled")

fun stateColor(state: String?): Color = when (state) {
    "finished", "accepted", "promote", "success" -> Sci.ok
    "failed", "cancelled", "rejected", "fail" -> Sci.bad
    "training", "preparing", "gating", "round running" -> Sci.accent
    "waiting", "queued" -> Sci.warn
    else -> Sci.dim
}

// ============================================================================================ Train

@Composable
fun TrainTab(studio: Studio) {
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var steps by remember { mutableStateOf("60000") }
    var chunk by remember { mutableStateOf("30") }
    var batch by remember { mutableStateOf("8") }
    var save by remember { mutableStateOf("5000") }
    var parent by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var metrics by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    val sel = selected ?: studio.jobs.firstOrNull()?.get("id").str
    // Reload the curve whenever the job list refreshes (every few seconds), rather than on a timer of its own.
    LaunchedEffect(sel, studio.jobs) {
        if (sel != null) studio.load({ get("/api/jobs/$sel/metrics", 10_000).arr }) { metrics = it }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(400.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel("New training job") {
                Field("name", name, { name = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' }.take(40) }, hint = "pick-cube-v1")
                Spacer(Modifier.height(10.dp))
                Text("DATASETS (same cameras)", style = Sci.small.copy(fontSize = 11.sp))
                val cams = studio.datasets.filter { it["name"].str in picked }.map { it["cameras"].arr.mapNotNull { c -> c.str } }.firstOrNull()
                for (d in studio.datasets) {
                    val n = d["name"].str ?: continue
                    val dc = d["cameras"].arr.mapNotNull { it.str }
                    Check("$n  ·  ${d["episodes"].int ?: 0} takes  ·  ${dc.joinToString("+")}", n in picked,
                        { on -> picked = if (on) picked + n else picked - n }, enabled = cams == null || cams == dc || n in picked)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("steps", steps, { steps = it.filter(Char::isDigit) }, Modifier.weight(1f))
                    Field("chunk", chunk, { chunk = it.filter(Char::isDigit) }, Modifier.weight(1f), enabled = parent == null)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("batch", batch, { batch = it.filter(Char::isDigit) }, Modifier.weight(1f))
                    Field("save every", save, { save = it.filter(Char::isDigit) }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Text("START FROM", style = Sci.small.copy(fontSize = 11.sp))
                Check("scratch (a new policy)", parent == null, { parent = null })
                for (p in studio.policies.take(6)) {
                    val pn = p["name"].str ?: continue
                    Check("fine-tune $pn", parent == pn, { on -> parent = if (on) pn else null })
                }
                Spacer(Modifier.height(12.dp))
                SciButton("Send to the Mac", {
                    studio.act("Queue job") {
                        val id = post("/api/jobs", mapOf("name" to name, "datasets" to picked.toList(), "steps" to steps.toIntOrNull(),
                            "chunk_size" to chunk.toIntOrNull(), "batch_size" to batch.toIntOrNull(), "save_freq" to save.toIntOrNull(),
                            "parent" to parent))["id"].str
                        selected = id
                        kotlinx.serialization.json.JsonObject(mapOf("message" to kotlinx.serialization.json.JsonPrimitive(
                            "Queued $id: the Mac picks it up within a minute while it is online")))
                    }
                }, Kind.Primary, enabled = name.isNotEmpty() && picked.isNotEmpty())
                Spacer(Modifier.height(8.dp))
                Text(
                    "Chunk 30 is two seconds of motion at 15 Hz, which worked better on the PiPER than ACT's default 100. " +
                        "On an M2 Pro, 60k steps take about seven hours; the Mac stays awake while it trains.",
                    style = Sci.small,
                )
            }
            WorkerLine(studio)
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel("Jobs", Modifier.fillMaxWidth().weight(1f)) {
                if (studio.jobs.isEmpty()) Text("No jobs yet.", style = Sci.small)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(studio.jobs) { j -> JobRow(studio, j, j["id"].str == sel) { selected = j["id"].str } }
                }
            }
            val job = studio.jobs.firstOrNull { it["id"].str == sel }
            Panel("Loss" + (sel?.let { "  ·  $it" } ?: ""), Modifier.fillMaxWidth().height(300.dp), trailing = {
                val m = job["status"]["metrics"]
                Text(listOfNotNull(m["loss"].str?.let { "loss $it" }, m["lr"].str?.let { "lr $it" }).joinToString("  ·  "), style = Sci.small)
            }) {
                LossChart(metrics.mapNotNull { r -> r["step"].dbl?.let { s -> r["loss"].dbl?.let { s to it } } }, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun JobRow(studio: Studio, j: JsonElement, selected: Boolean, onClick: () -> Unit) {
    val st = j["status"]
    val state = st["state"].str ?: "queued"
    val pct = (st["pct"].dbl ?: 0.0).coerceIn(0.0, 100.0)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(if (selected) Sci.accent.copy(alpha = 0.08f) else Sci.bg).clickable(onClick = onClick).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(j["id"].str ?: "", style = Sci.body)
            Pill(state.uppercase(), stateColor(state))
            if (j["job"]["type"].str == "posttrain") Pill("POST-TRAIN", Sci.violet)
            Spacer(Modifier.weight(1f))
            if (state !in TERMINAL && !j["cancel_requested"].bool) {
                SciButton("Cancel", { studio.act("Cancel", "Asked the Mac to stop ${j["id"].str}") { post("/api/jobs/${j["id"].str}/cancel") } }, Kind.Quiet)
            } else if (j["cancel_requested"].bool && state !in TERMINAL) Text("cancelling…", style = Sci.small)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Sci.line)) {
            Box(Modifier.fillMaxWidth((pct / 100).toFloat()).height(4.dp).background(stateColor(state)))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            listOfNotNull(
                st["phase"].str,
                st["step"].int?.let { s -> "step $s / ${st["target"].int ?: j["job"]["steps"].int ?: "?"}" },
                st["steps_per_s"].dbl?.let { "%.2f steps/s".format(it) },
                st["eta_hours"].dbl?.takeIf { state == "training" }?.let { "about %.1f h left".format(it) },
                st["restarts"].int?.takeIf { it > 0 }?.let { "$it restart(s), recovered" },
                st["published"].arr.size.takeIf { it > 0 }?.let { "$it published" },
            ).joinToString("  ·  "),
            style = Sci.small,
        )
        st["message"].str?.let { Text(it, style = Sci.small.copy(color = if (state == "failed") Sci.bad else Sci.dim)) }
        if (state == "training") st["warning"].str?.let { Text(it, style = Sci.small.copy(color = Sci.warn)) }
    }
}

@Composable
fun WorkerLine(studio: Studio) {
    val w = studio.status["worker"]
    Panel("Mac worker") {
        if (!w["seen"].bool) {
            Text("The Mac has never reported. Install the worker there: act/mac/install.sh", style = Sci.body.copy(color = Sci.warn))
        } else if (!w["alive"].bool) {
            Text("Last heard from ${w["age_s"].int ?: "?"} s ago. Queued jobs wait until it is back.", style = Sci.body.copy(color = Sci.warn))
        } else {
            Text("Online (${w["host"].str ?: "Mac"}): ${w["state"].str}" + (w["job"].str?.let { ", $it" } ?: ""), style = Sci.body.copy(color = Sci.ok))
        }
    }
}

// ============================================================================================ Policies

@Composable
fun PoliciesTab(studio: Studio) {
    val stats = studio.trials.groupBy { it["policy"].str }.mapValues { (_, rows) ->
        rows.size to rows.count { it["outcome"].str == "success" }
    }
    Panel("Policies", Modifier.fillMaxSize()) {
        if (studio.policies.isEmpty()) Text("Nothing published yet. Train one on the Train tab.", style = Sci.small)
        else Row(Modifier.padding(bottom = 6.dp)) {
            for ((h, w) in listOf("name" to 300, "role" to 100, "step" to 90, "loss" to 80, "trials" to 120, "gate" to 200, "created" to 170)) {
                Text(h.uppercase(), style = Sci.small.copy(fontSize = 11.sp), modifier = Modifier.width(w.dp))
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(studio.policies) { p ->
                val n = p["name"].str ?: ""
                val (t, ok) = stats[n] ?: (0 to 0)
                val dec = p["gate"]["decision"]
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (p["current"].bool) Sci.accent.copy(alpha = 0.08f) else Sci.bg)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.width(300.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(n, style = Sci.body.copy(color = if (p["current"].bool) Sci.accent else Sci.text))
                        if (p["current"].bool) {
                            Spacer(Modifier.width(8.dp))
                            Pill("CURRENT", Sci.accent)
                        }
                    }
                    Text(p["role"].str ?: "–", style = Sci.small, modifier = Modifier.width(100.dp))
                    Text(p["step"].str ?: "–", style = Sci.num.copy(fontSize = 13.sp), modifier = Modifier.width(90.dp))
                    Text(p["loss"].fmt(3), style = Sci.num.copy(fontSize = 13.sp), modifier = Modifier.width(80.dp))
                    Text(if (t > 0) "$ok/$t (${100 * ok / t}%)" else "none", style = Sci.num.copy(fontSize = 13.sp), modifier = Modifier.width(120.dp))
                    Text(
                        if (dec["pass"].str != null) (if (dec["pass"].bool) "passed" else "failed") + dec["score"].dbl?.let { ", score %.2f".format(it) }.orEmpty() else "–",
                        style = Sci.small.copy(color = if (dec["pass"].bool) Sci.ok else Sci.dim), modifier = Modifier.width(200.dp),
                    )
                    Text(p["created"].str?.replace("T", " ")?.removeSuffix("Z") ?: "–", style = Sci.small, modifier = Modifier.width(170.dp))
                    Spacer(Modifier.weight(1f))
                    SciButton("Run", {
                        studio.tab = Tab.Run
                        studio.act("Load", "Loaded $n") { post("/api/run/load", mapOf("name" to n), 300_000) }
                    })
                    Spacer(Modifier.width(6.dp))
                    SciButton("Make CURRENT", {
                        studio.act("Promote") { post("/api/policies/promote", mapOf("name" to n)) }
                    }, Kind.Quiet, enabled = !p["current"].bool)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Promotion is automatic only on real results: a candidate needs at least 10 labelled runs and a success rate no " +
                "lower than CURRENT's last 10. Make CURRENT overrides that.",
            style = Sci.small,
        )
    }
}

// ============================================================================================ Post-train

@Composable
fun PostTrainTab(studio: Studio) {
    val pt = studio.status["posttrain"]
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(420.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel("Post-training") {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Stat("state", pt["state"].str ?: "–", stateColor(pt["state"].str))
                    Stat("new episodes", "${pt["untrained_episodes"].int ?: 0}")
                    Stat("new corrections", "${pt["untrained_corrections"].int ?: 0}")
                }
                Spacer(Modifier.height(8.dp))
                pt["message"].str?.let { Text(it, style = Sci.body.copy(color = Sci.warn)) }
                Text("A round starts after ${pt["trigger"].str ?: "5 new corrections or 10 new episodes"}.", style = Sci.small)
                Spacer(Modifier.height(10.dp))
                SciButton("Post-train now", {
                    studio.act("Post-train", "Asked the Mac for a round; it starts within a minute if there are new episodes") {
                        post("/api/posttrain/request", mapOf("note" to "from ACT Studio"))
                    }
                }, Kind.Primary)
            }
            Panel("CURRENT on the arm") {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Stat("policy", pt["current"].str ?: studio.status["current_policy"].str ?: "none", Sci.accent)
                    Stat("last 10 runs", pt["current_success_rate"].dbl?.let { "%.0f %%".format(it * 100) } ?: "no runs")
                    Stat("runs counted", "${pt["current_trials"].int ?: 0}")
                }
            }
            Panel("Candidates") {
                val cands = pt["candidates"].arr
                if (cands.isEmpty()) Text("None waiting. A round publishes the checkpoints that pass the offline gate.", style = Sci.small)
                for (c in cands) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(c["name"].str ?: "", style = Sci.body)
                        Text("${c["trials"].int ?: 0} runs" + c["success_rate"].dbl?.let { ", %.0f %% success".format(it * 100) }.orEmpty() +
                            " · " + (c["verdict"].str ?: ""), style = Sci.small.copy(color = stateColor(c["verdict"].str?.substringBefore(":"))))
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel("Runs the Mac has looked at", Modifier.fillMaxWidth().weight(1f)) {
                val runs = pt["recent_runs"].arr
                if (runs.isEmpty()) Text("Label runs on the Run tab: each labelled run goes to the Mac.", style = Sci.small)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(runs) { r ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(r["run_id"].str ?: "", style = Sci.num.copy(fontSize = 12.sp), modifier = Modifier.width(320.dp))
                            Pill((r["outcome"].str ?: "?").uppercase(), stateColor(r["outcome"].str))
                            Pill((r["status"].str ?: "?").uppercase(), stateColor(r["status"].str))
                            Text(r["reason"].str ?: r["episodes"].arr.joinToString { it.str ?: "" }.let { "episodes: $it" } +
                                (r["trained_in"].int?.let { " · trained in round $it" } ?: ""), style = Sci.small)
                        }
                    }
                }
            }
            Panel("Rounds", Modifier.fillMaxWidth().weight(1f)) {
                val rounds = pt["rounds"].arr.reversed()
                if (rounds.isEmpty()) Text("No rounds yet.", style = Sci.small)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rounds) { r ->
                        val a = r["gate"]["A"]
                        val b = r["gate"]["B"]
                        val parent = r["parent"].str ?: ""
                        Column {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Round ${r["round"].int}", style = Sci.body)
                                Pill(if (r["failed"].str != null) "FAILED" else if (r["finished"].str != null) "DONE" else "RUNNING",
                                    if (r["failed"].str != null) Sci.bad else if (r["finished"].str != null) Sci.ok else Sci.accent)
                                Text("${r["new"].int ?: 0} new + ${r["prior"].int ?: 0} earlier episodes, ${r["held_out"].int ?: 0} held out", style = Sci.small)
                            }
                            r["failed"].str?.let { Text(it, style = Sci.small.copy(color = Sci.bad)) }
                            if (a["samples"].int != null) {
                                val best = r["decisions"].obj.entries.minByOrNull { it.value["score"].dbl ?: 9e9 }
                                Text(
                                    "gate A (no forgetting): CURRENT ${a[parent].fmt(3)}, hold-still ${a["hold_still"].fmt(3)}" +
                                        (best?.let { ", best ${it.key} ${a[it.key].fmt(3)}" } ?: "") +
                                        (if (b["samples"].int != null) " · gate B (corrections): CURRENT ${b[parent].fmt(3)}" +
                                            (best?.let { " vs ${b[it.key].fmt(3)}" } ?: "") else ""),
                                    style = Sci.small,
                                )
                            }
                            val pub = r["published"].arr.mapNotNull { it.str }
                            if (pub.isNotEmpty()) Text("published: ${pub.joinToString()}", style = Sci.small.copy(color = Sci.ok))
                        }
                    }
                }
            }
        }
    }
}
