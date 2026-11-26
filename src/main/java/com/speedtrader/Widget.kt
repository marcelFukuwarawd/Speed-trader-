package com.speedtrader

// =====================================================================================
//  WIDGET 5x5 (Jetpack Glance) - estilo Binance
// =====================================================================================

import android.content.Context
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

private val WBG = Color(0xFF0B0E11)
private val WCARD = Color(0xFF1E2329)
private val WYEL = Color(0xFFF0B90B)
private val WGRN = Color(0xFF0ECB81)
private val WRED = Color(0xFFF6465D)
private val WTXT = Color(0xFFEAECEF)
private val WGRY = Color(0xFF848E9C)

private fun st(c: Color, size: Int, bold: Boolean = false) =
    TextStyle(color = ColorProvider(c), fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

class SpeedWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SpeedWidget()
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Repo.tick(context)
    }
}

class SpeedWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = Db.get(context).dao()
        val a = dao.account() ?: Account()
        val trades = dao.trades(5)
        val spark = sparkBitmap(a.spark)
        provideContent { WidgetContent(a, trades, spark) }
    }
}

@Composable
private fun WidgetContent(a: Account, trades: List<Trade>, spark: Bitmap) {
    val up = a.change24h >= 0
    Column(
        modifier = GlanceModifier.fillMaxSize().background(WBG).cornerRadius(16.dp).padding(12.dp)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        // cabecalho
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("₿ BTC/USDT", style = st(WYEL, 15, true))
            Spacer(GlanceModifier.defaultWeight())
            Text(
                if (a.auto) " ● AUTO ${PROFILES[a.profile].name.uppercase()} " else " ○ ROBÔ DESLIGADO ",
                style = st(if (a.auto) WGRN else WGRY, 11, true),
                modifier = GlanceModifier.background(WCARD).cornerRadius(8.dp).padding(4.dp)
            )
            Text("   ⟳", style = st(WTXT, 18, true), modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()))
        }
        // preco
        Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (a.lastPrice > 0) price(a.lastPrice) else "--", style = st(if (up) WGRN else WRED, 26, true))
            Spacer(GlanceModifier.defaultWeight())
            Text(pct(a.change24h), style = st(if (up) WGRN else WRED, 15, true))
        }
        Text("Máx 24h ${price(a.high24h)}  •  Mín 24h ${price(a.low24h)}", style = st(WGRY, 10))
        // grafico 24h
        Image(
            provider = ImageProvider(spark), contentDescription = "Gráfico 24h",
            modifier = GlanceModifier.fillMaxWidth().height(80.dp).padding(vertical = 4.dp)
        )
        // painel de conta
        Row(modifier = GlanceModifier.fillMaxWidth().background(WCARD).cornerRadius(10.dp).padding(8.dp)) {
            Stat("Patrimônio", usd(a.equity()), WTXT, GlanceModifier.defaultWeight())
            Stat("Hoje", sgn(a.dayPnl()), if (a.dayPnl() >= 0) WGRN else WRED, GlanceModifier.defaultWeight())
            Stat("Total", sgn(a.totalPnl()), if (a.totalPnl() >= 0) WGRN else WRED, GlanceModifier.defaultWeight())
        }
        // posicao
        val pos = if (a.btc > 0) {
            val u = a.btc * (a.lastPrice - a.entryPrice)
            "● COMPRADO ${btc(a.btc)} BTC @ ${price(a.entryPrice)}  ${sgn(u)}\nStop ${price(a.stop)}  •  Alvo ${price(a.target)}"
        } else "Sem posição — aguardando sinal  •  Caixa ${usd(a.cash)}"
        Text(pos, style = st(if (a.btc > 0) WYEL else WGRY, 11, a.btc > 0), modifier = GlanceModifier.padding(vertical = 6.dp))
        Text("Últimas operações", style = st(WGRY, 11, true))
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            if (trades.isEmpty()) Text("Nenhuma operação ainda", style = st(WGRY, 11))
            trades.forEach { t ->
                Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text(hm(t.exitTime), style = st(WGRY, 11))
                    Text("  ${price(t.entryPrice)} → ${price(t.exitPrice)}", style = st(WTXT, 11))
                    Spacer(GlanceModifier.defaultWeight())
                    Text("${sgn(t.pnl)} (${pct(t.pnlPct)})", style = st(if (t.pnl >= 0) WGRN else WRED, 11, true))
                }
            }
        }
        Text(
            if (a.lastError.isNotEmpty()) "⚠ ${a.lastError}" else "Atualizado ${if (a.lastUpdate > 0) hm(a.lastUpdate, "HH:mm") else "--"}  •  Relatório às 21h",
            style = st(WGRY, 10)
        )
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: GlanceModifier) {
    Column(modifier = modifier) {
        Text(label, style = st(WGRY, 10))
        Text(value, style = st(color, 13, true))
    }
}

/** Desenha o mini-grafico (sparkline) das ultimas 24h como imagem. */
fun sparkBitmap(csv: String): Bitmap {
    val w = 700
    val h = 180
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val v = csv.split(",").mapNotNull { it.toDoubleOrNull() }
    if (v.size < 2) return bmp
    val cv = android.graphics.Canvas(bmp)
    val lo = v.min()
    val hi = v.max()
    val rng = if (hi - lo == 0.0) 1.0 else hi - lo
    val upColor = if (v.last() >= v.first()) 0xFF0ECB81.toInt() else 0xFFF6465D.toInt()
    val path = Path()
    v.forEachIndexed { i, p ->
        val x = i * w / (v.size - 1f)
        val y = (h - 8 - (p - lo) / rng * (h - 16)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    val fill = Path(path).apply { lineTo(w.toFloat(), h.toFloat()); lineTo(0f, h.toFloat()); close() }
    cv.drawPath(fill, Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, h.toFloat(), (upColor and 0x00FFFFFF) or 0x55000000, 0x00000000, Shader.TileMode.CLAMP)
    })
    cv.drawPath(path, Paint().apply { color = upColor; strokeWidth = 4f; style = Paint.Style.STROKE; isAntiAlias = true })
    return bmp
}
