package com.ck.restro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ck.restro.engine.Engine
import com.ck.restro.engine.History
import com.ck.restro.engine.ImageIO
import com.ck.restro.engine.Models
import com.ck.restro.engine.OutSize
import com.ck.restro.engine.RestoreJob
import com.ck.restro.engine.RestoreOptions
import com.ck.restro.ui.Appearance
import com.ck.restro.ui.CompareView
import com.ck.restro.ui.GlowBackdropView
import com.ck.restro.ui.Haptics
import com.ck.restro.ui.LiquidSlider
import com.ck.restro.ui.isGlass
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var opts: RestoreOptions
    private var photo: Uri? = null
    private var photoSize: Pair<Int, Int>? = null
    private var binding = false   // true while the UI is being filled from opts (ignore change events)

    // views
    private lateinit var compare: CompareView
    private lateinit var emptyPick: View
    private lateinit var rowPhotoInfo: View
    private lateinit var txtPhotoInfo: TextView
    private lateinit var cardProgress: View
    private lateinit var txtStage: TextView
    private lateinit var txtPercent: TextView
    private lateinit var txtEta: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var cardResult: View
    private lateinit var txtResultInfo: TextView
    private lateinit var chipsMode: ChipGroup
    private lateinit var txtModeDesc: TextView
    private lateinit var chipsSize: ChipGroup
    private lateinit var txtSizeInfo: TextView
    private lateinit var chipsEngine: ChipGroup
    private lateinit var txtEngineInfo: TextView
    private lateinit var rowDenoise: View
    private lateinit var sliderDenoise: LiquidSlider
    private lateinit var txtDenoise: TextView
    private lateinit var switchFaces: MaterialSwitch
    private lateinit var groupFace: View
    private lateinit var sliderFace: LiquidSlider
    private lateinit var txtFaceStrength: TextView
    private lateinit var switchBoost: MaterialSwitch
    private lateinit var sliderSharpen: LiquidSlider
    private lateinit var txtSharpen: TextView
    private lateinit var switchColor: MaterialSwitch
    private lateinit var chipsFormat: ChipGroup
    private lateinit var btnRestore: MaterialButton
    private lateinit var txtRestoreHint: TextView
    private var glow: GlowBackdropView? = null

    private lateinit var pageStudio: View
    private lateinit var pageLibrary: View
    private lateinit var pageModels: View
    private lateinit var modelsList: ViewGroup
    private lateinit var libraryAdapter: LibraryAdapter

    private var lastSaved: Uri? = null

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) setPhoto(uri)
    }
    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val jobListener: (RestoreJob.State) -> Unit = { onJob(it) }
    private val modelListener: () -> Unit = { refreshModels(); refreshRestoreButton() }

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        opts = RestoreOptions.load(this)

        findViews()
        if (isGlass()) glow?.visibility = View.VISIBLE
        setupStudio()
        setupLibrary()
        setupNav(savedInstanceState?.getInt("tab") ?: R.id.nav_studio)
        findViewById<View>(R.id.btnAppearance).setOnClickListener { showAppearance() }

        val restored = savedInstanceState?.getString("photo")?.let(Uri::parse)
        when {
            restored != null -> setPhoto(restored)
            intent?.action == Intent.ACTION_SEND -> handleShare(intent)
        }
        RestoreJob.addListener(jobListener)
        Models.addListener(modelListener)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_SEND) handleShare(intent)
    }

    private fun handleShare(i: Intent) {
        @Suppress("DEPRECATION")
        val uri = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        if (uri != null) {
            findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_studio
            setPhoto(uri)
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        photo?.let { out.putString("photo", it.toString()) }
        out.putInt("tab", findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId)
    }

    override fun onDestroy() {
        RestoreJob.removeListener(jobListener)
        Models.removeListener(modelListener)
        super.onDestroy()
    }

    private fun findViews() {
        compare = findViewById(R.id.compare)
        emptyPick = findViewById(R.id.emptyPick)
        rowPhotoInfo = findViewById(R.id.rowPhotoInfo)
        txtPhotoInfo = findViewById(R.id.txtPhotoInfo)
        cardProgress = findViewById(R.id.cardProgress)
        txtStage = findViewById(R.id.txtStage)
        txtPercent = findViewById(R.id.txtPercent)
        txtEta = findViewById(R.id.txtEta)
        progress = findViewById(R.id.progress)
        cardResult = findViewById(R.id.cardResult)
        txtResultInfo = findViewById(R.id.txtResultInfo)
        chipsMode = findViewById(R.id.chipsMode)
        txtModeDesc = findViewById(R.id.txtModeDesc)
        chipsSize = findViewById(R.id.chipsSize)
        txtSizeInfo = findViewById(R.id.txtSizeInfo)
        chipsEngine = findViewById(R.id.chipsEngine)
        txtEngineInfo = findViewById(R.id.txtEngineInfo)
        rowDenoise = findViewById(R.id.rowDenoise)
        sliderDenoise = findViewById(R.id.sliderDenoise)
        txtDenoise = findViewById(R.id.txtDenoise)
        switchFaces = findViewById(R.id.switchFaces)
        groupFace = findViewById(R.id.groupFace)
        sliderFace = findViewById(R.id.sliderFace)
        txtFaceStrength = findViewById(R.id.txtFaceStrength)
        switchBoost = findViewById(R.id.switchBoost)
        sliderSharpen = findViewById(R.id.sliderSharpen)
        txtSharpen = findViewById(R.id.txtSharpen)
        switchColor = findViewById(R.id.switchColor)
        chipsFormat = findViewById(R.id.chipsFormat)
        btnRestore = findViewById(R.id.btnRestore)
        txtRestoreHint = findViewById(R.id.txtRestoreHint)
        glow = findViewById(R.id.glowBackdrop)
        pageStudio = findViewById(R.id.pageStudio)
        pageLibrary = findViewById(R.id.pageLibrary)
        pageModels = findViewById(R.id.pageModels)
        modelsList = findViewById(R.id.modelsList)
    }

    // ---------------------------------------------------------------- navigation

    private fun setupNav(start: Int) {
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            pageStudio.visibility = if (item.itemId == R.id.nav_studio) View.VISIBLE else View.GONE
            pageLibrary.visibility = if (item.itemId == R.id.nav_library) View.VISIBLE else View.GONE
            pageModels.visibility = if (item.itemId == R.id.nav_models) View.VISIBLE else View.GONE
            when (item.itemId) {
                R.id.nav_library -> libraryAdapter.reload()
                R.id.nav_models -> refreshModels()
            }
            true
        }
        nav.selectedItemId = start
    }

    // ---------------------------------------------------------------- studio

    private fun chip(group: ChipGroup, text: String, id: Int): Chip {
        val c = LayoutInflater.from(this).inflate(R.layout.item_chip, group, false) as Chip
        c.id = id
        c.text = text
        group.addView(c)
        return c
    }

    private fun setupStudio() {
        RestoreOptions.MODE_NAMES.forEachIndexed { i, n -> chip(chipsMode, n, 1000 + i) }
        listOf("Original", "2×", "4×", "4K").forEachIndexed { i, n -> chip(chipsSize, n, 2000 + i) }
        listOf("Fast", "Ultra detail", "Anime").forEachIndexed { i, n -> chip(chipsEngine, n, 3000 + i) }
        listOf("JPEG", "PNG").forEachIndexed { i, n -> chip(chipsFormat, n, 4000 + i) }

        findViewById<View>(R.id.btnPick).setOnClickListener { pick() }
        emptyPick.setOnClickListener { pick() }
        findViewById<View>(R.id.btnChange).setOnClickListener { pick() }
        findViewById<View>(R.id.btnCancel).setOnClickListener { RestoreJob.cancel() }
        findViewById<View>(R.id.btnShare).setOnClickListener { lastSaved?.let { share(it) } }
        findViewById<View>(R.id.btnOpen).setOnClickListener { lastSaved?.let { view(it) } }

        chipsMode.setOnCheckedStateChangeListener { _, ids ->
            if (binding) return@setOnCheckedStateChangeListener
            val m = ids.firstOrNull()?.minus(1000) ?: return@setOnCheckedStateChangeListener
            opts.applyMode(m)
            Haptics.tick(this, 0.5f)
            changed(keepMode = true)
        }
        chipsSize.setOnCheckedStateChangeListener { _, ids ->
            if (binding) return@setOnCheckedStateChangeListener
            ids.firstOrNull()?.let { opts.size = OutSize.entries[it - 2000]; changed() }
        }
        chipsEngine.setOnCheckedStateChangeListener { _, ids ->
            if (binding) return@setOnCheckedStateChangeListener
            ids.firstOrNull()?.let { opts.engine = Engine.entries[it - 3000]; changed() }
        }
        chipsFormat.setOnCheckedStateChangeListener { _, ids ->
            if (binding) return@setOnCheckedStateChangeListener
            ids.firstOrNull()?.let { opts.png = it == 4001; changed(keepMode = true) }
        }
        sliderDenoise.addOnChangeListener { _, v, user -> if (user && !binding) { opts.denoise = v.roundToInt(); changed() } }
        sliderFace.addOnChangeListener { _, v, user -> if (user && !binding) { opts.faceStrength = v.roundToInt(); changed() } }
        sliderSharpen.addOnChangeListener { _, v, user -> if (user && !binding) { opts.sharpen = v.roundToInt(); changed() } }
        switchFaces.setOnCheckedChangeListener { _, on -> if (!binding) { opts.faces = on; changed() } }
        switchBoost.setOnCheckedChangeListener { _, on -> if (!binding) { opts.faceBoost = on; changed() } }
        switchColor.setOnCheckedChangeListener { _, on -> if (!binding) { opts.autoColor = on; changed() } }

        btnRestore.setOnClickListener { onRestoreClick() }
        bindOptions()
    }

    /** A setting changed by hand: the mode becomes "custom" unless it was the mode itself. */
    private fun changed(keepMode: Boolean = false) {
        if (!keepMode) opts.mode = RestoreOptions.MODE_CUSTOM
        opts.save(this)
        bindOptions()
    }

    private fun bindOptions() {
        binding = true
        if (opts.mode in 0..4) chipsMode.check(1000 + opts.mode) else chipsMode.clearCheck()
        txtModeDesc.text = RestoreOptions.MODE_DESCS[opts.mode.coerceIn(0, 5)]
        chipsSize.check(2000 + opts.size.ordinal)
        chipsEngine.check(3000 + opts.engine.ordinal)
        chipsFormat.check(if (opts.png) 4001 else 4000)

        // mark engines that still need downloading
        (chipsEngine.getChildAt(1) as Chip).text = if (Models.isReady(this, Models.ULTRA)) "Ultra detail" else "Ultra detail ↓"
        txtEngineInfo.text = when (opts.engine) {
            Engine.FAST -> "Quick, light on battery. Seconds for most photos."
            Engine.ULTRA -> "Finest detail. Can take a few minutes on big photos." +
                if (Models.isReady(this, Models.ULTRA)) "" else " Needs a one-time ${mb(Models.ULTRA.bytes)} download."
            Engine.ANIME -> "Tuned for drawings: clean lines, flat colours."
        }

        rowDenoise.visibility = if (opts.engine == Engine.FAST) View.VISIBLE else View.GONE
        sliderDenoise.value = opts.denoise.toFloat()
        txtDenoise.text = arrayOf("Keep grain", "Balanced", "Clean")[opts.denoise]

        switchFaces.isChecked = opts.faces
        groupFace.alpha = if (opts.faces) 1f else 0.4f
        sliderFace.isEnabled = opts.faces
        switchBoost.isEnabled = opts.faces
        sliderFace.value = opts.faceStrength.toFloat()
        txtFaceStrength.text = "${opts.faceStrength} %"
        switchBoost.isChecked = opts.faceBoost
        findViewById<TextView>(R.id.txtFaceSub).text =
            if (Models.isReady(this, Models.FACE)) getString(R.string.face_restore_sub)
            else "GFPGAN · one-time ${mb(Models.FACE.bytes)} download"

        sliderSharpen.value = opts.sharpen.toFloat()
        txtSharpen.text = if (opts.sharpen == 0) "Off" else "${opts.sharpen} %"
        switchColor.isChecked = opts.autoColor

        updateSizeInfo()
        binding = false
        refreshRestoreButton()
    }

    private fun mb(b: Long) = "${(b / 1_000_000.0).roundToInt()} MB"

    private fun updateSizeInfo() {
        val s = photoSize
        txtSizeInfo.text = if (s == null) when (opts.size) {
            OutSize.ORIGINAL -> "Same size, cleaned up"
            OutSize.X2 -> "Twice as wide and tall"
            OutSize.X4 -> "Four times as wide and tall"
            OutSize.K4 -> "Long side 3840 px"
        } else {
            val (w, h) = RestoreJob.outputSize(s.first, s.second, opts.size)
            "${s.first}×${s.second} → ${w}×${h}" + if (w * h > 24_000_000) "  ·  very large" else ""
        }
    }

    private fun missingModels(): List<Models.Spec> {
        val need = ArrayList<Models.Spec>()
        val up = Models.upscaler(opts.engine, opts.denoise)
        if (!Models.isReady(this, up)) need += up
        if (opts.faces && opts.faceStrength > 0 && !Models.isReady(this, Models.FACE)) need += Models.FACE
        return need
    }

    private fun refreshRestoreButton() {
        if (!::btnRestore.isInitialized) return
        val running = RestoreJob.isRunning
        val missing = missingModels()
        val downloading = missing.filter { Models.isDownloading(it) }
        btnRestore.isEnabled = !running && downloading.isEmpty()
        when {
            running -> { btnRestore.text = "Restoring…"; txtRestoreHint.text = "" }
            downloading.isNotEmpty() -> {
                val p = downloading.map { Models.progress[it.id] ?: 0f }.average()
                btnRestore.text = "Downloading ${(p * 100).roundToInt()} %"
                txtRestoreHint.text = downloading.joinToString(" + ") { it.title } + " · stays on your phone after this"
            }
            missing.isNotEmpty() -> {
                btnRestore.text = "Download models · ${mb(missing.sumOf { it.bytes })}"
                val err = missing.firstNotNullOfOrNull { Models.errors[it.id] }
                txtRestoreHint.text = err ?: (missing.joinToString(" + ") { it.title } + " download once over Wi-Fi, then work offline")
            }
            photo == null -> { btnRestore.text = "Choose a photo"; txtRestoreHint.text = "" }
            else -> {
                btnRestore.text = getString(R.string.restore)
                txtRestoreHint.text = "Runs fully on your phone. Saved to Pictures/Restro."
            }
        }
    }

    private fun onRestoreClick() {
        val missing = missingModels()
        if (missing.isNotEmpty()) {
            missing.forEach { Models.download(this, it) }
            refreshRestoreButton()
            return
        }
        val uri = photo ?: return pick()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) { askPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE); return }
        Haptics.tick(this, 0.9f)
        RestoreJob.start(this, uri, opts.copy())
        RestoreService.start(this)
    }

    private fun pick() {
        if (RestoreJob.isRunning) return
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun setPhoto(uri: Uri) {
        photo = uri
        RestoreJob.reset()
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { ImageIO.size(this@MainActivity, uri) to ImageIO.decode(this@MainActivity, uri, 2048) }
            }
            res.onSuccess { (size, preview) ->
                photoSize = size
                emptyPick.visibility = View.GONE
                rowPhotoInfo.visibility = View.VISIBLE
                txtPhotoInfo.text = "${size.first}×${size.second} · ${String.format("%.1f", size.first * size.second / 1e6)} MP"
                compare.set(preview, null)
                glow?.setArt(preview)
                updateSizeInfo()
                refreshRestoreButton()
            }.onFailure {
                photo = null
                Toast.makeText(this@MainActivity, "Couldn't open that photo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onJob(s: RestoreJob.State) {
        when (s) {
            is RestoreJob.State.Running -> {
                cardProgress.visibility = View.VISIBLE
                cardResult.visibility = View.GONE
                txtStage.text = s.stage
                txtPercent.text = "${(s.progress * 100).toInt()} %"
                progress.setProgressCompat((s.progress * 1000).toInt(), true)
                txtEta.text = if (s.etaSec >= 0) "About ${RestoreService.fmtEta(s.etaSec)} left" else "Working…"
            }
            is RestoreJob.State.Done -> {
                cardProgress.visibility = View.GONE
                cardResult.visibility = View.VISIBLE
                lastSaved = s.saved
                findViewById<TextView>(R.id.txtResultTitle).text = "Restored in ${RestoreService.fmtEta(s.seconds)}"
                txtResultInfo.text = s.info + "\nSaved to Pictures/Restro · drag the handle to compare, pinch to zoom"
                compare.set(s.before, s.after)
                emptyPick.visibility = View.GONE
                Haptics.tick(this, 1f)
            }
            is RestoreJob.State.Failed -> {
                cardProgress.visibility = View.GONE
                Toast.makeText(this, s.message, Toast.LENGTH_LONG).show()
            }
            RestoreJob.State.Idle -> {
                cardProgress.visibility = View.GONE
                cardResult.visibility = View.GONE
            }
        }
        refreshRestoreButton()
    }

    private fun share(uri: Uri) {
        val i = Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Share restored photo"))
    }

    private fun view(uri: Uri) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { Toast.makeText(this, "No app to open photos", Toast.LENGTH_SHORT).show() }
    }

    // ---------------------------------------------------------------- models page

    private fun refreshModels() {
        if (!::modelsList.isInitialized) return
        if (modelsList.childCount == 0) {
            val intro = TextView(this).apply {
                setTextAppearance(R.style.Label)
                text = "Every model runs on your phone: photos never leave it. Fast and Anime are built in; " +
                    "Ultra detail and Face restore download once."
                setPadding(8, 8, 8, 0)
            }
            modelsList.addView(intro)
            Models.CATALOG.forEach { spec ->
                val v = LayoutInflater.from(this).inflate(R.layout.item_model, modelsList, false)
                v.tag = spec.id
                modelsList.addView(v)
            }
        }
        for (i in 0 until modelsList.childCount) {
            val v = modelsList.getChildAt(i)
            val spec = Models.CATALOG.firstOrNull { it.id == v.tag } ?: continue
            v.findViewById<TextView>(R.id.txtTitle).text = spec.title
            v.findViewById<TextView>(R.id.txtDesc).text = spec.desc
            val status = v.findViewById<TextView>(R.id.txtStatus)
            val btn = v.findViewById<MaterialButton>(R.id.btnAction)
            val bar = v.findViewById<LinearProgressIndicator>(R.id.progress)
            val p = Models.progress[spec.id]
            when {
                spec.bundled -> {
                    status.text = "Built in · ${mb(spec.bytes)}"
                    btn.visibility = View.GONE; bar.visibility = View.GONE
                }
                p != null -> {
                    status.text = "Downloading ${(p * 100).roundToInt()} % of ${mb(spec.bytes)}"
                    btn.visibility = View.VISIBLE; btn.text = "Stop"; btn.setIconResource(R.drawable.ic_close)
                    btn.setOnClickListener { Models.cancelDownload(spec) }
                    bar.visibility = View.VISIBLE; bar.setProgressCompat((p * 1000).toInt(), true)
                }
                Models.isReady(this, spec) -> {
                    status.text = "Ready · ${mb(spec.bytes)}"
                    btn.visibility = View.VISIBLE; btn.text = "Delete"; btn.setIconResource(R.drawable.ic_delete)
                    btn.setOnClickListener { Models.delete(this, spec); refreshModels(); bindOptions() }
                    bar.visibility = View.GONE
                }
                else -> {
                    status.text = Models.errors[spec.id]?.let { "Failed: $it" } ?: "Not downloaded · ${mb(spec.bytes)}"
                    btn.visibility = View.VISIBLE; btn.text = "Download"; btn.setIconResource(R.drawable.ic_download)
                    btn.setOnClickListener { Models.download(this, spec) }
                    bar.visibility = View.GONE
                }
            }
        }
        if (::chipsEngine.isInitialized && !binding) bindOptions()
    }

    // ---------------------------------------------------------------- library page

    private fun setupLibrary() {
        val list = findViewById<RecyclerView>(R.id.listLibrary)
        list.layoutManager = GridLayoutManager(this, 3)
        libraryAdapter = LibraryAdapter()
        list.adapter = libraryAdapter
    }

    private inner class LibraryAdapter : RecyclerView.Adapter<LibraryAdapter.VH>() {
        var items: List<History.Item> = emptyList()
        private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.img)
            val txt: TextView = v.findViewById(R.id.txt)
        }

        fun reload() {
            items = History.all(this@MainActivity)
            findViewById<View>(R.id.txtLibraryEmpty).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_library, parent, false))

        override fun onBindViewHolder(h: VH, pos: Int) {
            val it = items[pos]
            val key = it.uri.toString()
            h.txt.text = it.info.substringAfter("→ ").ifEmpty { it.info }
            h.img.tag = key
            val cached = cache.get(key)
            if (cached != null) h.img.setImageBitmap(cached)
            else {
                h.img.setImageDrawable(null)
                lifecycleScope.launch {
                    val b = withContext(Dispatchers.IO) { thumb(it.uri) }
                    if (b != null) cache.put(key, b)
                    if (h.img.tag == key) h.img.setImageBitmap(b)
                }
            }
            h.itemView.setOnClickListener { _ -> view(it.uri) }
            h.itemView.setOnLongClickListener { v ->
                PopupMenu(this@MainActivity, v).apply {
                    menu.add(0, 1, 0, "Share")
                    menu.add(0, 2, 1, "Remove from list")
                    setOnMenuItemClickListener { m ->
                        if (m.itemId == 1) share(it.uri) else { History.remove(this@MainActivity, it.uri); reload() }
                        true
                    }
                }.show()
                true
            }
        }

        private fun thumb(uri: Uri): Bitmap? = runCatching {
            if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(uri, android.util.Size(360, 360), null)
            else {
                val o = BitmapFactory.Options().apply { inSampleSize = 8 }
                contentResolver.openInputStream(uri)?.use { s -> BitmapFactory.decodeStream(s, null, o) }
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- appearance

    private fun showAppearance() {
        val d = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_appearance, null)
        val themes = v.findViewById<ChipGroup>(R.id.chipsTheme)
        val accents = v.findViewById<ChipGroup>(R.id.chipsAccent)
        val desc = v.findViewById<TextView>(R.id.txtThemeDesc)
        Appearance.THEME_ORDER.forEach { t -> chip(themes, Appearance.THEME_NAMES[t], 5000 + t) }
        Appearance.ACCENT_NAMES.forEachIndexed { i, n ->
            chip(accents, n, 6000 + i).apply {
                chipIcon = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()).apply {
                    paint.color = Appearance.ACCENT_COLORS[i]; intrinsicWidth = 36; intrinsicHeight = 36
                }
                isChipIconVisible = true
            }
        }
        val curTheme = Appearance.theme(this)
        themes.check(5000 + curTheme)
        accents.check(6000 + Appearance.accent(this))
        desc.text = Appearance.THEME_DESCS[curTheme]
        themes.setOnCheckedStateChangeListener { _, ids ->
            val t = (ids.firstOrNull() ?: return@setOnCheckedStateChangeListener) - 5000
            if (t != Appearance.theme(this)) { Appearance.setTheme(this, t); d.dismiss(); recreate() }
        }
        accents.setOnCheckedStateChangeListener { _, ids ->
            val a = (ids.firstOrNull() ?: return@setOnCheckedStateChangeListener) - 6000
            if (a != Appearance.accent(this)) { Appearance.setAccent(this, a); d.dismiss(); recreate() }
        }
        d.setContentView(v)
        d.show()
    }
}
