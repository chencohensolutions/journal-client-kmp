package com.chencohensolution.journal.local

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer

/**
 * The search model's text half on the phone, with ONNX Runtime (as the server runs it: `input_ids`, 1 × 64, and the
 * vector from `pooler_output`). The session is made on the first search and kept until [releaseModel].
 */
internal class AndroidTextEncoding : TextEncoding {
    private var session: Pair<String, OrtSession>? = null

    @Synchronized
    override fun encode(modelPath: String, tokens: LongArray): FloatArray? = runCatching {
        val env = OrtEnvironment.getEnvironment()
        val open = session?.takeIf { it.first == modelPath }?.second ?: run {
            releaseModel()
            val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
            env.createSession(modelPath, options).also { session = modelPath to it }
        }
        OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), longArrayOf(1, tokens.size.toLong())).use { input ->
            open.run(mapOf("input_ids" to input)).use { result ->
                val output = result.get("pooler_output").orElse(null) ?: result.get(0)
                @Suppress("UNCHECKED_CAST")
                (output.value as Array<FloatArray>)[0]
            }
        }
    }.getOrNull()

    @Synchronized
    override fun releaseModel() {
        session?.second?.close()
        session = null
    }
}
