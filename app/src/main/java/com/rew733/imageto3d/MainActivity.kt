package com.rew733.imageto3d

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var pickMediaLauncher: ActivityResultLauncher<PickVisualMediaRequest>
    private var currentImageBytes: ByteArray? = null

    private val segmenter by lazy {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        Segmentation.getClient(options)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pickMediaLauncher = registerForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri: Uri? ->
            if (uri != null) {
                processAndIsolateSubject(uri)
            }
        }

        webView = WebView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
            }

            addJavascriptInterface(WebAppInterface(), "AndroidBridge")
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun openGallery() {
            runOnUiThread {
                pickMediaLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        }

        @JavascriptInterface
        fun startCloud3D() {
            val bytes = currentImageBytes
            if (bytes == null) {
                sendCloudFallback("يرجى اختيار صورة أولاً.")
                return
            }
            Thread {
                runCloudConversion(bytes)
            }.start()
        }
    }

    private fun processAndIsolateSubject(uri: Uri) {
        Thread {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close() ?: return@Thread

                if (originalBitmap == null) return@Thread

                val maxDim = 800
                val scale = minOf(
                    maxDim.toFloat() / originalBitmap.width,
                    maxDim.toFloat() / originalBitmap.height,
                    1.0f
                )
                val newWidth = (originalBitmap.width * scale).toInt()
                val newHeight = (originalBitmap.height * scale).toInt()
                val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)

                val inputImage = InputImage.fromBitmap(scaledBitmap, 0)

                segmenter.process(inputImage)
                    .addOnSuccessListener { segmentationMask ->
                        Thread {
                            try {
                                val maskBuffer = segmentationMask.buffer
                                val maskWidth = segmentationMask.width
                                val maskHeight = segmentationMask.height

                                val pixels = IntArray(maskWidth * maskHeight)
                                scaledBitmap.getPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

                                maskBuffer.rewind()
                                for (i in 0 until maskWidth * maskHeight) {
                                    val foregroundConfidence = maskBuffer.float
                                    if (foregroundConfidence < 0.55f) {
                                        pixels[i] = Color.TRANSPARENT
                                    } else if (foregroundConfidence < 0.85f) {
                                        // تنعيم حواف الجسم لمنع التعرج
                                        val alpha = (((foregroundConfidence - 0.55f) / 0.30f) * 255).toInt().coerceIn(0, 255)
                                        val origColor = pixels[i]
                                        pixels[i] = Color.argb(
                                            alpha,
                                            Color.red(origColor),
                                            Color.green(origColor),
                                            Color.blue(origColor)
                                        )
                                    }
                                }

                                val cutoutBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
                                cutoutBitmap.setPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

                                // حفظ نسخة PNG مفرغة الخلفية
                                val pngStream = ByteArrayOutputStream()
                                cutoutBitmap.compress(Bitmap.CompressFormat.PNG, 100, pngStream)
                                val pngBytes = pngStream.toByteArray()
                                currentImageBytes = pngBytes

                                val base64String = Base64.encodeToString(pngBytes, Base64.NO_WRAP)
                                val dataUrl = "data:image/png;base64,$base64String"

                                runOnUiThread {
                                    webView.evaluateJavascript("window.onImageSelectedFromAndroid('$dataUrl');", null)
                                }
                            } catch (e: Exception) {
                                fallbackSendOriginal(scaledBitmap)
                            }
                        }.start()
                    }
                    .addOnFailureListener {
                        fallbackSendOriginal(scaledBitmap)
                    }
            } catch (e: Exception) {
                sendCloudFallback("تعذر قراءة الصورة: ${e.localizedMessage}")
            }
        }.start()
    }

    private fun fallbackSendOriginal(bitmap: Bitmap) {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        val byteArray = outputStream.toByteArray()
        currentImageBytes = byteArray
        val base64String = Base64.encodeToString(byteArray, Base64.NO_WRAP)
        val dataUrl = "data:image/png;base64,$base64String"
        runOnUiThread {
            webView.evaluateJavascript("window.onImageSelectedFromAndroid('$dataUrl');", null)
        }
    }

    private fun runCloudConversion(imageBytes: ByteArray) {
        val spaces = listOf(
            "https://tencent-hunyuan3d-2.hf.space",
            "https://vast-ai-research-triposg.hf.space",
            "https://stabilityai-triposr.hf.space"
        )

        var lastError = "الخوادم السحابية المجانية مشغولة حالياً."

        for (spaceUrl in spaces) {
            try {
                updateStatus("جاري الاتصال بالسحابة...", spaceUrl.removePrefix("https://"))

                val uploadResult = smartUploadToGradio(spaceUrl, imageBytes)
                val apiPrefix = uploadResult.first
                val uploadedPath = uploadResult.second

                val fileObj = JSONObject().apply {
                    put("path", uploadedPath)
                    put("meta", JSONObject().put("_type", "gradio.FileData"))
                }

                updateStatus("جاري بناء المجسم ثلاثي الأبعاد...", "قد يستغرق ذلك 15 إلى 30 ثانية")

                val modelUrl = if (spaceUrl.contains("triposr")) {
                    val prepData = JSONArray().put(fileObj).put(false).put(0.85)
                    val prepRes = callGradioApi(spaceUrl, apiPrefix, "preprocess", prepData)
                    val genData = JSONArray().put(prepRes.getJSONObject(0)).put(256)
                    val genRes = callGradioApi(spaceUrl, apiPrefix, "generate", genData)
                    extractModelUrl(spaceUrl, apiPrefix, genRes.getJSONObject(0))
                } else {
                    val genData = JSONArray().put(fileObj)
                    val genRes = callGradioApi(spaceUrl, apiPrefix, "generation_all", genData)
                    extractModelUrl(spaceUrl, apiPrefix, genRes.getJSONObject(0))
                }

                runOnUiThread {
                    val safeUrl = JSONObject.quote(modelUrl)
                    webView.evaluateJavascript("window.on3DModelReady($safeUrl);", null)
                }
                return

            } catch (e: Exception) {
                lastError = e.message ?: "خطأ في الاتصال"
            }
        }

        sendCloudFallback("الخوادم السحابية مزدحمة الآن ($lastError)، تم تحويل المجسم المعزول عبر المحرك الفوري.")
    }

    private fun extractModelUrl(spaceUrl: String, apiPrefix: String, obj: JSONObject): String {
        return if (obj.has("url") && !obj.isNull("url")) {
            obj.getString("url")
        } else {
            "$spaceUrl$apiPrefix/file=${obj.getString("path")}"
        }
    }

    private fun smartUploadToGradio(spaceUrl: String, fileBytes: ByteArray): Pair<String, String> {
        val prefixes = listOf("/gradio_api", "")
        var lastCode = 404

        for (prefix in prefixes) {
            try {
                val boundary = "Boundary-${UUID.randomUUID()}"
                val url = URL("$spaceUrl$prefix/upload")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
                }

                DataOutputStream(conn.outputStream).use { out ->
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"files\"; filename=\"subject.png\"\r\n")
                    out.writeBytes("Content-Type: image/png\r\n\r\n")
                    out.write(fileBytes)
                    out.writeBytes("\r\n--$boundary--\r\n")
                    out.flush()
                }

                lastCode = conn.responseCode
                if (lastCode in 200..299) {
                    val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    val jsonArray = JSONArray(response)
                    return Pair(prefix, jsonArray.getString(0))
                }
            } catch (_: Exception) {
            }
        }
        throw Exception("رمز الخادم: $lastCode")
    }

    private fun callGradioApi(spaceUrl: String, apiPrefix: String, endpoint: String, dataArray: JSONArray): JSONArray {
        val postUrl = URL("$spaceUrl$apiPrefix/call/$endpoint")
        val postConn = (postUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20000
            readTimeout = 20000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
        }

        val payload = JSONObject().put("data", dataArray).toString()
        postConn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

        if (postConn.responseCode !in 200..299) {
            throw Exception("فشل الطلب ($endpoint: ${postConn.responseCode})")
        }

        val postResp = BufferedReader(InputStreamReader(postConn.inputStream)).use { it.readText() }
        val eventId = JSONObject(postResp).getString("event_id")

        val streamUrl = URL("$spaceUrl$apiPrefix/call/$endpoint/$eventId")
        val streamConn = (streamUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 25000
            readTimeout = 90000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
        }

        BufferedReader(InputStreamReader(streamConn.inputStream)).use { reader ->
            var line: String?
            var isComplete = false
            var isError = false
            while (reader.readLine().also { line = it } != null) {
                val current = line ?: continue
                if (current.startsWith("event: error")) {
                    isError = true
                } else if (current.startsWith("event: complete")) {
                    isComplete = true
                } else if (current.startsWith("data: ")) {
                    val dataStr = current.removePrefix("data: ")
                    if (isError) throw Exception("انشغال الخادم")
                    if (isComplete) return JSONArray(dataStr)
                }
            }
        }
        throw Exception("انتهت المهلة")
    }

    private fun updateStatus(title: String, sub: String) {
        runOnUiThread {
            val t = JSONObject.quote(title)
            val s = JSONObject.quote(sub)
            webView.evaluateJavascript("window.updateConversionStatus($t, $s);", null)
        }
    }

    private fun sendCloudFallback(reason: String) {
        runOnUiThread {
            val r = JSONObject.quote(reason)
            webView.evaluateJavascript("window.activateInstantLocal3D($r);", null)
        }
    }

    override fun onDestroy() {
        segmenter.close()
        webView.destroy()
        super.onDestroy()
    }
}
