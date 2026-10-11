package com.hmaossdetector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hmaossdetector.ui.theme.HMAOSSDetectorTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HMAOSSDetectorTheme {
                ScanScreen()
            }
        }
    }
}

@Composable
private fun ScanScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(ScanReport()) }
    var timings by remember { mutableStateOf(emptyList<List<String>>()) }
    fun startScan() {
        if (running) {
            return
        }
        running = true
        report = ScanReport()
        timings = emptyList()
        scope.launch {
            val rounds = ArrayList<ScanReport>()
            for (round in 0 until SAMPLING_ROUNDS) {
                val result = scanInFreshProcess(context.applicationContext)
                rounds += result
                if (result.error != null) break
            }
            report = rounds.last()
            timings = queryTimingRows(rounds)
            running = false
        }
    }
    LaunchedEffect(Unit) {
        startScan()
    }
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize(),
        ) {
            val textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 20.sp)
            Button(enabled = !running, onClick = { startScan() }) {
                Text(if (running) "Scanning..." else "Scan again", style = textStyle)
            }
            SelectionContainer(modifier = Modifier.weight(1f).padding(top = 16.dp)) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (report.directories.isNotEmpty()) {
                        PlainTable(
                            headers = listOf("Directory", "UIDs", "Named", "Empty"),
                            rows = report.directories,
                            numericColumns = setOf(1, 2, 3),
                            weights = listOf(2.2f, 0.9f, 0.8f, 0.8f),
                            style = textStyle,
                        )
                    }
                    if (timings.isNotEmpty()) {
                        PlainTable(
                            headers = listOf("Group", "Avg µs", "Min µs", "Max µs"),
                            rows = timings,
                            numericColumns = setOf(1, 2, 3),
                            weights = listOf(1.5f, 1f, 1f, 1f),
                            style = textStyle,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    report.error?.let { error ->
                        Text(text = error, style = textStyle, modifier = Modifier.padding(top = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlainTable(
    headers: List<String>,
    rows: List<List<String>>,
    numericColumns: Set<Int>,
    weights: List<Float>,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        PlainRow(headers, numericColumns, weights, style, heading = true)
        rows.forEach { row -> PlainRow(row, numericColumns, weights, style, heading = false) }
    }
}

@Composable
private fun PlainRow(
    cells: List<String>,
    numericColumns: Set<Int>,
    weights: List<Float>,
    style: TextStyle,
    heading: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { index, cell ->
            val family = if (!heading && index in numericColumns) FontFamily.Monospace else FontFamily.Default
            Text(
                text = cell,
                style = style.copy(fontFamily = family),
                textAlign = if (index == 0) TextAlign.Start else TextAlign.End,
                modifier = Modifier.weight(weights.getOrElse(index) { 1f }),
            )
        }
    }
}
