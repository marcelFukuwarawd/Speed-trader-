package com.speedtrader

// =====================================================================================
//  MOTOR DO SPEED TRADER  -  dados, indicadores, estrategia, robo, banco e agendamento
//  Somente BTC/USDT, mercado a vista (spot), SEM alavancagem, capital inicial US$ 100.
//  Dados publicos da Binance (sem login, sem chave de API).
// =====================================================================================

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// ------------------------------------------------------------------ constantes
const val FEE = 0.001          // taxa Binance spot 0,10% por lado
const val SLIP = 0.0003        // derrapagem simulada 0,03%
const val BAR = 15 * 60_000L   // candle de 15 minutos (tempo grafico do robo)
const val WARMUP = 210         // candles necessarios para os indicadores (EMA 200)
const val CAPITAL = 100.0      // capital inicial em US$
const val REPORT_HOUR = 21     // relatorio diario as 21h

// ------------------------------------------------------------------ formatacao
fun usd(v: Double) = String.format(Locale.US, "$%,.2f", v)
fun sgn(v: Double) = (if (v >= 0) "+" else "-") + String.format(Locale.US, "$%,.2f", abs(v))
fun pct(v: Double) = String.format(Locale.US, "%+.2f%%", v)
fun price(v: Double) = String.format(Locale.US, "%,.2f", v)
fun btc(v: Double) = String.format(Locale.US, "%.6f", v)
fun hm(ts: Long, p: String = "dd/MM HH:mm"): String =
    DateTimeFormatter.ofPattern(p).format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))
fun fmtDay(key: String): String = runCatching {
    LocalDate.parse(key).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
}.getOrDefault(key)

// "Dia de trade" = janela de 24h que TERMINA as 21h (ex.: 25/11 21:00 -> 26/11 21:00 = dia 26/11)
fun dayKey(ts: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val z = Instant.ofEpochMilli(ts).atZone(zone)
    return (if (z.hour >= REPORT_HOUR) z.toLocalDate().plusDays(1) else z.toLocalDate()).toString()
}
fun boundary(key: String, zone: ZoneId = ZoneId.systemDefault()): Long =
    LocalDate.parse(key).atTime(REPORT_HOUR, 0).atZone(zone).toInstant().toEpochMilli()

// =====================================================================================
//  PERFIS DE TRADER
// =====================================================================================
data class Profile(
    val name: String,
    val desc: String,
    val minScore: Int,      // quantas confirmacoes tecnicas sao exigidas para comprar
    val trend: Int,         // rigor do filtro de tendencia: 2 forte, 1 medio, 0 fraco
    val riskPct: Double,    // risco por operacao (% do patrimonio ate o stop)
    val maxPos: Double,     // tamanho maximo da posicao (% do patrimonio)
    val stopAtr: Double,    // stop = X vezes o ATR
    val rr: Double,         // alvo = R vezes o risco
    val trailAtr: Double,   // trailing stop = X vezes o ATR abaixo da maxima
    val maxBars: Int,       // tempo maximo na operacao (em candles de 15m)
    val maxTrades: Int,     // maximo de entradas por dia
    val dayLossPct: Double, // perda maxima diaria -> robo para ate o proximo dia
    val cooldown: Int,      // candles de pausa apos uma perda
    val rsiMax: Double      // nao compra acima deste RSI (sobrecompra)
)

val PROFILES = listOf(
    Profile(
        "Conservador",
        "Só compra a favor de tendência forte, com 4+ confirmações. Risco 1% por trade, posição máx. 50%, até 3 trades/dia, para o dia com -2%.",
        minScore = 4, trend = 2, riskPct = 0.01, maxPos = 0.50, stopAtr = 1.5, rr = 2.0,
        trailAtr = 2.0, maxBars = 48, maxTrades = 3, dayLossPct = 0.02, cooldown = 8, rsiMax = 65.0
    ),
    Profile(
        "Arrojado",
        "Tendência média, 3+ confirmações. Risco 2% por trade, posição máx. 80%, até 6 trades/dia, para o dia com -4%.",
        minScore = 3, trend = 1, riskPct = 0.02, maxPos = 0.80, stopAtr = 2.0, rr = 2.5,
        trailAtr = 2.5, maxBars = 72, maxTrades = 6, dayLossPct = 0.04, cooldown = 4, rsiMax = 70.0
    ),
    Profile(
        "Arriscado",
        "Aceita 2 confirmações e tendência fraca. Risco 3,5% por trade, posição até 100%, até 12 trades/dia, para o dia com -8%.",
        minScore = 2, trend = 0, riskPct = 0.035, maxPos = 1.0, stopAtr = 2.5, rr = 3.0,
        trailAtr = 3.0, maxBars = 96, maxTrades = 12, dayLossPct = 0.08, cooldown = 1, rsiMax = 78.0
    )
)

