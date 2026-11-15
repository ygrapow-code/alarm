package br.coopalarm

enum class Event { FREE, RANGE }

/** Logica pura de decisao do alarme. */
class QueueLogic {
    private var lastValid: Int? = null   // ultimo numero valido diferente do codigo geral
    private var armedRange = true
    private var armedFree = true
    private var prev: Cfg? = null

    /** Chamado a cada ciclo com a configuracao atual (lida ao vivo). */
    fun onConfig(cfg: Cfg) {
        val o = prev
        if (o != null) {
            if (o.url != cfg.url) lastValid = null
            if (o.car != cfg.car || o.advance != cfg.advance || o.maxCode != cfg.maxCode) armedRange = true
            if (o.general != cfg.general) armedFree = true
        }
        prev = cfg
    }

    fun evaluate(cur: Int, cfg: Cfg): Event? {
        // 1) Codigo geral: toca uma vez; nao conta como reset de ciclo
        if (cur == cfg.general) {
            if (armedFree) {
                armedFree = false
                return Event.FREE
            }
            return null
        }
        armedFree = true

        // 2) Reset de ciclo: numero menor que o ultimo valido.
        // Cruzar o maximo dentro da mesma aproximacao (ex.: 790 -> 5, carro 10) nao e reset.
        val lv = lastValid
        if (lv != null && cur < lv && !sameApproach(lv, cur, cfg)) armedRange = true

        // 3) Faixa do carro, com volta pelo numero maximo quando a antecedencia passa do carro
        var ev: Event? = null
        if (armedRange && inRange(cur, cfg)) {
            armedRange = false
            ev = Event.RANGE
        }

        lastValid = cur
        return ev
    }
}

/**
 * Faixa [carro - antecedencia, carro]. Se o inicio fica negativo, a faixa
 * continua do outro lado do ciclo: de (maximo + carro - antecedencia) ate o maximo,
 * e de 0 ate o carro. Carro 10, maximo 800 e antecedencia 50 => 760 a 800 e 0 a 10.
 */
fun rangeStart(cfg: Cfg): Int = cfg.car - cfg.advance

fun inRange(cur: Int, cfg: Cfg): Boolean {
    if (cur < 0 || cur > cfg.maxCode) return false
    val start = rangeStart(cfg)
    if (start >= 0) return cur >= start && cur <= cfg.car
    val high = cfg.maxCode + start
    return (cur >= high && cur <= cfg.maxCode) || cur <= cfg.car
}

/** Queda de numero que ainda e a mesma chegada ao carro, depois do reinicio. */
fun sameApproach(previous: Int, cur: Int, cfg: Cfg): Boolean {
    val start = rangeStart(cfg)
    if (start >= 0) return false
    val high = cfg.maxCode + start
    return previous >= high && previous <= cfg.maxCode && cur <= cfg.car
}

fun rangeLabel(cfg: Cfg): String {
    val start = rangeStart(cfg)
    return if (start >= 0) {
        "$start a ${cfg.car}"
    } else {
        "${cfg.maxCode + start} a ${cfg.maxCode} e 0 a ${cfg.car}"
    }
}
