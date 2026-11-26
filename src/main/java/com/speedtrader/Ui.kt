package com.speedtrader

// =====================================================================================
//  TELAS (Jetpack Compose) - visual inspirado em Binance / TradingView
// =====================================================================================

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

val BG = Color(0xFF0B0E11)
val CARD = Color(0xFF181A20)
val CARD2 = Color(0xFF1E2329)
val YEL = Color(0xFFF0B90B)
val GRN = Color(0xFF0ECB81)
val RED = Color(0xFFF6465D)
val TXT = Color(0xFFEAECEF)
val GRY = Color(0xFF848E9C)
val BLU = Color(0xFF3D8BFD)

fun pnlColor(v: Double) = if (v >= 0) GRN else RED

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Sched.setup(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val tab = intent.getIntExtra("tab", 0)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = YEL, onPrimary = Color.Black, background = BG, surface = CARD,
                    onSurface = TXT, onBackground = TXT, surfaceVariant = CARD2, secondary = YEL
                )
            ) { App(tab) }
        }
    }
}

// ------------------------------------------------------------------ componentes basicos
@Composable
fun T(s: String, c: Color = TXT, size: TextUnit = 14.sp, bold: Boolean = false, modifier: Modifier = Modifier) =
    Text(s, color = c, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, modifier = modifier)

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CARD)
            .padding(14.dp)
    ) { content() }
}

@Composable
fun RowScope.Stat(label: String, value: String, c: Color = TXT) {
    Column(Modifier.weight(1f)) {
        T(label, GRY, 11.sp)
        T(value, c, 15.sp, true)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) CARD2 else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) { T(text, if (selected) YEL else GRY, 13.sp, selected) }
}

