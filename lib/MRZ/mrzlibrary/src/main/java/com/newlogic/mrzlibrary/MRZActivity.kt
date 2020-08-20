package com.newlogic.mrzlibrary

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CameraManager
import android.media.Image
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.android.synthetic.main.activity_mrz.*
import kotlinx.android.synthetic.main.activity_mrz.view.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.IllegalArgumentException
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.abs

//typealias LumaListener = (luma: String) -> Unit


class MRZActivity : AppCompatActivity(), View.OnClickListener {

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var tessImageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var flashButton: View? = null

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    private var tessBaseAPI: TessBaseAPI? = null

    private var onAnalyzerResult: (AnalyzerType, String) -> Unit = { a, b -> getAnalyzerResult(a, b) }
    private var onAnalyzerStat: (AnalyzerType, Long, Long) -> Unit = { a, b: Long, c: Long -> getAnalyzerStat(a, b, c) }

    private var startScanTime: Long = 0
    private var tessStartScanTime: Long = 0

    enum class AnalyzerType {
        MLKIT,
        TESSERACT,
        BARCODE
    }

    private object UIState {
        var mlkit: Boolean? = false
        var tesseract: Boolean? = false
        var debug: Boolean? = false
    }

    private val clickThreshold = 5
    private var x = 0f
    private var y = 0f

    data class MRZResult (
        val imagePath: String?,
        val code: String?,
        val code1: Short?,
        val code2: Short?,
        val dateOfBirth: String?,
        val documentNumber: String?,
        val expirationDate: String?,
        val format: String?,
        val givenNames: String?,
        val issuingCountry: String?,
        val nationality: String?,
        val sex: String?,
        val surname: String?,
        var mrz: String?
    )

    data class barcodeResult (
        val imagePath: String?,
        val value: String?
    )

    private fun getAnalyzerResult(analyzerType: AnalyzerType, result: String): Unit {
        runOnUiThread {
            when (analyzerType) {
                AnalyzerType.MLKIT -> {
                    Log.d(TAG, "Success from MLKit")
                    mlkitCheckbox.isChecked = false
                    onMlkitCheckboxClicked(mlkitCheckbox)
                    mlkitText.text = result
                }
                AnalyzerType.TESSERACT -> {
                    Log.d(TAG, "Success from Tesseract")
                    tesseractCheckbox.isChecked = false
                    onTesseractCheckboxClicked(tesseractCheckbox)
                    tesseractText.text = result
                }
                AnalyzerType.BARCODE -> {
                    Log.d(TAG, "Success from Barcode")
                    Log.d(TAG, "value: $result")
                }
            }
            val data = Intent()
            data.putExtra(MRZ_RESULT, result)
            setResult(Activity.RESULT_OK, data)
            finish()
        }
    }

    private fun getAnalyzerStat(analyzerType: AnalyzerType, startTime: Long, endTime: Long): Unit {
        runOnUiThread {
            var analyzerTime = endTime - startTime
            if (analyzerType == AnalyzerType.MLKIT) {
                mlkitMS.text = "Frame processing time: ${analyzerTime} ms"
                var scanTime =
                    ((System.currentTimeMillis().toDouble() - startScanTime.toDouble()) / 1000)
                mlkitTime.text = "Total scan time: ${scanTime} s"
            } else if (analyzerType == AnalyzerType.TESSERACT) {
                tesseractMS.text = "Frame processing time: ${analyzerTime} ms"
                var scanTime =
                    ((System.currentTimeMillis().toDouble() - tessStartScanTime.toDouble()) / 1000)
                tesseractTime.text = "Total scan time: ${scanTime} s"
            }
        }
    }

    private fun getUIState(): UIState {
        return UIState
    }