const val STRATEGY_TEXT = """O robô combina as regras mais testadas dos clássicos do trading:

• Tela Tripla (Alexander Elder – Trading for a Living): só compra a favor da "maré". Tendência definida pelas EMAs 50 e 200 (equivalente às EMAs 12/50 do gráfico de 1h).
• Pullback com RSI (J. Welles Wilder – New Concepts; Constance Brown): entra quando o RSI cai abaixo de 42 e volta a subir, comprando o recuo dentro da alta.
• MACD (Gerald Appel) / Sistema Impulso (Elder): histograma positivo e crescente confirma o momento.
• Cruzamento EMA 9 x 21 (John Murphy – Análise Técnica do Mercado Financeiro).
• Pullback na EMA 21 – "Holy Grail" (Linda Raschke – Street Smarts).
• Bandas de Bollinger (John Bollinger – Bollinger on Bollinger Bands): repique na banda inferior ou suporte na média central.
• Rompimento de Donchian 20 com volume (Regras das Tartarugas – Richard Dennis / Curtis Faith).
• Candle de força com volume (Al Brooks – Reading Price Charts Bar by Bar; Wyckoff).

Gestão de risco (Van K. Tharp – Trade Your Way to Financial Freedom; Elder – regra dos 2% e 6%; Mark Douglas – Trading in the Zone):
• Stop pelo ATR (Wilder), alvo em múltiplos de R, stop no empate após +1R e trailing stop após +1,5R.
• Tamanho da posição pelo risco: (patrimônio × risco%) ÷ distância do stop, limitado ao caixa (sem alavancagem).
• Limite de perda diária, limite de trades por dia e pausa após perda (evita "revenge trading").
• Filtro de custo: só opera se o alvo cobrir taxas (0,1% + 0,1%) com folga.
• Mercado à vista: somente compra (long). Vender a descoberto exigiria margem/alavancagem."""

// =====================================================================================
//  BINANCE (API PUBLICA - SEM LOGIN)
// =====================================================================================
data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double, val closeTime: Long)
data class Ticker(val last: Double, val change: Double, val high: Double, val low: Double)

object Binance {
    private val hosts = listOf("https://data-api.binance.vision", "https://api.binance.com", "https://api.binance.us")
    @Volatile private var good = 0

