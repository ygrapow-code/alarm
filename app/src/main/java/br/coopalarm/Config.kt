package br.coopalarm

import android.content.Context

data class Cfg(
    val url: String,
    val interval: Int,     // segundos entre consultas
    val car: Int,          // codigo do carro
    val maxCode: Int,      // maior codigo da fila; depois dele a numeracao reinicia
    val general: Int,      // codigo geral (qualquer carro atende)
    val advance: Int,      // antecedencia do alarme
    val offlineMin: Int    // minutos sem leitura valida para avisar
)

object Config {
    private fun p(c: Context) = c.getSharedPreferences("coopalarm", Context.MODE_PRIVATE)

    fun load(c: Context): Cfg {
        val s = p(c)
        return Cfg(
            s.getString("url", "") ?: "",
            s.getInt("interval", 60),
            s.getInt("car", 350),
            s.getInt("maxCode", 999),
            s.getInt("general", 0),
            s.getInt("advance", 100),
            s.getInt("offlineMin", 5)
        )
    }

    fun save(c: Context, g: Cfg) {
        p(c).edit()
            .putString("url", g.url)
            .putInt("interval", g.interval)
            .putInt("car", g.car)
            .putInt("maxCode", g.maxCode)
            .putInt("general", g.general)
            .putInt("advance", g.advance)
            .putInt("offlineMin", g.offlineMin)
            .apply()
    }

    // Estado exibido na tela
    fun setStatus(c: Context, cur: String, time: String, msg: String) {
        p(c).edit().putString("st_cur", cur).putString("st_time", time).putString("st_msg", msg).apply()
    }

    fun status(c: Context): Triple<String, String, String> {
        val s = p(c)
        return Triple(s.getString("st_cur", "-") ?: "-", s.getString("st_time", "-") ?: "-", s.getString("st_msg", "") ?: "")
    }
}
