package com.livepoc.android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Meeting many-to-many sobre single-partition (paridade com o v3):
 * cada participante publica A/V (FLAG_AUD) na SUA partição (#0-#8) e subscreve
 * as dos outros; a #9 é o canal de presença — JSON {t:'who'|'hb'|'join'|'leave',
 * id, slot}. Entrada: who → os presentes respondem com hb → menor slot livre;
 * heartbeat 2s, expiry 6,5s; colisão de slot: o id maior (string) re-escolhe.
 *
 * Publisher = Broadcaster em modo single-partition no kind 'ms' (gerido pela
 * Activity). Cada peer remoto = um Viewer (kind 'pN', demux por registo) com o
 * seu AudioTrack — o AudioFlinger mistura os N áudios nativamente, e cada
 * Viewer mantém o seu relógio-mestre (sem mixer manual de PCM).
 *
 * Threads: toda a máquina de estados corre no main (onCtrl chega via listener
 * da ponte já no main; timers idem). onMedia chega em threads da ponte —
 * viewers é ConcurrentHashMap para essa leitura.
 */
class MeetingEngine(
    private val bridge: StreamrBridge,
    private val myId: String,
    /** A Activity cria o tile+Surface do slot e chama attachViewer quando pronto. */
    private val onPeerAdded: (slot: Int) -> Unit,
    private val onPeerRemoved: (slot: Int) -> Unit,
    private val onState: (String) -> Unit,
    /** Slot próprio escolhido (ou mudado por colisão) — arranca/atualiza o emissor. */
    private val onSlotChosen: (slot: Int) -> Unit,
    private val onStats: (String) -> Unit,
    /** Estado da câmara do peer (hb cam:false → placeholder no tile). */
    private val onPeerCam: (slot: Int, cam: Boolean) -> Unit = { _, _ -> },
    /** Dimensões DE RENDER do peer (rotação já aplicada) — aspect do tile. */
    private val onPeerVideoSize: (slot: Int, w: Int, h: Int) -> Unit = { _, _, _ -> },
    /** Enquadramento ESCOLHIDO PELO PEER (hb fill) — como ele quer ser visto. */
    private val onPeerFit: (slot: Int, fill: Boolean) -> Unit = { _, _ -> },
    /** Nº total de participantes (eu + roster) — para o bitrate adaptativo. */
    private val onParticipants: (n: Int) -> Unit = { _ -> },
) {
    companion object {
        const val SLOTS = 9              // #0-#8; #9 = controlo
        private const val HB_MS = 2000L
        private const val EXPIRE_MS = 6500L
        private const val TAG = "Meeting"
    }

    private data class Entry(val slot: Int, val at: Long, val cam: Boolean, val fill: Boolean)

    @Volatile var active = false; private set
    @Volatile var slot = -1; private set
    /** Câmara local ligada? — vai nos heartbeats (os peers põem placeholder). */
    @Volatile var camOn = true
        private set
    private val ui = Handler(Looper.getMainLooper())
    private val roster = HashMap<String, Entry>()  // id → (slot, lastSeen, cam)
    val viewers = ConcurrentHashMap<Int, Viewer>() // slot → viewer
    private val peerStats = HashMap<Int, String>()
    // diagnóstico da #9: tx = publishes de controlo enviados; rx = mensagens dos
    // OUTROS recebidas; echo = as minhas de volta (prova de caminho vivo).
    // rx=0 e echo=0 com tx a crescer = buraco negro no canal de presença.
    private var ctrlTx = 0; private var ctrlRx = 0; private var ctrlEcho = 0

    /** Enquadramento local (FIT/FILL) — vai nos heartbeats: é assim que os
     *  OUTROS renderizam o meu vídeo. */
    @Volatile var fillOn = false
        private set

    fun setCam(on: Boolean) {
        camOn = on
        if (active && slot >= 0) sendHb("hb") // propaga já o placeholder
    }

    fun setFill(on: Boolean) {
        fillOn = on
        if (active && slot >= 0) sendHb("hb") // propaga já o enquadramento
    }

    private val hbTick = object : Runnable {
        override fun run() {
            if (!active) return
            sendHb("hb")
            sweep()
            // espelho no logcat: medição automatizada (adb) sem UI
            Log.d(TAG, "state slot=#$slot cam=$camOn fill=$fillOn roster=${roster.size} " +
                "ctrl(tx=$ctrlTx rx=$ctrlRx echo=$ctrlEcho) " +
                "peers=[${viewers.keys.sorted().joinToString(",")}] " +
                "rosterSlots=[${roster.values.joinToString(",") { "#${it.slot}${if (!it.cam) "(camOff)" else ""}" }}]")
            ui.postDelayed(this, HB_MS)
        }
    }

    /** Entrada: subscrever #9, mandar who e esperar ~2,5s pelo roster (janela
     *  maior que o intervalo de hb — mesmo perdendo o who apanha-se um hb). */
    fun begin() {
        active = true
        bridge.meetCtrlStart()
        onState("discovering participants…")
        ui.postDelayed({ if (active) ctrlPub("who") }, 800) // deixa a #9 assentar
        ui.postDelayed({ chooseSlot() }, 3300)
    }

    // slots bloqueados por proxy-publish FANTASMA (sessão morta sem leave — em
    // proxy-only o slot fica "ocupado" nos operadores ~15-30s → "Cannot
    // broadcast"). Evitados na escolha; expiram sozinhos.
    private val blocked = HashMap<Int, Long>() // slot → quando bloqueou
    private fun freeSlot(vararg extra: Int): Int {
        val now = SystemClock.elapsedRealtime()
        blocked.entries.removeAll { now - it.value > 30_000 }
        val used = roster.values.map { it.slot }.toHashSet()
        used.addAll(extra.toList()); used.addAll(blocked.keys)
        return (0 until SLOTS).firstOrNull { it !in used } ?: -1
    }

    private fun chooseSlot() {
        if (!active) return
        slot = freeSlot()
        if (slot < 0) { onState("meeting full (9)"); stop(); return }
        bridge.meetStart(slot)
        sendHb("join")
        ui.postDelayed(hbTick, HB_MS)
        onSlotChosen(slot)
        onState("in meeting · slot #$slot")
        syncPeers()
    }

    /** Slot próprio bloqueado por publisher fantasma (ponte) → re-escolher. */
    fun onSlotBlocked() {
        if (!active || slot < 0) return
        Log.w(TAG, "slot #$slot bloqueado (cannot broadcast) → re-escolher")
        reslot(blockCurrent = true)
    }

    private fun ctrlPub(t: String) {
        val o = JSONObject().put("t", t).put("id", myId)
        if (slot >= 0) o.put("slot", slot).put("cam", camOn).put("fill", fillOn)
        ctrlTx++
        bridge.meetCtrlPub(o.toString())
    }

    private fun sendHb(t: String) { if (slot >= 0) ctrlPub(t) }

    /** Mensagem de controlo da #9 (main thread, via listener da ponte). */
    fun onCtrl(json: String) {
        if (!active) return
        val m = try { JSONObject(json) } catch (e: Exception) { return }
        val t = m.optString("t"); val id = m.optString("id")
        if (t.isEmpty() || id.isEmpty()) return
        if (id == myId) { ctrlEcho++; return }
        ctrlRx++
        when (t) {
            "who" -> ui.postDelayed({ if (active) sendHb("hb") }, (100..500).random().toLong())
            "leave" -> { roster.remove(id); syncPeers() }
            "hb", "join" -> {
                val s = m.optInt("slot", -1)
                if (s < 0 || s >= SLOTS) return
                roster[id] = Entry(s, SystemClock.elapsedRealtime(), m.optBoolean("cam", true), m.optBoolean("fill", false))
                if (s == slot && myId > id) resolveCollision() // o id maior perde
                syncPeers()
            }
        }
    }

    private fun resolveCollision() = reslot(blockCurrent = false)

    private fun reslot(blockCurrent: Boolean) {
        val old = slot
        if (blockCurrent) blocked[old] = SystemClock.elapsedRealtime()
        val ns = freeSlot(old)
        Log.w(TAG, (if (blockCurrent) "slot #$old bloqueado" else "colisão no slot #$old") + " → #$ns")
        if (ns < 0) { onState("meeting full"); stop(); return }
        slot = ns
        bridge.meetStart(ns) // remapeia 'ms' + proxies; o keyframe periódico (1s) ressincroniza
        sendHb("join")
        onSlotChosen(ns)
        onState("in meeting · slot #$slot")
        syncPeers()
    }

    private fun sweep() {
        val now = SystemClock.elapsedRealtime()
        roster.entries.removeAll { now - it.value.at > EXPIRE_MS }
        syncPeers()
    }

    private fun syncPeers() {
        if (!active || slot < 0) return
        val wanted = roster.values.map { it.slot }.filter { it != slot }.toHashSet()
        for (s in wanted) if (!viewers.containsKey(s)) onPeerAdded(s)
        for (s in viewers.keys.toList()) if (s !in wanted) {
            val v = viewers.remove(s)
            peerStats.remove(s)
            thread(name = "meet-peer-stop") { try { v?.stop() } catch (e: Exception) {} }
            onPeerRemoved(s)
        }
        for (e in roster.values) if (e.slot != slot) { onPeerCam(e.slot, e.cam); onPeerFit(e.slot, e.fill) }
        onParticipants(roster.size + 1) // eu + outros → bitrate adaptativo
        renderStats()
    }

    /** A Surface do tile do slot está pronta — cria o Viewer desse peer. */
    fun attachViewer(s: Int, surface: Surface) {
        if (!active || viewers.containsKey(s)) return
        if (roster.values.none { it.slot == s }) return // já saiu entretanto
        val v = Viewer(bridge, surface,
            kindVideo = "p$s", kindAudio = "p$s", baseTargetMs = 250,
            onState = {},
            onStats = { st -> ui.post { peerStats[s] = st; renderStats() } },
            onVideoSize = { w, h -> onPeerVideoSize(s, w, h) })
        viewers[s] = v
        v.start()
    }

    /** Média de um slot remoto (thread da ponte). */
    fun onMedia(kind: String, payload: ByteArray) {
        val s = kind.removePrefix("p").toIntOrNull() ?: return
        viewers[s]?.onMessage(kind, payload)
    }

    /** Mundo JS renasceu (reconnect): repor controlo, slot e subscrições. */
    fun resubscribe() {
        if (!active) return
        bridge.meetCtrlStart()
        if (slot >= 0) bridge.meetStart(slot)
        for (s in viewers.keys) bridge.subscribe("p$s")
    }

    private fun renderStats() {
        val now = SystemClock.elapsedRealtime()
        val sb = StringBuilder("slot=#$slot peers=${viewers.size} roster=${roster.size}")
        sb.append("\nctrl tx=").append(ctrlTx).append(" rx=").append(ctrlRx).append(" echo=").append(ctrlEcho)
        if (roster.isNotEmpty()) {
            sb.append("\nroster: ").append(roster.entries.joinToString(" ") {
                "#${it.value.slot}=${it.key.take(8)}·${"%.1f".format((now - it.value.at) / 1000.0)}s"
            })
        }
        for ((s, st) in peerStats.toSortedMap())
            sb.append("\n#").append(s).append(": ").append(st.replace('\n', ' '))
        onStats(sb.toString())
    }

    fun stop() {
        if (!active) return
        active = false
        ctrlPub("leave")
        ui.removeCallbacksAndMessages(null)
        val vs = viewers.values.toList()
        viewers.clear(); roster.clear(); peerStats.clear()
        thread(name = "meet-stop") {
            for (v in vs) try { v.stop() } catch (e: Exception) {}
            bridge.meetStop()
        }
    }
}