    private fun get(path: String): String {
        var last: Exception? = null
        for (k in hosts.indices) {
            val idx = (good + k) % hosts.size
            try {
                val con = URL(hosts[idx] + path).openConnection() as HttpURLConnection
                con.connectTimeout = 10_000
                con.readTimeout = 20_000
                try {
                    if (con.responseCode == 200) {
                        good = idx
                        return con.inputStream.bufferedReader().use { it.readText() }
                    }
                    last = Exception("HTTP ${con.responseCode}")
                } finally {
                    con.disconnect()
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: Exception("Sem conexão")
    }

    fun klines(interval: String = "15m", limit: Int = 1000, start: Long? = null): List<Candle> {
        var p = "/api/v3/klines?symbol=BTCUSDT&interval=$interval&limit=$limit"
        if (start != null) p += "&startTime=$start"
        val arr = JSONArray(get(p))
        return List(arr.length()) { i ->
            val a = arr.getJSONArray(i)
            Candle(
                a.getLong(0), a.getString(1).toDouble(), a.getString(2).toDouble(),
                a.getString(3).toDouble(), a.getString(4).toDouble(), a.getString(5).toDouble(), a.getLong(6) + 1
            )
        }
    }

    fun ticker(): Ticker {
        val o = JSONObject(get("/api/v3/ticker/24hr?symbol=BTCUSDT"))
        return Ticker(
            o.getString("lastPrice").toDouble(), o.getString("priceChangePercent").toDouble(),
            o.getString("highPrice").toDouble(), o.getString("lowPrice").toDouble()
        )
    }

    /** Baixa os ultimos [days] dias de candles de 15m (+ aquecimento dos indicadores). */
    fun history(days: Int, progress: (Int) -> Unit = {}): List<Candle> {
        val now = System.currentTimeMillis()
        var start = now - days * 86_400_000L - (WARMUP + 10) * BAR
        val total = ((now - start) / BAR).toInt()
        val out = ArrayList<Candle>()
        while (start < now) {
            val part = klines("15m", 1000, start)
            if (part.isEmpty()) break
            out.addAll(part)
            start = part.last().t + BAR
            progress(min(100, out.size * 100 / max(1, total)))
            if (part.size < 1000) break
        }
        return out.filter { it.closeTime <= now }
    }
}

// =====================================================================================
//  INDICADORES
// =====================================================================================
fun ema(x: DoubleArray, n: Int): DoubleArray {
    val o = DoubleArray(x.size)
    if (x.isEmpty()) return o
    val k = 2.0 / (n + 1)
    o[0] = x[0]
    for (i in 1 until x.size) o[i] = x[i] * k + o[i - 1] * (1 - k)
    return o
}

private fun wilder(up: DoubleArray, n: Int): DoubleArray {
    val o = DoubleArray(up.size)
    var s = 0.0
    for (i in up.indices) {
        s = if (i < n) s + up[i] / n else (s * (n - 1) + up[i]) / n
        o[i] = s
    }
    return o
}

class Ind(val c: List<Candle>) {
    val n = c.size
    val close = DoubleArray(n) { c[it].c }
    val e9 = ema(close, 9)
    val e21 = ema(close, 21)
    val e50 = ema(close, 50)
    val e200 = ema(close, 200)
    val rsi: DoubleArray
    val atr: DoubleArray
    val macdH: DoubleArray
    val bbMid = DoubleArray(n)
    val bbLow = DoubleArray(n)
    val bbUp = DoubleArray(n)
    val volAvg = DoubleArray(n)
    val donHigh = DoubleArray(n)

    init {
        val g = DoubleArray(n) { if (it == 0) 0.0 else max(close[it] - close[it - 1], 0.0) }
        val l = DoubleArray(n) { if (it == 0) 0.0 else max(close[it - 1] - close[it], 0.0) }
        val ag = wilder(g, 14)
        val al = wilder(l, 14)
        rsi = DoubleArray(n) { if (it < 14) 50.0 else if (al[it] == 0.0) 100.0 else 100 - 100 / (1 + ag[it] / al[it]) }
        val tr = DoubleArray(n) {
            val k = c[it]
            if (it == 0) k.h - k.l else maxOf(k.h - k.l, abs(k.h - c[it - 1].c), abs(k.l - c[it - 1].c))
        }
        atr = wilder(tr, 14)
        val e12 = ema(close, 12)
        val e26 = ema(close, 26)
        val m = DoubleArray(n) { e12[it] - e26[it] }
        val s = ema(m, 9)
        macdH = DoubleArray(n) { m[it] - s[it] }
        for (i in 0 until n) {
            val from = max(0, i - 19)
            var sum = 0.0
            var vs = 0.0
            for (j in from..i) { sum += close[j]; vs += c[j].v }
            val cnt = i - from + 1
            val mean = sum / cnt
            var sq = 0.0
            for (j in from..i) sq += (close[j] - mean) * (close[j] - mean)
            val sd = sqrt(sq / cnt)
            bbMid[i] = mean; bbLow[i] = mean - 2 * sd; bbUp[i] = mean + 2 * sd
            volAvg[i] = vs / cnt
            var hh = 0.0
            for (j in max(0, i - 20) until i) hh = max(hh, c[j].h)
            donHigh[i] = if (i == 0) c[0].h else hh
        }
    }
}

// =====================================================================================
//  ESTRATEGIA (pontuacao por confluencia)
// =====================================================================================
data class Signal(val score: Int, val trendOk: Boolean, val reasons: List<String>)

object Strategy {
    fun evaluate(d: Ind, i: Int, p: Profile): Signal {
        val c = d.c[i]
        val r = mutableListOf<String>()
        val trend = when (p.trend) {
            2 -> c.c > d.e200[i] && d.e50[i] > d.e200[i] && d.e50[i] > d.e50[i - 4]
            1 -> c.c > d.e200[i] && d.e21[i] > d.e50[i]
            else -> c.c > d.e200[i] || d.e21[i] > d.e50[i]
        }
        var s = 0
        // 1) Pullback no RSI (Wilder / Brown)
        val minRsi = (i - 6 until i).minOf { d.rsi[it] }
        if (minRsi < 42 && d.rsi[i] > d.rsi[i - 1] && d.rsi[i] >= 45 && d.rsi[i] <= p.rsiMax) {
            s++; r += "RSI saiu do pullback (${minRsi.toInt()}→${d.rsi[i].toInt()})"
        }
        // 2) MACD impulso (Appel / Elder)
        if (d.macdH[i] > 0 && d.macdH[i] > d.macdH[i - 1]) { s++; r += "MACD positivo e crescente" }
        // 3) Cruzamento EMA 9 x 21 (Murphy)
        if (d.e9[i] > d.e21[i] && (i - 3 until i).any { d.e9[it] <= d.e21[it] }) { s++; r += "Cruzamento EMA9 > EMA21" }
        // 4) Bollinger
        val touchedLow = (i - 3..i).any { d.c[it].l <= d.bbLow[it] }
        if (touchedLow && c.c > d.bbLow[i] && c.c > c.o) {
            s++; r += "Repique na banda inferior de Bollinger"
        } else if (trend && c.l <= d.bbMid[i] && c.c > d.bbMid[i] && c.c > c.o) {
            s++; r += "Suporte na média de Bollinger"
        }
        // 5) Rompimento Donchian 20 com volume (Tartarugas)
        if (c.c > d.donHigh[i] && c.v > 1.5 * d.volAvg[i]) { s++; r += "Rompimento da máxima de 20 candles c/ volume" }
        // 6) Candle de forca com volume (Brooks / Wyckoff)
        if (c.c > c.o && c.v > 1.2 * d.volAvg[i] && (c.c - c.o) > 0.5 * (c.h - c.l)) { s++; r += "Candle de força com volume" }
        // 7) Pullback na EMA 21 - Holy Grail (Raschke)
        if (c.l <= d.e21[i] && c.c > d.e21[i] && d.e21[i] > d.e50[i]) { s++; r += "Pullback na EMA21 (Holy Grail)" }

        return Signal(s, trend && d.rsi[i] <= p.rsiMax, r)
    }
}

// =====================================================================================
//  BANCO DE DADOS ROOM
// =====================================================================================
@Entity
data class Account(
    @PrimaryKey val id: Int = 1,
    val cash: Double = CAPITAL,
    val btc: Double = 0.0,
    val entryPrice: Double = 0.0,
    val entryTime: Long = 0,
    val entryFee: Double = 0.0,
    val stop: Double = 0.0,
    val target: Double = 0.0,
    val highest: Double = 0.0,
    val risk: Double = 0.0,
    val barsHeld: Int = 0,
    val entryReason: String = "",
    val lastCandle: Long = 0,
    val profile: Int = 0,
    val auto: Boolean = false,
    val resetDaily: Boolean = false,
    val dayKey: String = "",
    val dayStartEquity: Double = CAPITAL,
    val dayTrades: Int = 0,
    val dayClosed: Int = 0,
    val dayWins: Int = 0,
    val dayRealized: Double = 0.0,
    val dayGains: Double = 0.0,
    val dayLosses: Double = 0.0,
    val dayFees: Double = 0.0,
    val cumPnl: Double = 0.0,
    val cooldownUntil: Long = 0,
    val lastPrice: Double = 0.0,
    val change24h: Double = 0.0,
    val high24h: Double = 0.0,
    val low24h: Double = 0.0,
    val spark: String = "",
    val lastUpdate: Long = 0,
    val lastError: String = ""
) {
    fun equity(p: Double = lastPrice) = cash + btc * p
    fun dayPnl(p: Double = lastPrice) = equity(p) - dayStartEquity
    fun totalPnl(p: Double = lastPrice) = cumPnl + dayPnl(p)
}

@Entity
data class Trade(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryTime: Long,
    val exitTime: Long,
    val entryPrice: Double,
    val exitPrice: Double,
    val qty: Double,
    val pnl: Double,
    val pnlPct: Double,
    val fees: Double,
    val reason: String,
    val exitReason: String,
    val profile: Int,
    val dayKey: String
)

@Entity
data class DailyReport(
    @PrimaryKey val dayKey: String,
    val startEquity: Double,
    val endEquity: Double,
    val pnl: Double,
    val pnlPct: Double,
    val realized: Double,
    val gains: Double,
    val losses: Double,
    val trades: Int,
    val wins: Int,
    val fees: Double,
    val cumPnl: Double,
    val openPosition: Boolean,
    val profile: Int,
    val notified: Boolean = false,
    val createdAt: Long = 0
)

@androidx.room.Dao
interface TradeDao {
    @Query("SELECT * FROM Account WHERE id = 1") suspend fun account(): Account?
    @Query("SELECT * FROM Account WHERE id = 1") fun accountFlow(): Flow<Account?>
    @Upsert suspend fun save(a: Account)

    @Insert suspend fun insertTrades(t: List<Trade>)
    @Query("SELECT * FROM Trade ORDER BY exitTime DESC LIMIT :n") suspend fun trades(n: Int): List<Trade>
    @Query("SELECT * FROM Trade ORDER BY exitTime DESC LIMIT :n") fun tradesFlow(n: Int): Flow<List<Trade>>
    @Query("SELECT * FROM Trade WHERE dayKey = :k ORDER BY exitTime") fun tradesOfDay(k: String): Flow<List<Trade>>

    @Upsert suspend fun saveReports(r: List<DailyReport>)
    @Query("SELECT * FROM DailyReport ORDER BY dayKey DESC") fun reportsFlow(): Flow<List<DailyReport>>
    @Query("SELECT * FROM DailyReport WHERE notified = 0") suspend fun pendingReports(): List<DailyReport>
    @Query("UPDATE DailyReport SET notified = 1 WHERE dayKey = :k") suspend fun markNotified(k: String)

    @Query("DELETE FROM Trade") suspend fun clearTrades()
    @Query("DELETE FROM DailyReport") suspend fun clearReports()
}

@Database(entities = [Account::class, Trade::class, DailyReport::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): TradeDao

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, Db::class.java, "speedtrader.db")
                .fallbackToDestructiveMigration().build().also { inst = it }
        }
    }
}

