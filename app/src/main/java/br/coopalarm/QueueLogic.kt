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
            if (o.car != cfg.car || o.advance != cfg.advance) armedRange = true
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

        // 2) Reset de ciclo: numero menor que o ultimo valido
        val lv = lastValid
        if (lv != null && cur < lv) armedRange = true

        // 3) Faixa do carro
        var ev: Event? = null
        if (armedRange && cur >= cfg.car - cfg.advance && cur <= cfg.car) {
            armedRange = false
            ev = Event.RANGE
        }

        lastValid = cur
        return ev
    }
}