    private class MLKitAnalyzer(
        private var onResult: ((AnalyzerType, String) -> Unit)?,
        private var getUIState: (() -> UIState)?,
        private var onStat: ((AnalyzerType, Long, Long) -> Unit)?,
        private var tessBaseAPI: TessBaseAPI?,
        private var debugPath: String?
    ) : ImageAnalysis.Analyzer {

        private var barcodeBusy: Boolean = false
        private var mlkitBusy: Boolean = false
        private var tesseractBusy: Boolean = false

        fun Image.toBitmap(rotation: Int = 0): Bitmap {
            val yBuffer = planes[0].buffer // Y
            val uBuffer = planes[1].buffer // U
            val vBuffer = planes[2].buffer // V

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            // U and V are swapped
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
            val out = ByteArrayOutputStream()

            var rect:Rect =  Rect()
            if (rotation == 90 || rotation == 270) {
                rect.left = this.width / 4
                rect.top = 0
                rect.right = this.width - rect.left
                rect.bottom = this.height
            } else {
                rect.left = 0
                rect.top = this.height / 4
                rect.right = this.width
                rect.bottom =  this.height - rect.top
            }

            Log.d(TAG, "Image ${this.width}x${this.height}, crop to: ${rect.left},${rect.top},${rect.right},${rect.bottom}")

            yuvImage.compressToJpeg(rect, 100, out) // Ugly but it works
            //yuvImage.compressToJpeg(Rect(270, 20, 370, 460), 100, out)
            val imageBytes = out.toByteArray()
            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        }

        fun Bitmap.cacheImageToLocal(localPath: String, rotation: Int = 0, quality: Int = 100) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val b = Bitmap.createBitmap(this, 0, 0, this.width, this.height, matrix, true);
            val file = File(localPath)
            file.createNewFile()
            val ostream = FileOutputStream(file)
            b.compress(Bitmap.CompressFormat.JPEG, quality, ostream)
            ostream.flush()
            ostream.close()
        }