// =====================================================================================
//  ROBO (mesmo codigo para o modo automatico ao vivo e para o backtest)
//  Processa candles FECHADOS de 15m em ordem. Se o celular ficar sem rodar por um tempo,
//  ele "recupera" os candles perdidos usando maxima/minima para checar stop e alvo.
// =====================================================================================
class Engine(var a: Account, private val zone: ZoneId = ZoneId.systemDefault()) {
    val trades = mutableListOf<Trade>()
    val reports = mutableListOf<DailyReport>()
    val equity = mutableListOf<Double>()
    private val p get() = PROFILES[a.profile.coerceIn(0, PROFILES.lastIndex)]

    fun run(cs: List<Candle>, d: Ind = Ind(cs)) {
        for (i in WARMUP until cs.size) {
            if (cs[i].t <= a.lastCandle) continue
            step(cs, d, i)
        }
        val last = cs.lastOrNull() ?: return
        if (a.dayKey.isNotEmpty() && last.closeTime >= boundary(a.dayKey, zone)) closeDay(last.c, dayKey(last.closeTime, zone))
    }

    private fun newDay(key: String, px: Double) {
        a = a.copy(
            dayKey = key, dayStartEquity = a.equity(px), dayTrades = 0, dayClosed = 0, dayWins = 0,
            dayRealized = 0.0, dayGains = 0.0, dayLosses = 0.0, dayFees = 0.0
        )
    }

