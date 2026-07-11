package com.aicallscreening.mobile.gemma

import android.content.Context
import android.util.Log
import com.aicallscreening.common.ScamPrompt
import com.aicallscreening.common.ScamVerdict
import com.aicallscreening.common.VerdictParser
import com.aicallscreening.common.WavUtils
import com.aicallscreening.mobile.analysis.ScamAudioAnalyzer
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device scam analyzer backed by Gemma 4 E2B via LiteRT-LM.
 *
 * Each audio window is analyzed in a fresh, stateless conversation so windows
 * are independent and memory is released promptly. The engine is loaded once
 * (via [prepare]) and reused across windows; it must be [close]d when done.
 */
class LiteRtGemmaAnalyzer(
    private val context: Context,
    private val modelManager: GemmaModelManager,
) : ScamAudioAnalyzer {

    private var engine: Engine? = null

    override val isReady: Boolean
        get() = engine != null

    override suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        if (engine != null) {
            return@withContext true
        }
        if (!modelManager.isModelAvailable) {
            Log.w(TAG, "Model not available; cannot prepare on-device engine")
            return@withContext false
        }
        try {
            val config = EngineConfig(
                modelPath = modelManager.modelFile.absolutePath,
                backend = Backend.GPU(),
                audioBackend = Backend.CPU(),
                cacheDir = context.cacheDir.path,
            )
            engine = Engine(config).also { it.initialize() }
            Log.i(TAG, "On-device Gemma engine initialized")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to initialize on-device engine", error)
            engine = null
            false
        }
    }

    override suspend fun analyze(pcm: ByteArray): ScamVerdict? = withContext(Dispatchers.Default) {
        val activeEngine = engine ?: return@withContext null
        try {
            val wav = WavUtils.pcm16leToWav(pcm)
            val conversationConfig = ConversationConfig(
                systemInstruction = Contents.of(ScamPrompt.SYSTEM_INSTRUCTION),
            )
            activeEngine.createConversation(conversationConfig).use { conversation ->
                val response = conversation.sendMessage(
                    Contents.of(
                        Content.AudioBytes(wav),
                        Content.Text(ScamPrompt.ON_DEVICE_TURN_PROMPT),
                    ),
                )
                val text = extractText(response)
                Log.i(TAG, "On-device response: $text")
                VerdictParser.parse(text)
            }
        } catch (error: Exception) {
            Log.e(TAG, "On-device analysis failed", error)
            null
        }
    }

    override fun close() {
        try {
            engine?.close()
        } catch (error: Exception) {
            Log.w(TAG, "Error closing engine", error)
        } finally {
            engine = null
        }
    }

    private fun extractText(message: Any?): String {
        if (message == null) return ""
        return runCatching {
            val contents = (message as com.google.ai.edge.litertlm.Message).contents.contents
            contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        }.getOrElse { message.toString() }
    }

    private companion object {
        const val TAG = "LiteRtGemmaAnalyzer"
    }
}