        fun getConnectionType(context: Context): Int {
            var result = 0 // Returns connection type. 0: none; 1: mobile data; 2: wifi
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm?.run {
                    cm.getNetworkCapabilities(cm.activeNetwork)?.run {
                        when {
                            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                                result = 2
                            }
                            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                                result = 1
                            }
                            hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> {
                                result = 3
                            }
                        }
                    }
                }
            }
            return result
        }
        @SuppressLint("UnsafeExperimentalUsageError")
        override fun analyze(imageProxy: ImageProxy) {

//                // DEBUG: Write images to storage
//                val fname = "MRZ-TESS-$startTime.jpg"
//                val file = File(debugPath, fname)
//                if (file.exists()) file.delete()
//                try {
//                    val out = FileOutputStream(file)
//                    b.compress(Bitmap.CompressFormat.JPEG, 90, out)
//                    out.flush()
//                    out.close()
//                } catch (e: java.lang.Exception) {
//                    e.printStackTrace()
//                }
//                Log.d(TAG, "Saved image: $debugPath/$fname")

            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val rot = imageProxy.imageInfo.rotationDegrees
                val bf = mediaImage.toBitmap(rot)
                val b = if (rot == 90 || rot == 270) Bitmap.createBitmap(bf, bf.width / 2, 0, bf.width / 2, bf.height)
                        else Bitmap.createBitmap(bf, 0, bf.height / 2, bf.width, bf.height / 2)
                Log.d(
                    "$TAG",
                    "Bitmap: (${mediaImage.width}, ${mediaImage.height}) Cropped: (${b.width}, ${b.height}), Rotation: ${imageProxy.imageInfo.rotationDegrees}"
                )

                //barcode and pdf417
                if (!barcodeBusy && ((mode == "pdf417") || (mode == "barcode"))) {
                    barcodeBusy = true
                    Log.d("$TAG/MLKit", "barcode: mode is $mode")
                    val start = System.currentTimeMillis()
                    var options: BarcodeScannerOptions = BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                        .build()
                    when (mode) {
                        "pdf417" -> {
                            options = BarcodeScannerOptions.Builder()
                                .setBarcodeFormats(Barcode.FORMAT_PDF417)
                                .build()
                        }
                        "barcode" -> {
                            options = BarcodeScannerOptions.Builder()
                                .setBarcodeFormats(
                                    Barcode.FORMAT_CODE_128,
                                    Barcode.FORMAT_CODE_39,
                                    Barcode.FORMAT_CODE_93,
                                    Barcode.FORMAT_CODABAR,
                                    Barcode.FORMAT_DATA_MATRIX,
                                    Barcode.FORMAT_EAN_13,
                                    Barcode.FORMAT_EAN_8,
                                    Barcode.FORMAT_ITF,
                                    Barcode.FORMAT_QR_CODE,
                                    Barcode.FORMAT_UPC_A,
                                    Barcode.FORMAT_UPC_E,
                                    Barcode.FORMAT_AZTEC
                                )
                                .build()
                        }
                    }
                    val image = InputImage.fromBitmap(bf, imageProxy.imageInfo.rotationDegrees)
                    val scanner = BarcodeScanning.getClient(options)
                    Log.d("$TAG/MLKit", "barcode: process")
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            val timeRequired = System.currentTimeMillis() - start
                            var rawValue: String
                            Log.d("$TAG/MLKit", "barcode: success: $timeRequired ms")
                            if (barcodes.isNotEmpty()) {
                                //                                val bounds = barcode.boundingBox
                                //                                val corners = barcode.cornerPoints
                                rawValue = barcodes[0].rawValue!!
                                //                                val valueType = barcode.valueType
                                val imageCachePathFile = "${context.cacheDir}/outputImage.jpg"
                                bf.cacheImageToLocal(
                                    imageCachePathFile,
                                    imageProxy.imageInfo.rotationDegrees
                                )
                                var gson = Gson()
                                var jsonString = gson.toJson(barcodeResult(
                                    imageCachePathFile,
                                    rawValue))
                                onResult?.invoke(AnalyzerType.BARCODE, jsonString)
                            } else {
                                Log.d("$TAG/MLKit", "barcode: nothing detected")
                            }
                            barcodeBusy = false
                        }
                        .addOnFailureListener { e ->
                            Log.d("$TAG/MLKit", "barcode: failure: ${e.message}")
                            barcodeBusy = false
                        }
                }
                //MRZ
                var uiState = getUIState?.let { it() }
                if (uiState?.mlkit!! && !mlkitBusy) {
                    mlkitBusy = true
                    var mlStartTime = System.currentTimeMillis()

                    val image = InputImage.fromBitmap(b, imageProxy.imageInfo.rotationDegrees)


                    // Pass image to an ML Kit Vision API
                    val recognizer = TextRecognition.getClient()
                    Log.d("$TAG/MLKit", "TextRecognition: process")
                    val start = System.currentTimeMillis()


//                    await(
                        recognizer.process(image)
                            .addOnSuccessListener { visionText ->
                                modelLayoutView.visibility = View.INVISIBLE
                                val timeRequired = System.currentTimeMillis() - start

                                Log.d("$TAG/MLKit", "TextRecognition: success: $timeRequired ms")
                                var rawFullRead = ""
                                val blocks = visionText.textBlocks
                                for (i in blocks.indices) {
                                    val lines = blocks[i].lines
                                    for (j in lines.indices) {
                                        if (lines[j].text.contains('<')) {
                                            rawFullRead += lines[j].text + "\n"
                                        }
                                    }
                                }
                                rectangle!!.isSelected = rawFullRead != ""

                                try {
                                    Log.d(
                                        "$TAG/MLKit",
                                        "Before cleaner: [${URLEncoder.encode(rawFullRead, "UTF-8")
                                            .replace("%3C", "<").replace("%0A", "↩")}]"
                                    )
                                    var mrz = MRZCleaner.clean(rawFullRead)
                                    Log.d(
                                        "$TAG/MLKit",
                                        "After cleaner = [${URLEncoder.encode(mrz, "UTF-8")
                                            .replace("%3C", "<").replace("%0A", "↩")}]"
                                    )
                                    var record = MRZCleaner.parseAndClean(mrz)