    private fun closeDay(px: Double, next: String) {
        if (a.resetDaily && a.btc > 0) sell(px, "Fechamento do dia (21h)", boundary(a.dayKey, zone))
        val end = a.equity(px)
        val pnl = end - a.dayStartEquity
        reports += DailyReport(
            dayKey = a.dayKey, startEquity = a.dayStartEquity, endEquity = end, pnl = pnl,
            pnlPct = if (a.dayStartEquity > 0) pnl / a.dayStartEquity * 100 else 0.0,
            realized = a.dayRealized, gains = a.dayGains, losses = a.dayLosses, trades = a.dayClosed,
            wins = a.dayWins, fees = a.dayFees, cumPnl = a.cumPnl + pnl, openPosition = a.btc > 0,
            profile = a.profile, createdAt = System.currentTimeMillis()
        )
        a = a.copy(cumPnl = a.cumPnl + pnl)
        if (a.resetDaily) a = a.copy(cash = CAPITAL, btc = 0.0)
        newDay(next, px)
    }

    private fun step(cs: List<Candle>, d: Ind, i: Int) {
        val c = cs[i]
        val key = dayKey(c.closeTime - 1, zone)
        if (a.dayKey.isEmpty()) newDay(key, c.o) else if (key != a.dayKey) closeDay(cs[i - 1].c, key)

        var exited = false
        if (a.btc > 0) {
            var px = 0.0
            var why = ""
            if (c.l <= a.stop) {
                px = min(c.o, a.stop)
                why = if (a.stop > a.entryPrice) "Trailing stop (lucro protegido)" else "Stop loss"
            } else if (c.h >= a.target) {
                px = max(c.o, a.target); why = "Alvo atingido (${p.rr}R)"
            } else if (a.barsHeld >= 2 && c.c < d.e50[i] && d.e21[i] < d.e50[i] && d.macdH[i] < 0) {
                px = c.c; why = "Perda de tendência (EMA50 + MACD)"
            } else if (a.barsHeld + 1 >= p.maxBars) {
                px = c.c; why = "Tempo máximo da operação"
            }
            if (why.isNotEmpty()) {
                sell(px, why, c.closeTime); exited = true
            } else {
                val hi = max(a.highest, c.h)
                var st = a.stop
                if (hi >= a.entryPrice + a.risk) st = max(st, a.entryPrice * (1 + 2 * FEE + 2 * SLIP)) // stop no empate
                if (hi >= a.entryPrice + 1.5 * a.risk) st = max(st, hi - p.trailAtr * d.atr[i])        // trailing
                a = a.copy(highest = hi, stop = st, barsHeld = a.barsHeld + 1)
            }
        }
        if (a.btc == 0.0 && !exited) tryEnter(c, d, i)
        a = a.copy(lastCandle = c.t)
        equity += a.equity(c.c)
    }