// ------------------------------------------------------------------ app
@Composable
fun App(startTab: Int) {
    val ctx = LocalContext.current
    val dao = remember { Db.get(ctx).dao() }
    val acc by remember { dao.accountFlow() }.collectAsState(initial = null)
    val trades by remember { dao.tradesFlow(50) }.collectAsState(initial = emptyList())
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    var interval by rememberSaveable { mutableStateOf("15m") }
    var candles by remember { mutableStateOf<List<Candle>>(emptyList()) }

    // atualiza o grafico a cada 15s e roda o robo a cada 60s enquanto o app esta aberto
    LaunchedEffect(interval) {
        var n = 0
        while (true) {
            runCatching { candles = withContext(Dispatchers.IO) { Binance.klines(interval, 300) } }
            if (n % 4 == 0) Repo.tick(ctx)
            n++
            delay(15_000)
        }
    }

    Scaffold(
        containerColor = BG,
        bottomBar = {
            NavigationBar(containerColor = CARD) {
                listOf("Trade" to "📈", "Relatórios" to "📋", "Backtest" to "🧪", "Ajustes" to "⚙").forEachIndexed { i, (name, icon) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Text(icon, fontSize = 20.sp) },
                        label = { Text(name, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedTextColor = YEL, unselectedTextColor = GRY, indicatorColor = CARD2
                        )
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> TradeScreen(acc, candles, interval, { interval = it }, trades)
                1 -> ReportsScreen(acc)
                2 -> BacktestScreen(acc?.profile ?: 0)
                else -> SettingsScreen(acc)
            }
        }
    }
}

// ------------------------------------------------------------------ TELA TRADE
@Composable
fun TradeScreen(a: Account?, cs: List<Candle>, interval: String, onInterval: (String) -> Unit, trades: List<Trade>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val live = cs.lastOrNull()?.c ?: a?.lastPrice ?: 0.0
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("BTC/USDT", TXT, 18.sp, true)
                    T(if (live > 0) price(live) else "--", pnlColor(a?.change24h ?: 0.0), 28.sp, true)
                    T("${pct(a?.change24h ?: 0.0)}   Máx ${price(a?.high24h ?: 0.0)}   Mín ${price(a?.low24h ?: 0.0)}", GRY, 12.sp)
                }
                val on = a?.auto == true
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) GRN.copy(alpha = 0.15f) else CARD2)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) { T(if (on) "● AUTO\n${PROFILES[a!!.profile].name}" else "○ PARADO", if (on) GRN else GRY, 12.sp, true) }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 12.dp)) {
                listOf("15m", "1h", "4h", "1d").forEach { Chip(it, it == interval) { onInterval(it) } }
                Spacer(Modifier.weight(1f))
                T("EMA21 ", YEL, 11.sp); T("EMA50", BLU, 11.sp)
            }
        }
        item {
            CandleChart(cs, a, Modifier.fillMaxWidth().height(330.dp).padding(horizontal = 6.dp, vertical = 6.dp))
        }
        item {
            Card {
                T("Conta simulada (Spot • sem alavancagem)", GRY, 12.sp)
                Spacer(Modifier.height(6.dp))
                val eq = a?.equity(live) ?: CAPITAL
                T(usd(eq), TXT, 26.sp, true)
                Spacer(Modifier.height(8.dp))
                Row {
                    Stat("Caixa USDT", usd(a?.cash ?: CAPITAL))
                    Stat("BTC", btc(a?.btc ?: 0.0))
                    Stat("Trades hoje", "${a?.dayTrades ?: 0}")
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    val d = a?.dayPnl(live) ?: 0.0
                    val tot = a?.totalPnl(live) ?: 0.0
                    Stat("P&L hoje", sgn(d), pnlColor(d))
                    Stat("P&L total", sgn(tot), pnlColor(tot))
                    Stat("Total %", pct(tot / CAPITAL * 100), pnlColor(tot))
                }
                if (!a?.lastError.isNullOrEmpty()) T("⚠ ${a?.lastError}", RED, 11.sp)
                else if ((a?.lastUpdate ?: 0) > 0) T("Robô atualizado às ${hm(a!!.lastUpdate, "HH:mm:ss")}", GRY, 11.sp)
            }
        }
        if (a != null && a.btc > 0) item {
            Card {
                val u = a.btc * (live * (1 - FEE) - a.entryPrice) - a.entryFee
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("POSIÇÃO ABERTA (COMPRA)", YEL, 13.sp, true, Modifier.weight(1f))
                    T("${sgn(u)}", pnlColor(u), 15.sp, true)
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    Stat("Entrada", price(a.entryPrice))
                    Stat("Stop", price(a.stop), RED)
                    Stat("Alvo", price(a.target), GRN)
                }
                Spacer(Modifier.height(4.dp))
                T("Desde ${hm(a.entryTime)} • ${btc(a.btc)} BTC", GRY, 11.sp)
                T("Motivo: ${a.entryReason}", GRY, 11.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { scope.launch { Repo.closeNow(ctx) } }) { T("Fechar posição agora", RED, 13.sp) }
            }
        }
        item {
            val on = a?.auto == true
            Button(
                onClick = { scope.launch { Repo.setAuto(ctx, !on) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (on) RED else GRN),
                shape = RoundedCornerShape(10.dp)
            ) {
                T(if (on) "DESLIGAR ROBÔ AUTOMÁTICO" else "LIGAR ROBÔ AUTOMÁTICO", Color.White, 16.sp, true)
            }
            if (!on) T(
                "Dica: rode o Backtest antes de ligar o robô.", GRY, 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        item { T("Histórico de operações", TXT, 15.sp, true, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) }
        if (trades.isEmpty()) item { T("Nenhuma operação ainda.", GRY, 13.sp, modifier = Modifier.padding(16.dp)) }
        items(trades, key = { it.id }) { TradeRow(it) }
    }
}

@Composable
fun TradeRow(t: Trade) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("COMPRA → VENDA  •  ${PROFILES[t.profile].name}", if (t.pnl >= 0) GRN else RED, 12.sp, true)
                T("${hm(t.entryTime)} → ${hm(t.exitTime, "HH:mm")}   ${price(t.entryPrice)} → ${price(t.exitPrice)}", GRY, 11.sp)
                T(t.exitReason, GRY, 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                T(sgn(t.pnl), pnlColor(t.pnl), 14.sp, true)
                T(pct(t.pnlPct), pnlColor(t.pnl), 11.sp)
            }
        }
        if (open) T("Entrada: ${t.reason}\nQtd ${btc(t.qty)} BTC • Taxas ${usd(t.fees)}", GRY, 11.sp)
        HorizontalDivider(Modifier.padding(top = 8.dp), color = CARD2)
    }
}