//                                    onResult?.invoke(AnalyzerType.MLKIT, record.toString())

                                    val imageCachePathFile = "${context.cacheDir}/outputImage.jpg"
                                    bf.cacheImageToLocal(imageCachePathFile, imageProxy.imageInfo.rotationDegrees)

                                    // record to json
                                    var gson = Gson()
                                    var jsonString = gson.toJson(MRZResult(
                                        imageCachePathFile,
                                        record.code.toString(),
                                        record.code1.toShort(),
                                        record.code2.toShort(),
                                        record.dateOfBirth?.toString()?.replace(Regex("[{}]"), ""),
                                        record.documentNumber.toString(),
                                        record.expirationDate?.toString()?.replace(Regex("[{}]"), ""),
                                        record.format.toString(),
                                        record.givenNames,
                                        record.issuingCountry,
                                        record.nationality,
                                        record.sex.toString(),
                                        record.surname,
                                        record.toMrz()
                                    ))
                                    onResult?.invoke(AnalyzerType.MLKIT, jsonString)
                                } catch (e: Exception) { // MrzParseException, IllegalArgumentException
                                    Log.d("$TAG/MLKit", e.toString())
                                }
                                onStat?.invoke(
                                    AnalyzerType.MLKIT,
                                    mlStartTime,
                                    System.currentTimeMillis()
                                )
                                mlkitBusy = false
                            }
                            .addOnFailureListener { e ->
                                Log.d("$TAG/MLKit", "TextRecognition: failure: ${e.message}")
                                if (getConnectionType(context) == 0) {
                                    modelLayoutView.modelText.text = context.getString(R.string.ConnectionText)
                                } else {
                                    modelLayoutView.modelText.text = context.getString(R.string.ModelText)
                                }
                                modelLayoutView.visibility = View.VISIBLE
                                onStat?.invoke(
                                    AnalyzerType.MLKIT,
                                    mlStartTime,
                                    System.currentTimeMillis()
                                )
                                mlkitBusy = false
                            }
//                    )
                }
                if (uiState.tesseract!! && !tesseractBusy) {
                    tesseractBusy = true
                    var tessStartTime = System.currentTimeMillis()

                    thread(start = true) {
                        // Tesseract
                        val matrix = Matrix()
                        matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                        val rotatedBitmap =
                            Bitmap.createBitmap(b, 0, 0, b.width, b.height, matrix, true)
                        tessBaseAPI!!.setImage(rotatedBitmap)
                        var tessResult = tessBaseAPI!!.utF8Text

                        try {
                            Log.d(
                                "$TAG/Tesseract",
                                "Before cleaner: [${URLEncoder.encode(tessResult, "UTF-8")
                                    .replace("%3C", "<").replace("%0A", "↩")}]"
                            )
                            var mrz = MRZCleaner.clean(tessResult)
                            Log.d(
                                "$TAG/Tesseract",
                                "After cleaner = [${URLEncoder.encode(mrz, "UTF-8")
                                    .replace("%3C", "<")
                                    .replace("%0A", "↩")}]"
                            )
                            var record = MRZCleaner.parseAndClean(mrz)
