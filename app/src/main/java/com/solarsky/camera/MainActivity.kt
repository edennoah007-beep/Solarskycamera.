package com.solarsky.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.GeomagneticField
import android.hardware.camera2.CameraCharacteristics
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        const val MAX_PHOTO_ZOOM = 10000f
        const val MAX_VIDEO_ZOOM = 5000f
    }

    // views
    private lateinit var previewView: PreviewView
    private lateinit var overlay: ArOverlayView
    private lateinit var zoomMap: ZoomMapView
    private lateinit var skyPanel: View
    private lateinit var skyMap: SkyMapView
    private lateinit var infoText: TextView
    private lateinit var zoomText: TextView
    private lateinit var zoomDetail: TextView
    private lateinit var zoomBar: SeekBar
    private lateinit var btnShutter: View
    private lateinit var btnMode: TextView
    private lateinit var btnTimer: TextView
    private lateinit var btnLabels: TextView
    private lateinit var evText: TextView
    private lateinit var flash: View

    // camera
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var videoMode = false
    private var targetZoom = 1f
    private var deviceMaxZoom = 1f
    private var qualityLabel = ""
    private var lastSavedUri: Uri? = null
    private var recStart = 0L
    private lateinit var cameraExecutor: ExecutorService

    // capture helpers
    private var timerMode = 0      // 0 off, 1 = 3s, 2 = 10s, 3 = wait until steady
    private var pendingCapture: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false

    // sensors
    private lateinit var sensorManager: SensorManager
    private val rotM = FloatArray(9)
    private val smoothM = FloatArray(9)
    private var smoothInit = false
    private val grav = FloatArray(3)
    private val mag = FloatArray(3)
    private var hasGrav = false
    private var hasMag = false
    private var useRotationVector = false
    private var shake = 0f
    private var declination = 0f
    private var lastSkyCompute = 0L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res[Manifest.permission.CAMERA] == true) startCamera()
            else toast("Camera permission is needed")
            if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                res[Manifest.permission.ACCESS_COARSE_LOCATION] == true) startLocation()
        }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        cameraExecutor = Executors.newSingleThreadExecutor()

        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.arOverlay)
        zoomMap = findViewById(R.id.zoomMap)
        skyPanel = findViewById(R.id.skyPanel)
        skyMap = findViewById(R.id.skyMap)
        infoText = findViewById(R.id.infoText)
        zoomText = findViewById(R.id.zoomText)
        zoomDetail = findViewById(R.id.zoomDetail)
        zoomBar = findViewById(R.id.zoomBar)
        btnShutter = findViewById(R.id.btnShutter)
        btnMode = findViewById(R.id.btnMode)
        btnTimer = findViewById(R.id.btnTimer)
        btnLabels = findViewById(R.id.btnLabels)
        evText = findViewById(R.id.evText)
        flash = findViewById(R.id.flash)

        // TextureView mode so we can add extra digital magnification by scaling the view
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        setupControls()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        refreshSky()

        val needed = mutableListOf(
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT <= 28) needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) { startCamera(); startLocation() }
        else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
            permissionLauncher.launch(missing.toTypedArray())
        }
        handler.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        val rv = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        useRotationVector = rv != null
        if (rv != null) sensorManager.registerListener(this, rv, SensorManager.SENSOR_DELAY_GAME)
        else {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        recording?.stop()
        recording = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (skyPanel.visibility == View.VISIBLE) skyPanel.visibility = View.GONE
        else super.onBackPressed()
    }

    // ---------------------------------------------------------------- UI

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControls() {
        zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) applyZoom(progressToZoom(p), updateBar = false)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) { userSeeking = false }
        })

        val quick = findViewById<LinearLayout>(R.id.quickZoom)
        val steps = floatArrayOf(1f, 3f, 10f, 30f, 100f, 300f, 1000f, 5000f, 10000f)
        for (z in steps) {
            val tv = TextView(this)
            tv.text = if (z >= 1000) "${(z / 1000).toInt()}Kx" else "${z.toInt()}x"
            tv.setTextColor(0xFFFFFFFF.toInt())
            tv.textSize = 13f
            tv.setBackgroundResource(R.drawable.btn_bg)
            val pad = (10 * resources.displayMetrics.density).toInt()
            tv.setPadding(pad + 4, pad / 2, pad + 4, pad / 2)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = pad / 2
            tv.layoutParams = lp
            tv.setOnClickListener { applyZoom(z) }
            quick.addView(tv)
        }

        // pinch to zoom + tap to focus
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                applyZoom(targetZoom * d.scaleFactor); return true
            }
        })
        overlay.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            if (ev.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress && ev.pointerCount == 1) {
                focusAt(ev.x, ev.y)
            }
            true
        }

        btnShutter.setOnClickListener { onShutter() }
        btnMode.setOnClickListener {
            if (recording != null) { toast("Stop recording first"); return@setOnClickListener }
            videoMode = !videoMode
            btnMode.text = if (videoMode) "VIDEO" else "PHOTO"
            btnShutter.setBackgroundResource(if (videoMode) R.drawable.shutter_video else R.drawable.shutter_photo)
            btnTimer.alpha = if (videoMode) 0.4f else 1f
            bindUseCases()
        }
        btnTimer.setOnClickListener {
            timerMode = (timerMode + 1) % 4
            btnTimer.text = when (timerMode) { 1 -> "⏱ 3s"; 2 -> "⏱ 10s"; 3 -> "✋ STEADY"; else -> "⏱ OFF" }
            if (timerMode == 3) toast("Shutter waits until the phone is still")
        }
        btnLabels.setOnClickListener {
            overlay.showLabels = !overlay.showLabels
            btnLabels.text = if (overlay.showLabels) "AR ON" else "AR OFF"
            overlay.invalidate()
        }
        findViewById<View>(R.id.btnGallery).setOnClickListener { openLast() }
        findViewById<View>(R.id.btnEvUp).setOnClickListener { changeEv(+1) }
        findViewById<View>(R.id.btnEvDown).setOnClickListener { changeEv(-1) }
        findViewById<View>(R.id.btnSky).setOnClickListener {
            refreshSky(); skyPanel.visibility = View.VISIBLE; skyMap.invalidate()
        }
        findViewById<View>(R.id.btnCloseSky).setOnClickListener { skyPanel.visibility = View.GONE }
        skyMap.onSelect = { name ->
            SkyState.selected = if (SkyState.selected == name) null else name
            skyMap.invalidate()
            if (SkyState.selected != null) {
                val o = SkyState.find(name)
                toast("Point the camera where the arrow shows" + if (o != null && o.alt < 0) " (it is below the horizon now)" else "")
                handler.postDelayed({ skyPanel.visibility = View.GONE }, 600)
            }
        }
    }

    private fun maxForMode() = if (videoMode) MAX_VIDEO_ZOOM else MAX_PHOTO_ZOOM

    private fun progressToZoom(p: Int): Float =
        exp(p / 1000.0 * ln(maxForMode().toDouble())).toFloat()

    private fun zoomToProgress(z: Float): Int =
        (ln(z.toDouble()) / ln(maxForMode().toDouble()) * 1000).roundToInt()

    private fun applyZoom(z: Float, updateBar: Boolean = true) {
        targetZoom = z.coerceIn(1f, maxForMode())
        val camZoom = min(targetZoom, max(1f, deviceMaxZoom))
        camera?.cameraControl?.setZoomRatio(camZoom)
        val extra = targetZoom / camZoom
        previewView.scaleX = extra
        previewView.scaleY = extra
        overlay.zoom = targetZoom.toDouble()
        zoomMap.zoom = targetZoom.toDouble()
        zoomMap.visibility = if (targetZoom >= 10f) View.VISIBLE else View.GONE
        if (updateBar && !userSeeking) zoomBar.progress = zoomToProgress(targetZoom)

        zoomText.text = formatZoom(targetZoom)
        zoomDetail.text = if (extra > 1.01f) {
            if (videoMode)
                String.format("Camera %.0fx + viewfinder x%.0f (video file records at %.0fx)", camZoom, extra, camZoom)
            else
                String.format("Camera %.0fx + extra digital crop x%.0f (photo is cropped & enlarged)", camZoom, extra)
        } else String.format("Camera zoom %.1fx  ·  phone max %.0fx", camZoom, deviceMaxZoom)
        overlay.invalidate(); zoomMap.invalidate()
    }

    private fun formatZoom(z: Float): String =
        if (z >= 100) "${z.roundToInt()}x" else String.format("%.1fx", z)

    private fun changeEv(dir: Int) {
        val cam = camera ?: return
        val st = cam.cameraInfo.exposureState
        if (!st.isExposureCompensationSupported) { toast("Exposure control not supported"); return }
        val range = st.exposureCompensationRange
        val step = max(1, (0.5f / st.exposureCompensationStep.toFloat()).roundToInt())
        val idx = (st.exposureCompensationIndex + dir * step).coerceIn(range.lower, range.upper)
        cam.cameraControl.setExposureCompensationIndex(idx)
        val ev = idx * st.exposureCompensationStep.toFloat()
        evText.text = String.format("EV %+.1f", ev)
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        // at heavy digital zoom focus on centre
        val fx = if (targetZoom > deviceMaxZoom) previewView.width / 2f else x
        val fy = if (targetZoom > deviceMaxZoom) previewView.height / 2f else y
        val point = previewView.meteringPointFactory.createPoint(fx, fy)
        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(4, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun openLast() {
        val uri = lastSavedUri
        if (uri == null) { toast("Nothing taken yet - files go to Pictures/ & Movies/SolarSkyCamera"); return }
        try {
            val type = contentResolver.getType(uri) ?: "image/*"
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { toast("No gallery app found") }
    }

    /** runs ~10x per second: refresh labels, zoom-map snapshot, recording time */
    private val ticker = object : Runnable {
        private var lastSnap = 0L
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSkyCompute > 5000) refreshSky()
            // keep a wide snapshot for the zoom-map box while zoomed out
            if (targetZoom <= 4f && now - lastSnap > 1000 && camera != null && SkyState.hasOrientation) {
                lastSnap = now
                previewView.bitmap?.let { full ->
                    val sw = 240
                    val sh = (full.height * sw / full.width.toFloat()).toInt().coerceAtLeast(1)
                    val small = Bitmap.createScaledBitmap(full, sw, sh, true)
                    if (small != full) full.recycle()
                    zoomMap.setSnapshot(small, targetZoom.toDouble())
                }
            }
            if (recording != null) {
                val s = (now - recStart) / 1000
                infoText.text = String.format("● REC %02d:%02d   %s", s / 60, s % 60, qualityLabel)
                infoText.setTextColor(0xFFFF5252.toInt())
            }
            handler.postDelayed(this, 100)
        }
    }

    private fun refreshSky() {
        lastSkyCompute = SystemClock.elapsedRealtime()
        SkyState.objects = Astronomy.computeAll(SkyState.lat, SkyState.lon, System.currentTimeMillis())
        updateDeclination()
        if (::skyMap.isInitialized && skyPanel.visibility == View.VISIBLE) skyMap.invalidate()
    }

    // ---------------------------------------------------------------- camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        try {
            if (videoMode) {
                val qs = QualitySelector.fromOrderedList(
                    listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
                val recorder = Recorder.Builder().setQualitySelector(qs).build()
                val vc = VideoCapture.withOutput(recorder)
                videoCapture = vc
                imageCapture = null
                camera = provider.bindToLifecycle(this, selector, preview, vc)
                qualityLabel = try {
                    @Suppress("DEPRECATION")
                    val supported = QualitySelector.getSupportedQualities(camera!!.cameraInfo)
                    if (supported.contains(Quality.UHD)) "4K UHD video" else "Full HD video"
                } catch (e: Exception) { "Video" }
            } else {
                val rs = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(rs)
                    .setJpegQuality(95)
                    .build()
                imageCapture = ic
                videoCapture = null
                camera = provider.bindToLifecycle(this, selector, preview, ic)
                qualityLabel = ic.resolutionInfo?.resolution?.let { r ->
                    val mp = r.width * r.height / 1_000_000.0
                    val tag = if (max(r.width, r.height) >= 3840) "4K+ " else ""
                    String.format("%sphoto %dx%d (%.0f MP)", tag, r.width, r.height, mp)
                } ?: "Max-quality photo"
            }
        } catch (e: Exception) {
            toast("Camera error: ${e.message}")
            return
        }
        val cam = camera ?: return
        readLensFov(cam.cameraInfo)
        deviceMaxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
        cam.cameraInfo.zoomState.observe(this) { st ->
            if (st.maxZoomRatio != deviceMaxZoom) {
                deviceMaxZoom = st.maxZoomRatio
                applyZoom(targetZoom)
            }
        }
        infoText.setTextColor(0xFFFFFFFF.toInt())
        infoText.text = qualityLabel
        evText.text = "EV 0"
        applyZoom(min(targetZoom, maxForMode()))
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun readLensFov(info: CameraInfo) {
        try {
            val c2 = Camera2CameraInfo.from(info)
            val fl = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val size = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (fl != null && size != null && fl > 0f) {
                val longSide = max(size.width, size.height)
                val fov = Math.toDegrees(2.0 * atan(longSide / 2.0 / fl))
                if (fov in 30.0..110.0) SkyState.baseFovLong = fov
            }
        } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------- shutter

    private fun onShutter() {
        if (videoMode) { toggleRecording(); return }
        pendingCapture?.let {
            handler.removeCallbacks(it); pendingCapture = null; overlay.bigMessage = null
            toast("Cancelled"); return
        }
        when (timerMode) {
            1 -> countdown(3)
            2 -> countdown(10)
            3 -> waitSteady()
            else -> takePhoto()
        }
    }

    private fun countdown(sec: Int) {
        var left = sec
        val r = object : Runnable {
            override fun run() {
                if (left <= 0) { overlay.bigMessage = null; pendingCapture = null; takePhoto(); return }
                overlay.bigMessage = left.toString()
                left--
                handler.postDelayed(this, 1000)
            }
        }
        pendingCapture = r
        handler.post(r)
    }

    private fun waitSteady() {
        val start = SystemClock.elapsedRealtime()
        var steadySince = 0L
        val r = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                if (shake < 0.025f) { if (steadySince == 0L) steadySince = now } else steadySince = 0L
                if ((steadySince != 0L && now - steadySince > 600) || now - start > 8000) {
                    overlay.bigMessage = null; pendingCapture = null; takePhoto(); return
                }
                overlay.bigMessage = "✋"
                handler.postDelayed(this, 50)
            }
        }
        pendingCapture = r
        handler.post(r)
    }

    private fun flashScreen() {
        flash.alpha = 0.8f
        flash.animate().alpha(0f).setDuration(250).start()
    }

    private fun newName(prefix: String) =
        prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val camZoom = min(targetZoom, max(1f, deviceMaxZoom))
        val extra = targetZoom / camZoom
        val name = newName("SolarSky_")
        flashScreen()
        if (extra <= 1.01f) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SolarSkyCamera")
            }
            val opts = ImageCapture.OutputFileOptions.Builder(
                contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ).build()
            ic.takePicture(opts, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    runOnUiThread { lastSavedUri = r.savedUri; toast("Photo saved to Pictures/SolarSkyCamera") }
                }
                override fun onError(e: ImageCaptureException) {
                    runOnUiThread { toast("Photo error: ${e.message}") }
                }
            })
        } else {
            // beyond the phone's own zoom: capture full frame, crop the centre, enlarge
            ic.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val rotation = image.imageInfo.rotationDegrees
                        val buf = image.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        image.close()
                        val bmp = cropCentre(bytes, extra, rotation)
                        val uri = saveBitmap(bmp, name)
                        bmp.recycle()
                        runOnUiThread {
                            lastSavedUri = uri
                            toast(if (uri != null) "Photo saved (${formatZoom(targetZoom)})" else "Could not save")
                        }
                    } catch (e: Throwable) {
                        try { image.close() } catch (_: Throwable) { }
                        runOnUiThread { toast("Photo error: ${e.message}") }
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    runOnUiThread { toast("Photo error: ${e.message}") }
                }
            })
        }
    }

    private fun cropCentre(jpeg: ByteArray, extra: Float, rotation: Int): Bitmap {
        @Suppress("DEPRECATION")
        val dec = BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false)
            ?: throw IllegalStateException("decoder")
        val w = dec.width; val h = dec.height
        val cw = (w / extra).toInt().coerceAtLeast(8)
        val ch = (h / extra).toInt().coerceAtLeast(8)
        val rect = Rect((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2)
        var bmp = dec.decodeRegion(rect, null)
        dec.recycle()
        if (rotation != 0) {
            val m = Matrix(); m.postRotate(rotation.toFloat())
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r != bmp) bmp.recycle()
            bmp = r
        }
        // enlarge so the picture is viewable (no new detail is created)
        val shortSide = min(bmp.width, bmp.height)
        if (shortSide < 1440) {
            val s = 1440f / shortSide
            val r = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
            if (r != bmp) bmp.recycle()
            bmp = r
        }
        return bmp
    }

    private fun saveBitmap(bmp: Bitmap, name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SolarSkyCamera")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return uri
    }

    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        val vc = videoCapture ?: return
        val cur = recording
        if (cur != null) { cur.stop(); recording = null; return }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newName("SolarSky_VID_"))
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/SolarSkyCamera")
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
        var pending = vc.output.prepareRecording(this, opts)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> {
                    recStart = SystemClock.elapsedRealtime()
                    btnShutter.setBackgroundResource(R.drawable.shutter_rec)
                }
                is VideoRecordEvent.Finalize -> {
                    btnShutter.setBackgroundResource(R.drawable.shutter_video)
                    infoText.setTextColor(0xFFFFFFFF.toInt())
                    infoText.text = qualityLabel
                    recording = null
                    if (!ev.hasError()) {
                        lastSavedUri = ev.outputResults.outputUri
                        toast("Video saved to Movies/SolarSkyCamera")
                    } else toast("Video error (${ev.error})")
                }
                else -> {}
            }
        }
    }

    // ---------------------------------------------------------------- location

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val l = lm.getLastKnownLocation(p)
                if (l != null && (best == null || l.time > best.time)) best = l
            } catch (_: Exception) { }
        }
        best?.let { setLocation(it) }
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                setLocation(l)
                lm.removeUpdates(this)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
            } catch (_: Exception) { }
        }
    }

    private fun setLocation(l: Location) {
        SkyState.lat = l.latitude
        SkyState.lon = l.longitude
        SkyState.hasRealLocation = true
        refreshSky()
    }

    private fun updateDeclination() {
        declination = try {
            GeomagneticField(SkyState.lat.toFloat(), SkyState.lon.toFloat(), 0f, System.currentTimeMillis()).declination
        } catch (_: Exception) { 0f }
    }

    // ---------------------------------------------------------------- sensors

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotM, event.values)
                onRotation()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, grav, 0, 3); hasGrav = true
                if (!useRotationVector && hasMag && SensorManager.getRotationMatrix(rotM, null, grav, mag)) onRotation()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, mag, 0, 3); hasMag = true
            }
            Sensor.TYPE_GYROSCOPE -> {
                val v = event.values
                val m = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                shake = shake * 0.85f + m * 0.15f
                overlay.shake = shake
            }
        }
    }

    private fun onRotation() {
        // smoothing: stronger when zoomed in so labels don't jump around
        val a = if (targetZoom > 20) 0.06f else if (targetZoom > 4) 0.12f else 0.25f
        if (!smoothInit) { System.arraycopy(rotM, 0, smoothM, 0, 9); smoothInit = true }
        else for (i in 0 until 9) smoothM[i] += (rotM[i] - smoothM[i]) * a
        val r = smoothM
        // device axes in world (East, North(magnetic), Up)
        val fwd = doubleArrayOf(-r[2].toDouble(), -r[5].toDouble(), -r[8].toDouble())
        val up = doubleArrayOf(r[1].toDouble(), r[4].toDouble(), r[7].toDouble())
        val right = doubleArrayOf(r[0].toDouble(), r[3].toDouble(), r[6].toDouble())
        // magnetic north -> true north
        val d = Math.toRadians(declination.toDouble())
        val cd = cos(d); val sd = sin(d)
        fun rot(v: DoubleArray, out: DoubleArray) {
            val e = v[0] * cd + v[1] * sd
            val n = -v[0] * sd + v[1] * cd
            val len = sqrt(e * e + n * n + v[2] * v[2]).coerceAtLeast(1e-9)
            out[0] = e / len; out[1] = n / len; out[2] = v[2] / len
        }
        rot(fwd, SkyState.fwd); rot(up, SkyState.up); rot(right, SkyState.right)
        SkyState.hasOrientation = true
        overlay.invalidate()
        if (zoomMap.visibility == View.VISIBLE) zoomMap.invalidate()
        if (skyPanel.visibility == View.VISIBLE) skyMap.invalidate()
    }
}
