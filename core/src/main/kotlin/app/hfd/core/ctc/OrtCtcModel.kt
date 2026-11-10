package app.hfd.core.ctc

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * The CTC model run by ONNX Runtime (the same Java API on the phone and on a JVM: the app brings
 * `onnxruntime-android`, the bench `onnxruntime`). Inputs "features" [1, 80, frames] and "lengths"
 * [1]; outputs the log-probabilities [1, out frames, symbols] and how many frames count.
 */
class OrtCtcModel(path: String, threads: Int) : CtcRecognizer.CtcModel, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(threads)
        // Idle threads sleep between readings instead of spinning (a phone's battery).
        options.addConfigEntry("session.intra_op.allow_spinning", "0")
        env.createSession(path, options)
    }

    override fun run(features: Mel.Features): CtcOutput {
        val shape = longArrayOf(1, Mel.MELS.toLong(), features.frames.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(features.data), shape).use { sound ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(features.length.toLong())), longArrayOf(1)).use { length ->
                session.run(mapOf("features" to sound, "lengths" to length)).use { result ->
                    val probabilities = result.get(0) as OnnxTensor
                    val symbols = probabilities.info.shape[2].toInt()
                    val frames = (result.get(1) as OnnxTensor).longBuffer.get(0).toInt()
                    val values = FloatArray(frames * symbols)
                    probabilities.floatBuffer.get(values)
                    return CtcOutput(values, frames, symbols)
                }
            }
        }
    }

    override fun close() {
        session.close()
    }
}
