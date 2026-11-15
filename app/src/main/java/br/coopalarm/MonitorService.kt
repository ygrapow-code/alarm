package br.coopalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class MonitorService : Service() {

    companion object {
        const val A_STOP_ALARM = "br.coopalarm.STOP_ALARM"
        const val A_APPLY = "br.coopalarm.APPLY"
        const val A_STOP_SERVICE = "br.coopalarm.STOP_SERVICE"
        const val A_TEST = "br.coopalarm.TEST"
        const val CH_MON = "monitor"
        const val CH_ALARM = "alarme"
        const val ID_MON = 1
        const val ID_ALARM = 2

        @Volatile
        var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val wake = Semaphore(0)
    private var worker: Thread? = null
    private var player: MediaPlayer? = null
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        startFg("CoopAlarm iniciando...")

        when (intent?.action) {
            A_STOP_ALARM -> {
                stopAlarm()
                if (!running) stopSelf()
            }
            A_APPLY -> wake.release()
            A_TEST -> startAlarm("Teste de alarme", false)
            A_STOP_SERVICE -> {
                running = false
                wake.release()
                stopAlarm()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> startWorker()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        wake.release()
        stopAlarm()
        wl?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---------- Monitoramento ----------

    private fun startWorker() {
        if (running && worker?.isAlive == true) return
        running = true
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "coopalarm:monitor").apply {
            if (!isHeld) acquire()
        }
        worker = Thread { loop() }.also { it.start() }
    }

    private fun loop() {
        val logic = QueueLogic()
        var lastOk = SystemClock.elapsedRealtime()
        var offlineWarned = false
        val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        while (running) {
            val cfg = Config.load(this) // lida a cada ciclo: mudancas valem na proxima requisicao
            logic.onConfig(cfg)
            val alvo = rangeLabel(cfg)
            try {
                val cur = fetch(cfg)
                lastOk = SystemClock.elapsedRealtime()
                offlineWarned = false
                Config.setStatus(this, "%03d".format(cur), clock.format(Date()), "OK")
                updateFg("Atual: %03d | Alvo: %s".format(cur, alvo))
                when (logic.evaluate(cur, cfg)) {
                    Event.FREE -> startAlarm("Fila livre (%03d): qualquer carro pode atender".format(cfg.general), true)
                    Event.RANGE -> startAlarm("Hora de ir! Atual %03d, seu carro %d".format(cur, cfg.car), true)
                    null -> {}
                }
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                Config.setStatus(this, Config.status(this).first, Config.status(this).second, "Falha: $msg")
                updateFg("Sem leitura (%s)".format(msg))
                val semLeitura = SystemClock.elapsedRealtime() - lastOk
                if (!offlineWarned && semLeitura > cfg.offlineMin * 60_000L) {
                    offlineWarned = true
                    startAlarm("Sem conexao com a fila ha ${cfg.offlineMin} min", false)
                }
            }
            val espera = Config.load(this).interval * 1000L
            try {
                wake.tryAcquire(espera, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun fetch(cfg: Cfg): Int {
        val c = URL(cfg.url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.requestMethod = "GET"
            c.setRequestProperty("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 13)")
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            val body = c.inputStream.bufferedReader().readText().trimStart('\uFEFF', ' ', '\n', '\r')
            return parse(body)
        } finally {
            c.disconnect()
        }
    }

    /** Primeiro item cujo "senha" e numerico (ignora "FOLGA ..."). */
    private fun parse(body: String): Int {
        val arr = JSONArray(body)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val s = o.optString("senha").trim()
            if (s.isNotEmpty() && s.all { it.isDigit() }) {
                if (o.optString("erro", "000") != "000") throw RuntimeException("erro ${o.optString("erro")}")
                return s.toInt()
            }
        }
        throw RuntimeException("sem numero na resposta")
    }

    // ---------- Alarme ----------

    private fun startAlarm(msg: String, loop: Boolean) {
        main.post {
            stopAlarmNow()
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(this@MonitorService, uri)
                    isLooping = loop
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                // sem som, ainda ha vibracao e notificacao
            }
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), if (loop) 0 else -1))

            val stop = PendingIntent.getService(
                this, 1,
                Intent(this, MonitorService::class.java).setAction(A_STOP_ALARM),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val open = PendingIntent.getActivity(
                this, 2, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(this, CH_ALARM)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("CoopAlarm")
                .setContentText(msg)
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "PARAR", stop).build())
                .build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID_ALARM, n)
        }
    }

    private fun stopAlarm() = main.post { stopAlarmNow() }

    private fun stopAlarmNow() {
        try {
            player?.let { if (it.isPlaying) it.stop(); it.release() }
        } catch (e: Exception) {
        }
        player = null
        (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_ALARM)
    }

    // ---------- Notificacao fixa ----------

    private fun createChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_MON, "Monitoramento", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Alarme", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
            }
        )
    }

    private fun fgNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH_MON)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("CoopAlarm monitorando")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun startFg(text: String) {
        val n = fgNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_MON, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID_MON, n)
        }
    }

    private fun updateFg(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID_MON, fgNotification(text))
    }
}
