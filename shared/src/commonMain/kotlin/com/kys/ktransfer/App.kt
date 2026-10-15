package com.kys.ktransfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private const val KEY_API = "apiKey"
private const val KEY_PARTNER = "partner"

@Composable
fun App(platform: Platform) {
    val interpreter = remember { Interpreter(platform.audio) }
    DisposableEffect(Unit) { onDispose { interpreter.close() } }
    val state by interpreter.state.collectAsStateWithLifecycle()

    var apiKey by remember { mutableStateOf(platform.settings.load(KEY_API).orEmpty()) }
    var mode by remember { mutableStateOf(Mode.CONVERSATION) }
    var partner by remember {
        mutableStateOf(PARTNER_LANGUAGES.firstOrNull { it.code == platform.settings.load(KEY_PARTNER) } ?: PARTNER_LANGUAGES.first())
    }
    var echoCancel by remember { mutableStateOf(true) }
    var halfDuplex by remember { mutableStateOf(false) }
    var resumption by remember { mutableStateOf(false) }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!state.running) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it.trim() },
                        label = { Text("Gemini API 키 (기기에만 저장)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(mode == Mode.CONVERSATION, { mode = Mode.CONVERSATION }, { Text("대화") })
                        FilterChip(mode == Mode.LISTEN, { mode = Mode.LISTEN }, { Text("듣기(→한국어)") })
                        if (mode == Mode.CONVERSATION) LanguagePicker(partner) { partner = it }
                    }
                    Toggle("에코 제거(통화 경로)", echoCancel) { echoCancel = it }
                    Toggle("반이중: 번역 재생 중 마이크 막기", halfDuplex) { halfDuplex = it }
                    Toggle("세션 재개 요청(지원 확인용)", resumption) { resumption = it }
                    Button(
                        enabled = apiKey.isNotBlank(),
                        onClick = {
                            platform.settings.save(KEY_API, apiKey)
                            platform.settings.save(KEY_PARTNER, partner.code)
                            interpreter.start(apiKey, RunOptions(mode, partner, echoCancel, halfDuplex, resumption))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("시작") }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "마이크 ${state.micLevel}" + if (state.gated) " · 막힘(재생 중)" else "",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = { interpreter.stop() }) { Text("중지") }
                    }
                }

                val lanes = state.lanes
                if (lanes.size == 2) {
                    LanePane(lanes[1], flipped = true, modifier = Modifier.weight(1f))
                    LanePane(lanes[0], flipped = false, modifier = Modifier.weight(1f))
                } else if (lanes.size == 1) {
                    LanePane(lanes[0], flipped = false, modifier = Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                lanes.forEach { MetricsLine(it) }
                LogPanel(state.log)
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun LanguagePicker(selected: Language, onSelect: (Language) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("상대: ${selected.label}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PARTNER_LANGUAGES.forEach { lang ->
                DropdownMenuItem(text = { Text(lang.label) }, onClick = { onSelect(lang); open = false })
            }
        }
    }
}

@Composable
private fun LanePane(lane: LaneState, flipped: Boolean, modifier: Modifier) {
    Card(modifier.fillMaxWidth().then(if (flipped) Modifier.rotate(180f) else Modifier)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("→ ${lane.target.label} · ${lane.status}", style = MaterialTheme.typography.labelMedium)
            Text(lane.translated.takeLast(220), fontSize = 22.sp, lineHeight = 28.sp)
            Text(lane.source.takeLast(160), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

@Composable
private fun MetricsLine(lane: LaneState) {
    val avg = if (lane.latencies.isEmpty()) "-" else "${lane.latencies.average().toLong()}ms"
    val resumable = when (lane.resumable) { true -> "예"; false -> "아니오"; null -> "?" }
    Text(
        "[${lane.target.code}] 지연 최근 ${lane.lastLatencyMs?.let { "${it}ms" } ?: "-"} · 평균 $avg (n=${lane.latencies.size}) · 재개 $resumable",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun LogPanel(log: List<String>) {
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.scrollToItem(log.lastIndex) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(110.dp)) {
        items(log) { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
    }
}