// ------------------------------------------------------------------ GRAFICO DE CANDLES
@Composable
fun CandleChart(cs: List<Candle>, a: Account?, modifier: Modifier) {
    var visible by remember { mutableFloatStateOf(70f) }
    var offset by remember { mutableFloatStateOf(0f) }
    val e21 = remember(cs) { ema(DoubleArray(cs.size) { cs[it].c }, 21) }
    val e50 = remember(cs) { ema(DoubleArray(cs.size) { cs[it].c }, 50) }
    val gridColor = Color(0x22FFFFFF)
    Canvas(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CARD)
            .pointerInput(cs.size) {
                detectTransformGestures { _, pan, zoom, _ ->
                    visible = (visible / zoom).coerceIn(20f, max(20f, min(250f, cs.size.toFloat())))
                    val cw = size.width * 0.84f / visible
                    offset = (offset + pan.x / cw).coerceIn(0f, max(0f, cs.size - visible))
                }
            }
    ) {
        if (cs.size < 2) return@Canvas
        val chartW = size.width * 0.84f
        val priceH = size.height * 0.76f
        val volTop = size.height * 0.80f
        val n = visible.toInt().coerceIn(2, cs.size)
        val end = (cs.size - offset.toInt()).coerceIn(n, cs.size)
        val start = end - n
        val sub = cs.subList(start, end)
        var hi = sub.maxOf { it.h }
        var lo = sub.minOf { it.l }
        val pad = (hi - lo) * 0.06
        hi += pad; lo -= pad
        fun y(p: Double) = ((hi - p) / (hi - lo) * priceH).toFloat()
        val cw = chartW / n
        val paint = android.graphics.Paint().apply { color = GRY.toArgb(); textSize = 24f; isAntiAlias = true }

        for (g in 0..4) {
            val p = lo + (hi - lo) * g / 4
            val yy = y(p)
            drawLine(gridColor, Offset(0f, yy), Offset(chartW, yy))
            drawContext.canvas.nativeCanvas.drawText(price(p), chartW + 6f, yy + 8f, paint)
        }
        val maxV = sub.maxOf { it.v }
        clipRect(0f, 0f, chartW, size.height) {
            sub.forEachIndexed { k, c ->
                val x = k * cw + cw / 2
                val col = if (c.c >= c.o) GRN else RED
                drawLine(col, Offset(x, y(c.h)), Offset(x, y(c.l)), strokeWidth = max(1.5f, cw * 0.12f))
                val top = y(max(c.o, c.c))
                val bot = y(min(c.o, c.c))
                drawRect(col, Offset(x - cw * 0.36f, top), Size(cw * 0.72f, max(2f, bot - top)))
                val vh = (c.v / maxV * (size.height - volTop)).toFloat()
                drawRect(col.copy(alpha = 0.35f), Offset(x - cw * 0.36f, size.height - vh), Size(cw * 0.72f, vh))
            }
            clipRect(0f, 0f, chartW, priceH) {
                emaLine(e21, start, n, cw, ::y, YEL)
                emaLine(e50, start, n, cw, ::y, BLU)
            }
            if (a != null && a.btc > 0) {
                hLine(y(a.entryPrice), chartW, YEL)
                hLine(y(a.stop), chartW, RED)
                hLine(y(a.target), chartW, GRN)
            }
        }
        val last = cs.last()
        val ly = y(last.c).coerceIn(0f, priceH)
        val lc = if (last.c >= last.o) GRN else RED
        hLine(ly, chartW, lc)
        drawRect(lc, Offset(chartW, ly - 16f), Size(size.width - chartW, 32f))
        paint.color = android.graphics.Color.WHITE
        paint.isFakeBoldText = true
        drawContext.canvas.nativeCanvas.drawText(price(last.c), chartW + 6f, ly + 8f, paint)
    }
}