//                            onResult?.invoke(AnalyzerType.TESSERACT, record.toString())
                            // record to json
                            var gson = Gson()
                            var jsonString = gson.toJson(MRZResult(
                                null,
                                record.code.toString(),
                                record.code1.toShort(),
                                record.code2.toShort(),
                                record.dateOfBirth.toString().replace(Regex("[{}]"), ""),
                                record.documentNumber.toString(),
                                record.expirationDate.toString().replace(Regex("[{}]"), ""),
                                record.format.toString(),
                                record.givenNames,
                                record.issuingCountry,
                                record.nationality,
                                record.sex.toString(),
                                record.surname,
                                record.toMrz()
                            ))
                            onResult?.invoke(AnalyzerType.TESSERACT, jsonString)
                        } catch (e: Exception) { // MrzParseException, IllegalArgumentException
                            Log.d("$TAG/Tesseract", e.toString())
                        }
                        onStat?.invoke(
                            AnalyzerType.TESSERACT,
                            tessStartTime,
                            System.currentTimeMillis()
                        )
                        tesseractBusy = false
                    }
                }
            }
            imageProxy.close()
        }
    }


    private fun openSettingsApp() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", packageName, null)
        intent.data = uri
        startActivity(intent)
    }


    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                val snackbar: Snackbar = Snackbar.make(
                    CoordinatorLayoutView,
                    R.string.required_perms_not_given, Snackbar.LENGTH_INDEFINITE
                )
                snackbar.setAction(R.string.settings, View.OnClickListener {
                    openSettingsApp()
                })
                snackbar.show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mrz)
        modelLayoutView = findViewById(R.id.modelLayout)
        CoordinatorLayoutView =  findViewById(R.id.CoordinatorLayout)
        flashButton = findViewById<View>(R.id.flash_button)
        Companion.rectangle = findViewById<View>(R.id.rectimage)
        findViewById<View>(R.id.close_button).setOnClickListener(this)
        flashButton?.setOnClickListener(this)
        context = applicationContext
        val intent = intent
        mode = intent.getStringExtra("mode")
        when (mode) {
            "mrz" -> {
                mlkitCheckbox.isChecked = true
            }
            "tesseract" -> {
                tesseractCheckbox.isChecked = true
            }
            "pdf417" -> {

            }
            "barcode" -> {
            }
        }

        UIState.mlkit = mlkitCheckbox.isChecked
        UIState.tesseract = tesseractCheckbox.isChecked

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

//        FirebaseApp.initializeApp(this)
//        val firebaseModelManager: FirebaseModelManager = FirebaseModelManager.getInstance()
//        firebaseModelManager.getDownloadedModels(FirebaseRemoteModel::class.java).addOnSuccessListener { models ->
//            Toast.makeText(this, "fini", Toast.LENGTH_SHORT).show()
//        }

        outputDirectory = getOutputDirectory()

        cameraExecutor = Executors.newSingleThreadExecutor()



        // TODO: This will not work if the file is updated in the APK, need to handle versioning
        if (!FileUtils.tesseractPathExists(this)) {
            if (FileUtils.createTesseractSubDir(this)) {
                FileUtils.copyFilesToSdCard(this);
            } else {
                //Timber.e(this.getClass().getSimpleName(), "Unknown file error. Cannot create subdirectory tessdata");
                Log.e(TAG, "Unknown file error. Cannot create subdirectory tessdata");
            }
        }



        tessBaseAPI = TessBaseAPI()
        val extDirPath: String = getExternalFilesDir(null)!!.absolutePath
        Log.d(TAG, "path: ${extDirPath}")
        tessBaseAPI!!.init(extDirPath, "ocrb_int", TessBaseAPI.OEM_DEFAULT)
        tessBaseAPI!!.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
    }

    private fun startCamera() {

        this.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            preview = Preview.Builder()
//                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()

            var size = Size(480, 640)
            if (mode == "pdf417") size = Size(1080, 1920)
            imageAnalyzer = ImageAnalysis.Builder()
//                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetResolution(size)
//                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    var mrzAnalyzer = MLKitAnalyzer(onAnalyzerResult, ::getUIState, onAnalyzerStat, tessBaseAPI, getExternalFilesDir(null)!!.absolutePath + "/Debug")
                    it.setAnalyzer(cameraExecutor, mrzAnalyzer)
                }

//            tessImageAnalyzer = ImageAnalysis.Builder()
////                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
//                //.setTargetResolution(Size(960, 720))
////                .setTargetResolution(Size(640, 480))
////                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
//                .build()
//                .also {
//                    var tess_analyzer = TesseractAnalyzer(tessOnAnalyzerResult, tessOnAnalyzerGetState, tessOnAnalyzerStat, tessBaseAPI, Environment.getExternalStorageDirectory()!!.absolutePath + "/Debug")
//                    it.setAnalyzer(cameraExecutor, tess_analyzer)
//                }

            // Select back camera
            val cameraSelector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
