package br.coopalarm

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val h = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var btnToggle: Button
    private lateinit var fUrl: EditText
    private lateinit var fInterval: EditText
    private lateinit var fCar: EditText
    private lateinit var fMax: EditText
    private lateinit var fGeneral: EditText
    private lateinit var fAdvance: EditText
    private lateinit var fOffline: EditText

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            h.postDelayed(this, 1000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 18f }
        root.addView(status)

        fun field(label: String, type: Int): EditText {
            root.addView(TextView(this).apply { text = label; setPadding(0, pad / 2, 0, 0) })
            val e = EditText(this).apply { inputType = type }
            root.addView(e, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return e
        }

        val num = InputType.TYPE_CLASS_NUMBER
        fUrl = field("URL de captura", InputType.TYPE_TEXT_VARIATION_URI)
        fInterval = field("Intervalo de consulta (segundos, minimo 15)", num)
        fCar = field("Codigo do carro", num)
        fMax = field("Numero maximo do codigo (depois dele a fila reinicia)", num)
        fGeneral = field("Codigo geral (qualquer carro atende)", num)
        fAdvance = field("Antecedencia do alarme (pode ser maior que o carro; a faixa volta pelo numero maximo)", num)
        fOffline = field("Avisar se ficar sem leitura por (minutos)", num)

        val c = Config.load(this)
        fUrl.setText(c.url)
        fInterval.setText(c.interval.toString())
        fCar.setText(c.car.toString())
        fMax.setText(c.maxCode.toString())
        fGeneral.setText("%03d".format(c.general))
        fAdvance.setText(c.advance.toString())
        fOffline.setText(c.offlineMin.toString())

        fun button(text: String, onClick: () -> Unit): Button {
            val bt = Button(this).apply { this.text = text; setOnClickListener { onClick() } }
            root.addView(bt)
            return bt
        }

        button("Salvar (vale na proxima requisicao)") {
            if (save()) {
                Toast.makeText(this, "Salvo", Toast.LENGTH_SHORT).show()
                if (MonitorService.running) sendToService(MonitorService.A_APPLY)
            }
        }
        btnToggle = button("Iniciar") {
            if (MonitorService.running) {
                sendToService(MonitorService.A_STOP_SERVICE)
            } else if (save()) {
                startForegroundService(Intent(this, MonitorService::class.java))
            }
        }
        button("Testar alarme") { startForegroundService(Intent(this, MonitorService::class.java).setAction(MonitorService.A_TEST)) }
        button("Parar alarme") { sendToService(MonitorService.A_STOP_ALARM) }
        button("Isentar da otimizacao de bateria") {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        h.post(tick)
    }

    override fun onPause() {
        h.removeCallbacks(tick)
        super.onPause()
    }

    private fun sendToService(action: String) {
        startService(Intent(this, MonitorService::class.java).setAction(action))
    }

    private fun save(): Boolean {
        val url = fUrl.text.toString().trim()
        val interval = fInterval.text.toString().toIntOrNull()
        val car = fCar.text.toString().toIntOrNull()
        val maxCode = fMax.text.toString().toIntOrNull()
        val general = fGeneral.text.toString().toIntOrNull()
        val advance = fAdvance.text.toString().toIntOrNull()
        val offline = fOffline.text.toString().toIntOrNull()

        val erro = when {
            !url.startsWith("http") -> "URL deve comecar com http"
            interval == null || interval < 15 -> "Intervalo minimo: 15 s"
            car == null || car <= 0 -> "Codigo do carro invalido"
            maxCode == null || car == null || maxCode < car -> "Numero maximo deve ser maior ou igual ao codigo do carro"
            general == null || general < 0 -> "Codigo geral invalido"
            advance == null || maxCode == null || advance < 0 || advance >= maxCode -> "Antecedencia deve ser menor que o numero maximo"
            offline == null || offline < 1 -> "Minutos sem leitura invalido"
            else -> null
        }
        if (erro != null) {
            Toast.makeText(this, erro, Toast.LENGTH_LONG).show()
            return false
        }
        Config.save(this, Cfg(url, interval!!, car!!, maxCode!!, general!!, advance!!, offline!!))
        return true
    }

    private fun refresh() {
        val c = Config.load(this)
        val (cur, time, msg) = Config.status(this)
        val estado = if (MonitorService.running) "MONITORANDO" else "PARADO"
        status.text = "$estado\nAtual: $cur\nFaixa do alarme: ${rangeLabel(c)}\n" +
            "Codigo geral: %03d\nUltima leitura: %s\n%s".format(c.general, time, msg)
        btnToggle.text = if (MonitorService.running) "Parar monitoramento" else "Iniciar"
    }
}