    private fun tryEnter(c: Candle, d: Ind, i: Int) {
        if (a.dayTrades >= p.maxTrades) return
        if (a.dayRealized <= -p.dayLossPct * a.dayStartEquity) return
        if (c.t < a.cooldownUntil) return
        val s = Strategy.evaluate(d, i, p)
        if (!s.trendOk || s.score < p.minScore) return
        val dist = p.stopAtr * d.atr[i]
        if (dist <= 0 || p.rr * dist / c.c < 0.006) return // alvo nao paga as taxas com folga
        val px = c.c * (1 + SLIP)
        val e = a.equity(c.c)
        val notional = minOf(e * p.riskPct / dist * px, e * p.maxPos, a.cash / (1 + FEE))
        if (notional < 10.0) return // minimo de ordem da Binance
        val fee = notional * FEE
        a = a.copy(
            cash = a.cash - notional - fee, btc = notional / px, entryPrice = px, entryTime = c.closeTime,
            entryFee = fee, stop = px - dist, target = px + p.rr * dist, highest = px, risk = dist, barsHeld = 0,
            entryReason = "Score ${s.score}: " + s.reasons.joinToString("; "), dayTrades = a.dayTrades + 1
        )
    }

    fun sell(rawPx: Double, why: String, time: Long) {
        if (a.btc <= 0) return
        val px = rawPx * (1 - SLIP)
        val gross = a.btc * px
        val fee = gross * FEE
        val cost = a.btc * a.entryPrice + a.entryFee
        val pnl = gross - fee - cost
        trades += Trade(
            entryTime = a.entryTime, exitTime = time, entryPrice = a.entryPrice, exitPrice = px, qty = a.btc,
            pnl = pnl, pnlPct = pnl / cost * 100, fees = fee + a.entryFee, reason = a.entryReason,
            exitReason = why, profile = a.profile, dayKey = a.dayKey
        )
        a = a.copy(
            cash = a.cash + gross - fee, btc = 0.0, entryPrice = 0.0, entryFee = 0.0, stop = 0.0, target = 0.0,
            highest = 0.0, risk = 0.0, barsHeld = 0, entryReason = "",
            dayRealized = a.dayRealized + pnl, dayGains = a.dayGains + max(pnl, 0.0),
            dayLosses = a.dayLosses + min(pnl, 0.0), dayClosed = a.dayClosed + 1,
            dayWins = a.dayWins + if (pnl > 0) 1 else 0, dayFees = a.dayFees + fee + a.entryFee,
            cooldownUntil = if (pnl < 0) time + p.cooldown * BAR else a.cooldownUntil
        )
    }
}