private fun DrawScope.emaLine(arr: DoubleArray, start: Int, n: Int, cw: Float, y: (Double) -> Float, color: Color) {
    val path = Path()
    for (k in 0 until n) {
        val px = k * cw + cw / 2
        val py = y(arr[start + k])
        if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    drawPath(path, color, style = Stroke(2.5f))
}

private fun DrawScope.hLine(yy: Float, w: Float, color: Color) =
    drawLine(color, Offset(0f, yy), Offset(w, yy), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))

// ------------------------------------------------------------------ TELA RELATORIOS
@Composable
fun ReportsScreen(a: Account?) {
    val ctx = LocalContext.current
    val dao = remember { Db.get(ctx).dao() }
    val reps by remember { dao.reportsFlow() }.collectAsState(initial = emptyList())
    var open by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Card {
                T("Resumo", YEL, 15.sp, true)
                Spacer(Modifier.height(6.dp))
                val tot = a?.totalPnl() ?: 0.0
                Row {
                    Stat("Dias", "${reps.size}")
                    Stat("Dias positivos", "${reps.count { it.pnl > 0 }}", GRN)
                    Stat("Dias negativos", "${reps.count { it.pnl < 0 }}", RED)
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    Stat("Resultado final", sgn(tot), pnlColor(tot))
                    Stat("Patrimônio", usd(a?.equity() ?: CAPITAL))
                    Stat("Retorno", pct(tot / CAPITAL * 100), pnlColor(tot))
                }
                Spacer(Modifier.height(6.dp))
                T("O relatório do dia fecha às 21h (janela 21h → 21h) e chega como notificação.", GRY, 11.sp)
            }
        }
        if (a != null && a.dayKey.isNotEmpty()) item {
            Card {
                T("Hoje até agora (${fmtDay(a.dayKey)})", TXT, 14.sp, true)
                Spacer(Modifier.height(6.dp))
                Row {
                    Stat("P&L do dia", sgn(a.dayPnl()), pnlColor(a.dayPnl()))
                    Stat("Ganhos", sgn(a.dayGains), GRN)
                    Stat("Perdas", sgn(a.dayLosses), RED)
                }
                T("${a.dayClosed} operações fechadas • ${a.dayWins} vencedoras", GRY, 11.sp)
            }
        }
        if (reps.isEmpty()) item {
            T("Nenhum relatório ainda. Ligue o robô; o primeiro relatório sai às 21h.", GRY, 13.sp, modifier = Modifier.padding(16.dp))
        }
        items(reps, key = { it.dayKey }) { r ->
            Card(Modifier.clickable { open = if (open == r.dayKey) null else r.dayKey }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T(fmtDay(r.dayKey), TXT, 15.sp, true)
                        T("Perfil ${PROFILES[r.profile].name} • ${r.trades} trades (${r.wins}✔ ${r.trades - r.wins}✘)", GRY, 11.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        T(sgn(r.pnl), pnlColor(r.pnl), 16.sp, true)
                        T(pct(r.pnlPct), pnlColor(r.pnl), 11.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    Stat("Ganhos", sgn(r.gains), GRN)
                    Stat("Perdas", sgn(r.losses), RED)
                    Stat("Taxas", usd(r.fees), GRY)
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    Stat("Início", usd(r.startEquity))
                    Stat("Fim", usd(r.endEquity))
                    Stat("Acumulado", sgn(r.cumPnl), pnlColor(r.cumPnl))
                }
                if (r.openPosition) T("Posição aberta no fechamento (valor a mercado).", YEL, 11.sp)
                if (open == r.dayKey) {
                    val ts by remember(r.dayKey) { dao.tradesOfDay(r.dayKey) }.collectAsState(initial = emptyList())
                    Spacer(Modifier.height(6.dp))
                    if (ts.isEmpty()) T("Sem operações neste dia.", GRY, 12.sp)
                    ts.forEach { TradeRow(it) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ TELA BACKTEST
@Composable
fun BacktestScreen(currentProfile: Int) {
    val scope = rememberCoroutineScope()
    var prof by remember { mutableIntStateOf(currentProfile) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    var data by remember { mutableStateOf<List<Candle>?>(null) }
    var results by remember { mutableStateOf<List<BtResult>>(emptyList()) }

    fun run(profiles: List<Int>) {
        if (running) return
        running = true
        scope.launch {
            try {
                val cs = data ?: withContext(Dispatchers.IO) {
                    status = "Baixando 90 dias de candles da Binance..."
                    Binance.history(90) { progress = it }
                }.also { data = it }
                status = "Simulando..."
                results = withContext(Dispatchers.Default) {
                    val ind = Ind(cs)
                    profiles.map { backtest(cs, it, ind) }
                }
                status = "Período: ${hm(cs[WARMUP].t, "dd/MM/yy")} → ${hm(cs.last().closeTime, "dd/MM/yy")} • ${cs.size - WARMUP} candles de 15m"
            } catch (e: Exception) {
                status = "Erro: ${e.message}"
            }
            running = false
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Card {
                T("Backtester offline – últimos 90 dias", YEL, 15.sp, true)
                T("Roda exatamente o mesmo robô sobre o histórico real (15m) com US$ 100, taxas de 0,1% e derrapagem.", GRY, 12.sp)
                Spacer(Modifier.height(8.dp))
                Row { PROFILES.forEachIndexed { i, p -> Chip(p.name, prof == i) { prof = i } } }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { run(listOf(prof)) }, enabled = !running, shape = RoundedCornerShape(8.dp)) {
                        T("Rodar backtest", Color.Black, 13.sp, true)
                    }
                    OutlinedButton(onClick = { run(PROFILES.indices.toList()) }, enabled = !running, shape = RoundedCornerShape(8.dp)) {
                        T("Comparar 3 perfis", YEL, 13.sp)
                    }
                }
                if (running) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = YEL)
                }
                if (status.isNotEmpty()) T(status, GRY, 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (results.size > 1) item {
            Card {
                T("Comparação", TXT, 14.sp, true)
                Spacer(Modifier.height(4.dp))
                Row { listOf("Perfil", "P&L", "Acerto", "DD máx", "Trades").forEach { T(it, GRY, 11.sp, modifier = Modifier.weight(1f)) } }
                results.forEach { r ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        T(PROFILES[r.profile].name, TXT, 12.sp, true, Modifier.weight(1f))
                        T(pct(r.pnlPct), pnlColor(r.pnl), 12.sp, true, Modifier.weight(1f))
                        T(String.format("%.0f%%", r.winRate), TXT, 12.sp, modifier = Modifier.weight(1f))
                        T(String.format("-%.1f%%", r.maxDD), RED, 12.sp, modifier = Modifier.weight(1f))
                        T("${r.trades.size}", TXT, 12.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        items(results) { r -> BtCard(r) }
        item {
            T(
                "Resultados passados não garantem resultados futuros. Use o backtest para escolher o perfil antes de ligar o robô.",
                GRY, 11.sp, modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
fun BtCard(r: BtResult) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(PROFILES[r.profile].name, YEL, 15.sp, true, Modifier.weight(1f))
            T("${sgn(r.pnl)} (${pct(r.pnlPct)})", pnlColor(r.pnl), 15.sp, true)
        }
        Spacer(Modifier.height(8.dp))
        EquityChart(r.equity, Modifier.fillMaxWidth().height(140.dp))
        Spacer(Modifier.height(8.dp))
        Row {
            Stat("Taxa de acerto", String.format("%.1f%%", r.winRate))
            Stat("Drawdown máx", String.format("-%.2f%%", r.maxDD), RED)
            Stat("Fator de lucro", String.format("%.2f", r.profitFactor))
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Stat("Operações", "${r.trades.size}")
            Stat("Ganho médio", sgn(r.avgWin), GRN)
            Stat("Perda média", sgn(r.avgLoss), RED)
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Stat("Dias +", "${r.posDays}/${r.days}")
            Stat("Melhor dia", sgn(r.bestDay), GRN)
            Stat("Pior dia", sgn(r.worstDay), RED)
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Stat("Saldo final", usd(r.endEquity))
            Stat("Taxas pagas", usd(r.fees), GRY)
            Stat("Buy & Hold", pct(r.buyHold), pnlColor(r.buyHold))
        }
        val days = if (r.days > 0) r.pnl / r.days else 0.0
        T("P&L esperado por dia: ${sgn(days)}  •  por mês (30d): ${sgn(days * 30)}", GRY, 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun EquityChart(eq: List<Double>, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(CARD2)) {
        if (eq.size < 2) return@Canvas
        val step = max(1, eq.size / 400)
        val pts = eq.filterIndexed { i, _ -> i % step == 0 } + eq.last()
        val lo = min(pts.min(), CAPITAL)
        val hi = max(pts.max(), CAPITAL)
        val rng = if (hi - lo == 0.0) 1.0 else hi - lo
        fun y(v: Double) = (size.height - 6 - (v - lo) / rng * (size.height - 12)).toFloat()
        val path = Path()
        pts.forEachIndexed { i, v ->
            val x = i * size.width / (pts.size - 1)
            if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
        }
        drawLine(GRY, Offset(0f, y(CAPITAL)), Offset(size.width, y(CAPITAL)), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        drawPath(path, if (pts.last() >= CAPITAL) GRN else RED, style = Stroke(3f))
    }
}

// ------------------------------------------------------------------ TELA AJUSTES
@Composable
fun SettingsScreen(a: Account?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Card {
                T("Perfil do trader", YEL, 15.sp, true)
                T("Muda a quantidade de confirmações exigidas, o risco por operação e os limites diários.", GRY, 12.sp)
                Spacer(Modifier.height(6.dp))
                PROFILES.forEachIndexed { i, p ->
                    val sel = (a?.profile ?: 0) == i
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                            .border(1.dp, if (sel) YEL else CARD2, RoundedCornerShape(10.dp))
                            .clickable { scope.launch { Repo.setProfile(ctx, i) } }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = sel, onClick = { scope.launch { Repo.setProfile(ctx, i) } },
                            colors = RadioButtonDefaults.colors(selectedColor = YEL))
                        Column(Modifier.weight(1f)) {
                            T(p.name, if (sel) YEL else TXT, 15.sp, true)
                            T(p.desc, GRY, 11.sp)
                        }
                    }
                }
            }
        }
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T("Recomeçar com US$ 100 todo dia", TXT, 14.sp, true)
                        T("Ligado: às 21h fecha a posição e o caixa volta a US$ 100. Desligado: o saldo continua de um dia para o outro.", GRY, 11.sp)
                    }
                    Switch(
                        checked = a?.resetDaily == true,
                        onCheckedChange = { v -> scope.launch { Repo.setResetDaily(ctx, v) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = YEL, checkedThumbColor = Color.Black)
                    )
                }
            }
        }
        item {
            Card {
                T("Conta", TXT, 14.sp, true)
                T("Capital inicial: ${usd(CAPITAL)} • Sem alavancagem • Somente BTC/USDT spot", GRY, 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { confirm = true }) { T("Zerar conta e histórico", RED, 13.sp) }
            }
        }
        item {
            Card {
                T("Como o robô decide", YEL, 15.sp, true)
                Spacer(Modifier.height(6.dp))
                T(STRATEGY_TEXT, TXT, 12.sp)
            }
        }
        item {
            Card {
                T("Funcionamento", TXT, 14.sp, true)
                T(
                    "• Preços públicos da Binance, sem login e sem chave de API.\n" +
                        "• O robô roda a cada 15 min, mesmo com o app fechado. Se o celular ficar sem internet, ao voltar ele processa os candles que perdeu.\n" +
                        "• Relatório diário às 21h por notificação.\n" +
                        "• Para funcionar melhor, em Configurações > Apps > Speed Trader > Bateria, escolha \"Sem restrições\".\n" +
                        "• Isto é um SIMULADOR: nenhuma ordem real é enviada.",
                    GRY, 12.sp
                )
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Zerar conta?") },
        text = { Text("Apaga todas as operações e relatórios e volta para US$ 100. O robô será desligado.") },
        confirmButton = { TextButton(onClick = { confirm = false; scope.launch { Repo.resetAll(ctx) } }) { Text("Zerar", color = RED) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
        containerColor = CARD
    )
}
