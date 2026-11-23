package com.ck.restro.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/** ONNX Runtime sessions, loaded once per model file and kept while the app runs. */
object Ort {
    val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val sessions = HashMap<String, OrtSession>()

    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

    @Synchronized
    fun session(path: String): OrtSession {
        val key = path.substringAfterLast('/')
        sessions[key]?.let { return it }
        val s = try {
            env.createSession(path, options(xnnpack = true))
        } catch (e: Throwable) {
            // some phones/models reject the XNNPACK accelerator: plain CPU always works
            env.createSession(path, options(xnnpack = false))
        }
        sessions[key] = s
        return s
    }

    private fun options(xnnpack: Boolean) = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        if (xnnpack) {
            // XNNPACK does the threading itself; ORT's own pool stays at 1 so they don't fight
            setIntraOpNumThreads(1)
            addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
        } else {
            setIntraOpNumThreads(threads)
        }
    }

    @Synchronized
    fun drop(file: String) {
        sessions.remove(file)?.close()
    }

    @Synchronized
    fun dropAll() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
    }

    /** Runs a 1×3×H×W float image through [s]; returns the output tensor's floats and shape. */
    fun run(s: OrtSession, data: FloatArray, h: Int, w: Int): Pair<FloatArray, LongArray> {
        val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, h.toLong(), w.toLong()))
        input.use {
            s.run(mapOf(s.inputNames.first() to input)).use { res ->
                val t = res.get(0) as OnnxTensor
                val shape = t.info.shape
                val fb = t.floatBuffer
                val out = FloatArray(fb.remaining())
                fb.get(out)
                return out to shape
            }
        }
    }
}