// =====================================================================================
//  BACKTEST OFFLINE (roda a estrategia nos ultimos 90 dias)
// =====================================================================================
data class BtResult(
    val profile: Int, val trades: List<Trade>, val equity: List<Double>, val reports: List<DailyReport>,
    val endEquity: Double, val pnl: Double, val pnlPct: Double, val winRate: Double, val maxDD: Double,
    val profitFactor: Double, val avgWin: Double, val avgLoss: Double, val buyHold: Double,
    val posDays: Int, val days: Int, val bestDay: Double, val worstDay: Double, val fees: Double
)

fun backtest(cs: List<Candle>, profile: Int, ind: Ind = Ind(cs)): BtResult {
    val e = Engine(Account(profile = profile, auto = true))
    e.run(cs, ind)
    val last = cs.last().c
    if (e.a.btc > 0) e.sell(last, "Fim do backtest", cs.last().closeTime)
    val t = e.trades
    val wins = t.filter { it.pnl > 0 }
    val losses = t.filter { it.pnl <= 0 }
    var peak = CAPITAL
    var dd = 0.0
    for (v in e.equity) { peak = max(peak, v); dd = max(dd, (peak - v) / peak) }
    val end = e.a.equity(last)
    val gl = abs(losses.sumOf { it.pnl })
    return BtResult(
        profile, t, e.equity, e.reports, end, end - CAPITAL, (end - CAPITAL) / CAPITAL * 100,
        if (t.isEmpty()) 0.0 else wins.size * 100.0 / t.size, dd * 100,
        if (gl == 0.0) (if (wins.isEmpty()) 0.0 else 99.0) else wins.sumOf { it.pnl } / gl,
        if (wins.isEmpty()) 0.0 else wins.sumOf { it.pnl } / wins.size,
        if (losses.isEmpty()) 0.0 else losses.sumOf { it.pnl } / losses.size,
        (last / cs[WARMUP].c - 1) * 100,
        e.reports.count { it.pnl > 0 }, e.reports.size,
        e.reports.maxOfOrNull { it.pnl } ?: 0.0, e.reports.minOfOrNull { it.pnl } ?: 0.0,
        t.sumOf { it.fees }
    )
}

// =====================================================================================
//  REPOSITORIO: ciclo do robo ao vivo + acoes do usuario
// =====================================================================================
object Repo {
    private val lock = Mutex()

    private suspend fun <T> locked(ctx: Context, block: suspend (TradeDao, Account) -> T): T =
        lock.withLock {
            withContext(Dispatchers.IO) {
                val dao = Db.get(ctx).dao()
                block(dao, dao.account() ?: Account())
            }
        }.also { runCatching { SpeedWidget().updateAll(ctx) } }