//                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer, tessImageAnalyzer)
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
                //camera!!.cameraControl.enableTorch(true)

                preview?.setSurfaceProvider(viewFinder.createSurfaceProvider())

                Log.d(TAG, "Measured size: ${viewFinder.width}x${viewFinder.height}")

                startScanTime = System.currentTimeMillis()
                tessStartScanTime = startScanTime
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    companion object {
        const val TAG = "CameraXBasic"
        const val MRZ_RESULT = "MrzResult"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        private lateinit var modelLayoutView: View
        private lateinit var CoordinatorLayoutView: View
        private lateinit var context: Context
        private var mode: String? = null
        private var rectangle: View? = null
    }

    fun onMlkitCheckboxClicked(view: View) {
        if (view is Switch) {
            val checked: Boolean = view.isChecked
            UIState.mlkit = checked
            Log.d(TAG, "UIState.mlkit: ${UIState.mlkit}")
            if (checked) {
                startScanTime = System.currentTimeMillis()
                mlkitText.text = ""
                mlkitMS.text = ""
                mlkitTime.text = ""
            }
            // TODO

        }
    }

    fun onTesseractCheckboxClicked(view: View) {
        if (view is Switch) {
            val checked: Boolean = view.isChecked
            UIState.tesseract = checked
            Log.d(TAG, "UIState.tesseract: ${UIState.tesseract}")
            if (checked) {
                tessStartScanTime = System.currentTimeMillis()
                tesseractText.text = ""
                tesseractMS.text = ""
                tesseractTime.text = ""
            }
            // TODO
        }
    }

    override fun onClick(view: View) {
        val id = view.id
        Log.d(TAG, "view: $id")
        if (id == R.id.close_button) {
            onBackPressed()
        } else if (id == R.id.flash_button) {
            if (flashButton!!.isSelected) {
                flashButton!!.isSelected = false
                camera?.cameraControl?.enableTorch(false)
            } else {
                flashButton!!.isSelected = true
                camera?.cameraControl?.enableTorch(true)
            }
        }
    }

    private fun isAClick(
        startX: Float,
        endX: Float,
        startY: Float,
        endY: Float
    ): Boolean {
        val differenceX = abs(startX - endX)
        val differenceY = abs(startY - endY)
        return !(differenceX > clickThreshold || differenceY > clickThreshold)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val minDistance = 600
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                y = event.y
                x = event.x
            }
            MotionEvent.ACTION_UP -> {
                if (isAClick(x, event.x, y, event.y)) {
                    val factory: MeteringPointFactory = SurfaceOrientedMeteringPointFactory(
                        viewFinder.width.toFloat(), viewFinder.height.toFloat()
                    )
                    val autoFocusPoint = factory.createPoint(event.x, event.y)
                    try {
                        camera!!.cameraControl.startFocusAndMetering(
                            FocusMeteringAction.Builder(
                                autoFocusPoint,
                                FocusMeteringAction.FLAG_AF
                            ).apply {
                                //focus only when the user tap the preview
                                disableAutoCancel()
                            }.build()
                        )
                    } catch (e: CameraInfoUnavailableException) {
                        Log.d("ERROR", "cannot access camera", e)
                    }
                } else {
                    val deltaY = event.y - y
                    if (deltaY < -minDistance) {
                        // Toast.makeText(this, "bottom2up swipe: $y1, $y2 -> $deltaY", Toast.LENGTH_SHORT).show()
                        debugLayout.visibility = View.VISIBLE
                    } else if (deltaY > minDistance) {
                        debugLayout.visibility = View.INVISIBLE
                    }
                }
            }

        }
        return super.onTouchEvent(event)
    }
}