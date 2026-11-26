# ⚡ Speed Trader – Simulador de Robô Day/Swing Trade BTC/USDT (Android)

Um app Android que **simula** um robô automático de trade de **BTC/USDT**, começando com **US$ 100**, **sem alavancagem**, usando preços reais da Binance (API pública, **sem login e sem chave**).

> ⚠️ É um **simulador**. Nenhuma ordem real é enviada. Resultados passados não garantem resultados futuros.

---

## 📱 O que o app tem

| Recurso | Descrição |
|---|---|
| **Tela Trade** (estilo Binance/TradingView) | Gráfico de candles com zoom/arraste (15m, 1h, 4h, 1d), EMA21/EMA50, volume, linhas de entrada/stop/alvo, painel da conta, posição aberta e histórico |
| **Robô automático** | Roda a cada 15 min, mesmo com o app fechado. Se ficar sem internet, recupera os candles perdidos ao voltar |
| **Perfil do trader** | **Conservador**, **Arrojado** ou **Arriscado** – muda confirmações exigidas, risco, tamanho da posição e limites diários |
| **Relatório diário às 21h** | Notificação com ganhos, perdas, saldo do dia e resultado final acumulado |
| **Tela Relatórios** | Histórico de todos os dias (toque no dia para ver as operações) |
| **Backtester offline** | Roda o robô nos últimos 90 dias de candles reais: taxa de acerto, P&L esperado, drawdown máximo, fator de lucro, comparação dos 3 perfis |
| **Widget 5x5** | Preço, variação 24h, mini-gráfico, patrimônio, P&L do dia/total, posição e últimas 5 operações. Botão ⟳ atualiza |
| **Banco Room** | Tabelas `Account`, `Trade` e `DailyReport` salvas no celular |

---

## 🧠 Estratégia (extraída dos manuais clássicos)

O robô só **compra** (mercado à vista não permite vender a descoberto sem margem) e usa **pontuação por confluência**: cada regra abaixo vale 1 ponto.

| Regra | Origem |
|---|---|
| Filtro de tendência (EMA 50/200) – só opera a favor da "maré" | Alexander Elder – *Trading for a Living* (Tela Tripla) |
| Pullback no RSI (cai abaixo de 42 e volta a subir) | J. Welles Wilder – *New Concepts in Technical Trading*; Constance Brown |
| MACD com histograma positivo e crescente | Gerald Appel; Sistema Impulso de Elder |
| Cruzamento EMA 9 > EMA 21 | John Murphy – *Análise Técnica do Mercado Financeiro* |
| Pullback na EMA 21 ("Holy Grail") | Linda Raschke – *Street Smarts* |
| Repique na banda de Bollinger | John Bollinger – *Bollinger on Bollinger Bands* |
| Rompimento de 20 candles com volume | Regras das Tartarugas (Richard Dennis / Curtis Faith) |
| Candle de força com volume | Al Brooks; Wyckoff |

**Gestão de risco** (Van K. Tharp, Elder – regra dos 2%/6%, Mark Douglas):
stop por ATR, alvo em múltiplos de R, stop no empate após +1R, trailing stop após +1,5R, tamanho da posição pelo risco, limite de perda diária, limite de trades por dia, pausa após perda e filtro de custo (só entra se o alvo paga as taxas de 0,1% por lado).

| Perfil | Confirmações | Risco/trade | Posição máx. | Stop | Alvo | Trades/dia | Para o dia com |
|---|---|---|---|---|---|---|---|
| Conservador | 4 | 1% | 50% | 1,5 ATR | 2R | 3 | -2% |
| Arrojado | 3 | 2% | 80% | 2 ATR | 2,5R | 6 | -4% |
| Arriscado | 2 | 3,5% | 100% | 2,5 ATR | 3R | 12 | -8% |

O **dia de trade** vai das 21h de um dia até as 21h do dia seguinte. Nos **Ajustes** você escolhe se o caixa **volta a US$ 100 todo dia** ou se o saldo **continua** de um dia para o outro (padrão).

---