    /** Busca precos, roda o robo nos candles novos, salva tudo, envia relatorio pendente e atualiza o widget. */
    suspend fun tick(ctx: Context) = locked(ctx) { dao, start ->
        var a = start
        try {
            val t = Binance.ticker()
            a = a.copy(lastPrice = t.last, change24h = t.change, high24h = t.high, low24h = t.low)
            val now = System.currentTimeMillis()
            val cs = Binance.klines("15m", 1000).filter { it.closeTime <= now }
            if (a.auto && cs.size > WARMUP + 2) {
                if (a.lastCandle < cs[WARMUP].t) a = a.copy(lastCandle = cs[cs.size - 2].t)
                val e = Engine(a)
                e.run(cs)
                a = e.a
                if (e.trades.isNotEmpty()) dao.insertTrades(e.trades)
                if (e.reports.isNotEmpty()) dao.saveReports(e.reports)
            }
            a = a.copy(
                spark = cs.takeLast(96).joinToString(",") { String.format(Locale.US, "%.2f", it.c) },
                lastUpdate = now, lastError = ""
            )
        } catch (e: Exception) {
            a = a.copy(lastError = e.message ?: "Erro de rede")
        }
        dao.save(a)
        for (r in dao.pendingReports()) {
            Notifier.report(ctx, r)
            dao.markNotified(r.dayKey)
        }
    }

    suspend fun setAuto(ctx: Context, on: Boolean) {
        locked(ctx) { dao, a -> dao.save(a.copy(auto = on, lastCandle = if (on) 0 else a.lastCandle)) }
        tick(ctx)
    }

    suspend fun setProfile(ctx: Context, p: Int) = locked(ctx) { dao, a -> dao.save(a.copy(profile = p)) }
    suspend fun setResetDaily(ctx: Context, v: Boolean) = locked(ctx) { dao, a -> dao.save(a.copy(resetDaily = v)) }

    suspend fun closeNow(ctx: Context) = locked(ctx) { dao, a ->
        if (a.btc > 0 && a.lastPrice > 0) {
            val e = Engine(a)
            e.sell(a.lastPrice, "Fechamento manual", System.currentTimeMillis())
            dao.insertTrades(e.trades)
            dao.save(e.a)
        }
    }

    suspend fun resetAll(ctx: Context) = locked(ctx) { dao, a ->
        dao.clearTrades()
        dao.clearReports()
        dao.save(Account(profile = a.profile, resetDaily = a.resetDaily, lastPrice = a.lastPrice,
            change24h = a.change24h, high24h = a.high24h, low24h = a.low24h, spark = a.spark))
    }
}

// =====================================================================================
//  AGENDAMENTO (a cada 15 min + alarme das 21h) e NOTIFICACAO LOCAL
// =====================================================================================
class TickWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Repo.tick(applicationContext)
        return Result.success()
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Sched.setup(ctx)
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<TickWorker>().setConstraints(net).build())
    }
}

object Sched {
    fun setup(ctx: Context) {
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val req = PeriodicWorkRequestBuilder<TickWorker>(15, TimeUnit.MINUTES).setConstraints(net).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("tick", ExistingPeriodicWorkPolicy.KEEP, req)

        val am = ctx.getSystemService(AlarmManager::class.java)
        var t = ZonedDateTime.now().withHour(REPORT_HOUR).withMinute(1).withSecond(0).withNano(0)
        if (!t.isAfter(ZonedDateTime.now())) t = t.plusDays(1)
        val pi = PendingIntent.getBroadcast(
            ctx, 21, Intent(ctx, AlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val ms = t.toInstant().toEpochMilli()
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        }
    }
}

object Notifier {
    fun report(ctx: Context, r: DailyReport) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rel", "Relatório diário 21h", NotificationManager.IMPORTANCE_HIGH))
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val pi = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).putExtra("tab", 1),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = "Relatório ${fmtDay(r.dayKey)}: ${sgn(r.pnl)} (${pct(r.pnlPct)})"
        val body = "Operações: ${r.trades} (${r.wins} ganhos, ${r.trades - r.wins} perdas)\n" +
            "Ganhos: ${sgn(r.gains)}   Perdas: ${sgn(r.losses)}   Taxas: ${usd(r.fees)}\n" +
            "Saldo do dia: ${sgn(r.pnl)}  •  Patrimônio: ${usd(r.endEquity)}\n" +
            "RESULTADO FINAL ACUMULADO: ${sgn(r.cumPnl)}" + if (r.openPosition) "\n(posição ainda aberta)" else ""
        val n = NotificationCompat.Builder(ctx, "rel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText("Resultado acumulado: ${sgn(r.cumPnl)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(r.dayKey.hashCode(), n)
    }
}
