package com.ck.restro.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The one restoration that can run at a time. Screens and the notification watch [state]. */
object RestoreJob {

    sealed class State {
        object Idle : State()
        class Running(val stage: String, val progress: Float, val etaSec: Int) : State()
        class Done(
            val before: Bitmap, val after: Bitmap, val saved: Uri?,
            val info: String, val seconds: Int, val faces: Int,
        ) : State()
        class Failed(val message: String) : State()
    }

    @Volatile var state: State = State.Idle
        private set

    private val listeners = ArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var cancel = false

    fun addListener(l: (State) -> Unit) { listeners += l; l(state) }
    fun removeListener(l: (State) -> Unit) { listeners -= l }

    private fun post(s: State) {
        state = s
        main.post { listeners.toList().forEach { it(s) } }
    }

    val isRunning get() = state is State.Running

    fun cancel() { cancel = true }

    fun reset() { if (!isRunning) post(State.Idle) }

    /** Max input side fed to each network (bigger photos are shrunk first). */
    private fun maxInput(e: Engine) = when (e) { Engine.ULTRA -> 2048; else -> 3072 }
    private fun tileFor(e: Engine) = when (e) { Engine.ULTRA -> 128; else -> 256 }
    const val MAX_OUT = 7680

    /** Output size for a w×h photo. */
    fun outputSize(w: Int, h: Int, size: OutSize): Pair<Int, Int> {
        val long = max(w, h)
        var s = when (size) {
            OutSize.ORIGINAL -> 1f
            OutSize.X2 -> 2f
            OutSize.X4 -> 4f
            OutSize.K4 -> max(1f, 3840f / long)
        }
        s = min(s, MAX_OUT.toFloat() / long)
        return (w * s).roundToInt().coerceAtLeast(1) to (h * s).roundToInt().coerceAtLeast(1)
    }

    fun start(ctx: Context, uri: Uri, opts: RestoreOptions) {
        if (isRunning) return
        val app = ctx.applicationContext
        cancel = false
        post(State.Running("Opening photo", 0f, -1))
        job = scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val upSpec = Models.upscaler(opts.engine, opts.denoise)
                if (!Models.isReady(app, upSpec)) throw RuntimeException("Download ${upSpec.title} on the Models tab first")
                val wantFaces = opts.faces && opts.faceStrength > 0
                if (wantFaces && !Models.isReady(app, Models.FACE)) throw RuntimeException("Download Face restore on the Models tab first, or turn faces off")

                val (ow0, oh0) = ImageIO.size(app, uri)
                val (outW, outH) = outputSize(ow0, oh0, opts.size)
                // the network makes 4x; feeding it more than the output needs only costs time
                val need = (max(outW, outH) / (if (opts.engine == Engine.ULTRA) 2f else 1.5f)).roundToInt().coerceAtLeast(512)
                val src = ImageIO.decode(app, uri, min(maxInput(opts.engine), need))
                val sx = outW.toFloat() / src.width
                val sy = outH.toFloat() / src.height

                // weights for the progress bar: faces and finishing are quick next to the upscale
                val wUp = if (wantFaces) 0.78f else 0.94f
                val wFace = if (wantFaces) 0.16f else 0f
                fun report(stage: String, p: Float) {
                    val el = (SystemClock.elapsedRealtime() - t0) / 1000f
                    val eta = if (p > 0.04f) ((el / p) * (1f - p)).roundToInt() else -1
                    post(State.Running(stage, p.coerceIn(0f, 1f), eta))
                }

                report("Loading ${upSpec.title}", 0.01f)
                val up = Ort.session(Models.path(app, upSpec))
                val upStage = if (opts.engine == Engine.ULTRA) "Upscaling · Ultra detail" else "Upscaling"
                val out = Upscaler.run(up, src, outW, outH, tileFor(opts.engine),
                    { d, t -> report("$upStage · tile $d of $t", 0.02f + wUp * d / t) }, { cancel })

                var faceCount = 0
                if (wantFaces) {
                    report("Finding faces", 0.02f + wUp)
                    val faces = FaceRestorer.detect(src)
                    faceCount = faces.size
                    if (faces.isNotEmpty()) {
                        val gf = Ort.session(Models.path(app, Models.FACE))
                        val boost = if (opts.faceBoost) Ort.session(Models.path(app, Models.FAST_CLEAN)) else null
                        faces.forEachIndexed { i, f ->
                            if (cancel) throw CancelledException()
                            report("Restoring face ${i + 1} of ${faces.size}", 0.02f + wUp + wFace * i / faces.size)
                            val crop = FaceRestorer.crop(src, f)
                            var restored = FaceRestorer.restore(gf, crop)
                            crop.recycle()
                            // big on the output? give the restored face real 4× detail before pasting
                            if (boost != null && FaceRestorer.outputSize(f, sx) > 700f) {
                                val big = Upscaler.runWhole(boost, restored)
                                restored.recycle(); restored = big
                            }
                            FaceRestorer.paste(out, f, restored, sx, sy, opts.faceStrength / 100f)
                            restored.recycle()
                        }
                    }
                }

                if (opts.sharpen > 0 || opts.autoColor) {
                    report("Finishing", 0.97f)
                    val lut = if (opts.autoColor) Finish.colorLut(out) else null
                    Finish.apply(out, opts.sharpen / 100f, lut) { cancel }
                }

                report("Saving", 0.99f)
                val saved = ImageIO.save(app, out, opts.png)
                History.add(app, saved, "${src.width}×${src.height} → ${outW}×${outH}")

                val secs = ((SystemClock.elapsedRealtime() - t0) / 1000).toInt()
                val faceTxt = if (!wantFaces) "" else if (faceCount == 0) " · no faces found" else " · $faceCount face${if (faceCount > 1) "s" else ""} restored"
                post(State.Done(src, out, saved, "${ow0}×${oh0} → ${outW}×${outH}$faceTxt", secs, faceCount))
            } catch (e: CancelledException) {
                post(State.Idle)
            } catch (e: OutOfMemoryError) {
                Ort.dropAll()
                post(State.Failed("Ran out of memory. Try a smaller output size."))
            } catch (e: Throwable) {
                post(State.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
    }
}
