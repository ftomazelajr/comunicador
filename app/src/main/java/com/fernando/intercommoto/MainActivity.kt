package com.fernando.intercommoto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        const val SERVER_URL = "wss://comunicador-moto.onrender.com"
        const val REQ_AUDIO = 100
        const val JANELA_MS = 6000L
    }

    private lateinit var statusText: TextView
    private lateinit var layoutSelecao: LinearLayout
    private lateinit var layoutMotorista: LinearLayout
    private lateinit var layoutPassageiro: LinearLayout
    private lateinit var btnTalk: Button
    private lateinit var debugLog: TextView
    private lateinit var chatText: TextView
    private lateinit var txtMensagem: EditText

    private var papel: String = ""
    private var ws: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var speechRecognizer: SpeechRecognizer? = null
    private var sentinelaAtivo = false
    private var janelaFalaAtiva = false
    private val handler = Handler(Looper.getMainLooper())
    private var janelaRunnable: Runnable? = null
    private var tts: TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null
    private var toneGen: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        layoutSelecao = findViewById(R.id.layoutSelecao)
        layoutMotorista = findViewById(R.id.layoutMotorista)
        layoutPassageiro = findViewById(R.id.layoutPassageiro)
        btnTalk = findViewById(R.id.btnTalk)
        debugLog = findViewById(R.id.debugLog)
        chatText = findViewById(R.id.chatText)
        txtMensagem = findViewById(R.id.txtMensagem)

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        tts = TextToSpeech(this, this)

        findViewById<Button>(R.id.btnMotorista).setOnClickListener { conectar("motorista") }
        findViewById<Button>(R.id.btnPassageiro).setOnClickListener { conectar("passageiro") }
        btnTalk.setOnClickListener { iniciarSentinela() }
        findViewById<Button>(R.id.btnEnviar).setOnClickListener { enviarTexto() }

        val frases = mapOf(
            R.id.btnCuidado to "Cuidado! Perigo à frente!",
            R.id.btnDevagar to "Devagar, abranda um pouco.",
            R.id.btnParar to "Para a moto assim que puderes.",
            R.id.btnBanheiro to "Preciso parar no banheiro.",
            R.id.btnPosto to "Quero parar num posto.",
            R.id.btnOk to "Tudo certo, pode acelerar!"
        )
        frases.forEach { (id, frase) ->
            findViewById<Button>(id).setOnClickListener { enviarFrasePronta(frase) }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("pt", "BR")
        }
    }

    // ---------- WEBSOCKET ----------

    private fun conectar(p: String) {
        papel = p
        statusText.text = "A ligar ao servidor..."
        val request = Request.Builder().url(SERVER_URL).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread {
                    layoutSelecao.visibility = View.GONE
                    if (papel == "motorista") {
                        layoutMotorista.visibility = View.VISIBLE
                        statusText.text = "Clique para ligar o microfone."
                    } else {
                        layoutPassageiro.visibility = View.VISIBLE
                        statusText.text = "Ligado como PASSAGEIRO"
                    }
                }
                val json = JSONObject().put("origem", papel).put("tipo", "registro")
                webSocket.send(json.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val dados = JSONObject(text)
                    val texto = dados.optString("texto", "")
                    if (texto.isEmpty()) return
                    runOnUiThread {
                        if (papel == "passageiro") {
                            chatText.text = texto
                            vibrar(300)
                        } else if (papel == "motorista") {
                            falarNoFone(texto)
                        }
                    }
                } catch (e: Exception) { }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread { statusText.text = "Sinal perdido. Reconectando..." }
                handler.postDelayed({ conectar(papel) }, 2000)
            }
        })
    }

    private fun enviarFrasePronta(frase: String) {
        enviarMensagem(frase)
        statusText.text = "Alerta enviado!"
        handler.postDelayed({ statusText.text = "Ligado como PASSAGEIRO" }, 1500)
    }

    private fun enviarTexto() {
        val texto = txtMensagem.text.toString().trim()
        if (texto.isNotEmpty()) {
            enviarMensagem(texto)
            txtMensagem.text.clear()
        }
    }

    private fun enviarMensagem(texto: String) {
        val json = JSONObject().put("origem", papel).put("texto", texto)
        ws?.send(json.toString())
    }

    private fun falarNoFone(texto: String) {
        tts?.stop()
        tts?.setSpeechRate(1.25f)
        tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "intercom")
    }

    // ---------- VOZ / SENTINELA ----------

    private fun iniciarSentinela() {
        if (sentinelaAtivo) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Reconhecimento de voz não disponível neste aparelho.", Toast.LENGTH_LONG).show()
            return
        }
        adquirirWakeLock()
        sentinelaAtivo = true
        btnTalk.text = "MODO\nSENTINELA"
        statusText.text = "Sentinela ativado! Diga 'AVISO'."
        iniciarReconhecimento()
    }

    private fun criarRecognizer(): SpeechRecognizer {
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                log("🟢 Microfone ativo — fale AVISO")
            }
            override fun onResults(results: Bundle?) {
                val lista = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                lista?.firstOrNull()?.let { processar(it, true) }
                reiniciarRecognizer()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val lista = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                lista?.firstOrNull()?.let { processar(it, false) }
            }
            override fun onError(error: Int) {
                log("⚠️ Erro de reconhecimento: $error")
                if (sentinelaAtivo) handler.postDelayed({ reiniciarRecognizer() }, 300)
            }
            override fun onEndOfSpeech() {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        return r
    }

    private fun iniciarReconhecimento() {
        speechRecognizer = criarRecognizer()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun reiniciarRecognizer() {
        if (!sentinelaAtivo) return
        try { speechRecognizer?.destroy() } catch (e: Exception) { }
        iniciarReconhecimento()
    }

    // isFinal = true quando vem de onResults (frase completa), false quando vem de onPartialResults
    private fun processar(fraseOriginal: String, isFinal: Boolean) {
        val frase = norm(fraseOriginal)
        log(fraseOriginal)

        if (!janelaFalaAtiva) {
            // ainda esperando a palavra-gatilho
            if (frase.contains("aviso")) {
                abrirJanela()
            }
        } else {
            // janela já aberta = essa é uma sessão NOVA de reconhecimento,
            // só captura a mensagem em si (sem o "aviso" misturado)
            if (isFinal && fraseOriginal.isNotBlank()) {
                enviarMensagem(fraseOriginal)
                janelaRunnable?.let { handler.removeCallbacks(it) }
                fecharJanela()
            }
        }
    }

    private fun abrirJanela() {
        if (janelaFalaAtiva) return
        janelaFalaAtiva = true
        bip(880)
        runOnUiThread {
            btnTalk.text = "🎤 FALANDO..."
            statusText.text = "Transmissão ativa! Fale agora..."
        }
        vibrarPadrao(longArrayOf(0, 100, 50, 100))
        log("Janela aberta — fale a mensagem")
        janelaRunnable = Runnable { fecharJanela() }
        handler.postDelayed(janelaRunnable!!, JANELA_MS)
        // reinicia o reconhecimento numa sessão limpa, só pra capturar a mensagem
        reiniciarRecognizer()
    }

    private fun fecharJanela() {
        janelaFalaAtiva = false
        bip(440)
        runOnUiThread {
            btnTalk.text = "MODO\nSENTINELA"
            statusText.text = "Diga 'AVISO' para falar."
        }
        log("Escutando... diga AVISO")
    }

    private fun bip(freq: Int) {
        val tone = if (freq > 600) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_ACK
        toneGen?.startTone(tone, 150)
    }

    private fun norm(t: String): String {
        val normalized = Normalizer.normalize(t.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{Mn}+"), "").trim()
    }

    private fun log(txt: String) {
        runOnUiThread { debugLog.text = "🎤 $txt" }
    }

    private fun vibrar(duracao: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(duracao, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(duracao)
        }
    }

    private fun vibrarPadrao(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, -1)
        }
    }

    private fun adquirirWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IntercomMoto:Sentinela")
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire(12 * 60 * 60 * 1000L)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                iniciarSentinela()
            } else {
                Toast.makeText(this, "Permita o microfone nas configurações do app.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sentinelaAtivo = false
        try { speechRecognizer?.destroy() } catch (e: Exception) { }
        wakeLock?.let { if (it.isHeld) it.release() }
        tts?.shutdown()
        ws?.close(1000, null)
        toneGen?.release()
    }
}
