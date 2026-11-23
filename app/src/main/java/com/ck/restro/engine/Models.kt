package com.ck.restro.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The AI models. Small ones ship inside the APK; the big ones (Ultra detail, Face restore)
 * download once from the Restro GitHub release and live in the app's private storage.
 */
object Models {
    private const val RELEASE = "https://github.com/cryptoking12344-lgtm/Restro/releases/download/models-v1/"

    class Spec(
        val id: String,
        val title: String,
        val desc: String,
        val file: String,
        val bytes: Long,
        val bundled: Boolean,
    )

    val FAST_GRAIN = Spec("fast_grain", "Fast · keep grain", "", "fast_grain.onnx", 2450320, true)
    val FAST_BALANCED = Spec("fast_balanced", "Fast · balanced", "", "fast_balanced.onnx", 2450320, true)
    val FAST_CLEAN = Spec("fast_clean", "Fast · clean", "", "fast_clean.onnx", 2450320, true)

    val FAST = Spec(
        "fast", "Fast upscaler",
        "Real-ESRGAN general v3, compact. Quick 4× upscale with three noise-cleanup levels. Built in, works offline.",
        "fast_balanced.onnx", 3 * 2450320L, true,
    )
    val ANIME = Spec(
        "anime", "Anime upscaler",
        "Real-ESRGAN anime v3. Keeps line art crisp and flat colours clean. Built in.",
        "anime.onnx", 1255503, true,
    )
    val ULTRA = Spec(
        "ultra", "Ultra detail",
        "Real-ESRGAN x4plus, the full 23-block network. Much finer textures and edges, about 10× slower than Fast.",
        "ultra.onnx", 33710801, false,
    )
    val FACE = Spec(
        "face", "Face restore",
        "GFPGAN 1.4. Rebuilds eyes, skin, lips and hair on blurry or low-res faces. Works with any engine.",
        "face_gfpgan14.onnx", 170288640, false,
    )

    /** Shown on the Models page, in order. */
    val CATALOG = listOf(FAST, ANIME, ULTRA, FACE)

    fun upscaler(e: Engine, denoise: Int): Spec = when (e) {
        Engine.ULTRA -> ULTRA
        Engine.ANIME -> ANIME
        Engine.FAST -> when (denoise) { 0 -> FAST_GRAIN; 2 -> FAST_CLEAN; else -> FAST_BALANCED }
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    fun file(ctx: Context, s: Spec) = File(dir(ctx), s.file)

    /** Downloads only get their final name once complete, so existing = ready. */
    fun isReady(ctx: Context, s: Spec) = s.bundled || file(ctx, s).let { it.exists() && it.length() > 1_000_000 }

    /** Absolute path for ONNX Runtime; copies a bundled model out of the APK the first time. */
    fun path(ctx: Context, s: Spec): String {
        val f = file(ctx, s)
        if (s.bundled) {
            if (!f.exists() || f.length() == 0L) {
                val tmp = File(f.path + ".tmp")
                ctx.assets.open("models/${s.file}").use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp.renameTo(f)
            }
        } else if (!isReady(ctx, s)) {
            throw IllegalStateException("${s.title} isn't downloaded yet")
        }
        return f.absolutePath
    }

    fun delete(ctx: Context, s: Spec) {
        if (!s.bundled) file(ctx, s).delete()
        Ort.drop(s.file)
    }

    // ---------------- downloads ----------------

    /** id -> 0..1 while downloading. */
    val progress = HashMap<String, Float>()
    val errors = HashMap<String, String>()
    private val jobs = HashMap<String, Job>()
    private val listeners = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true).build()
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun fireChanged() = main.post { listeners.toList().forEach { it() } }

    fun isDownloading(s: Spec) = jobs[s.id]?.isActive == true

    fun download(ctx: Context, s: Spec) {
        if (s.bundled || isDownloading(s) || isReady(ctx, s)) return
        val app = ctx.applicationContext
        errors.remove(s.id)
        progress[s.id] = 0f
        fireChanged()
        jobs[s.id] = scope.launch {
            val out = file(app, s)
            val part = File(out.path + ".part")
            try {
                val req = Request.Builder().url(RELEASE + s.file).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("Server said ${resp.code}")
                    val body = resp.body ?: throw RuntimeException("Empty download")
                    val known = body.contentLength()
                    val total = if (known > 0) known else s.bytes
                    body.byteStream().use { input ->
                        part.outputStream().use { o ->
                            val buf = ByteArray(256 * 1024)
                            var done = 0L
                            var lastPost = 0L
                            while (isActive) {
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (now - lastPost > 200) {
                                    lastPost = now
                                    progress[s.id] = (done.toFloat() / total).coerceIn(0f, 1f)
                                    fireChanged()
                                }
                            }
                        }
                    }
                    if (isActive && known > 0 && part.length() != known) throw RuntimeException("Download incomplete, try again")
                }
                if (!isActive) { part.delete(); return@launch }
                part.renameTo(out)
            } catch (e: Exception) {
                part.delete()
                errors[s.id] = e.message ?: "Download failed"
            } finally {
                progress.remove(s.id)
                jobs.remove(s.id)
                fireChanged()
            }
        }
    }

    fun cancelDownload(s: Spec) {
        jobs[s.id]?.cancel()
    }
}