## 🛠️ Como gerar o APK no GitHub (passo a passo para iniciante)

O GitHub compila o APK sozinho para você (GitHub Actions). Você não precisa instalar nada no computador.

### Se os arquivos já estão no seu repositório
1. Abra o repositório no GitHub.
2. Clique na aba **Actions** → veja o processo **"Gerar APK"** rodando (bolinha amarela). Leva ~5 minutos.
3. Quando ficar ✅ verde, vá na página inicial do repositório → à direita clique em **Releases** → abra a versão mais nova → baixe **SpeedTrader.apk**.
   *(Dica: faça isso pelo navegador do celular e o download já vai direto para ele.)*
4. Para rodar de novo manualmente: **Actions** → **Gerar APK** → **Run workflow**.

### Se for criar um repositório novo do zero
1. No GitHub clique em **New repository** → dê um nome → **Create repository**.
2. Clique em **uploading an existing file** e arraste **todos os arquivos e pastas** deste projeto (incluindo a pasta `.github` – no computador ative "mostrar arquivos ocultos" para vê-la).
3. Clique em **Commit changes**. O APK começa a ser gerado automaticamente (siga o passo 2 acima).
4. Se aparecer erro de permissão na etapa *Publicar APK*: **Settings** → **Actions** → **General** → **Workflow permissions** → marque **Read and write permissions** → **Save** e rode de novo.

### Instalar no celular
1. Abra o arquivo **SpeedTrader.apk** baixado.
2. O Android vai pedir para **permitir instalar apps desta fonte** → permita.
3. Abra o app e **permita as notificações** (para receber o relatório das 21h).
4. Recomendado: **Configurações → Apps → Speed Trader → Bateria → Sem restrições** (para o robô rodar com o app fechado).
5. Opcional: **Configurações → Apps → Speed Trader → Alarmes e lembretes → Permitir** (relatório exatamente às 21h).

### Adicionar o widget 5x5
Pressione e segure a tela inicial → **Widgets** → **Speed Trader BTC** → arraste para a tela. Se precisar, redimensione para 5x5.

### Atualizar o app
Cada versão nova do APK é assinada com uma chave diferente gerada pelo GitHub. Se o Android recusar a atualização, **desinstale a versão antiga** e instale a nova (o histórico simulado é apagado).

---

## 🚀 Uso recomendado
1. Vá em **Backtest** → **Comparar 3 perfis** e veja qual teve melhor resultado e menor drawdown nos últimos 90 dias.
2. Em **Ajustes**, escolha o perfil.
3. Na tela **Trade**, toque em **LIGAR ROBÔ AUTOMÁTICO**.
4. Às 21h chega a notificação com o relatório do dia. Todo o histórico fica em **Relatórios**.

---

## 📁 Arquivos do projeto (só 11)

```
.github/workflows/build.yml        → receita que o GitHub usa para gerar o APK
settings.gradle.kts                → configuração do projeto
build.gradle.kts                   → bibliotecas e versão do Android
gradle.properties                  → opções do compilador
src/main/AndroidManifest.xml       → permissões, tela, widget e alarmes
src/main/res/xml/widget_info.xml   → tamanho do widget (5x5)
src/main/res/drawable/ic_logo.xml  → ícone do app
src/main/java/com/speedtrader/Engine.kt → robô, estratégia, indicadores, banco, backtest, relatório 21h
src/main/java/com/speedtrader/Ui.kt     → todas as telas (Trade, Relatórios, Backtest, Ajustes)
src/main/java/com/speedtrader/Widget.kt → widget 5x5
README.md                          → este guia
```

## ❓ Por que não Firebase e não Flutter?
- **Firebase** exige criar conta/projeto no Google e um servidor para enviar push. Como você pediu **nada de login**, o relatório das 21h é uma **notificação local** gerada pelo próprio celular – mesmo resultado, sem conta nenhuma.
- **Flutter** duplicaria todo o código (e mais arquivos). Como o alvo é Android, o app é nativo em Kotlin + Jetpack Compose, que é mais leve e tem o widget nativo.
