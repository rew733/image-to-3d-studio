package com.rew733.imageto3d

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pickMediaLauncher = registerForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri: Uri? ->
            if (uri != null) {
                processAndSendImageToWebView(uri)
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
        fun start3DConversion() {
            val bytes = currentImageBytes
            if (bytes == null) {
                sendError("يرجى اختيار صورة أولاً.")
                return
            }
            Thread {
                runCloudConversion(bytes)
            }.start()
        }
    }

    private fun processAndSendImageToWebView(uri: Uri) {
        Thread {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (originalBitmap != null) {
                    val maxDim = 800
                    val scale = minOf(
                        maxDim.toFloat() / originalBitmap.width,
                        maxDim.toFloat() / originalBitmap.height,
                        1.0f
                    )
                    val newWidth = (originalBitmap.width * scale).toInt()
                    val newHeight = (originalBitmap.height * scale).toInt()
                    val resizedBitmap = Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)

                    val outputStream = ByteArrayOutputStream()
                    resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 88, outputStream)
                    val byteArray = outputStream.toByteArray()
                    currentImageBytes = byteArray

                    val base64String = Base64.encodeToString(byteArray, Base64.NO_WRAP)
                    val dataUrl = "data:image/jpeg;base64,$base64String"

                    runOnUiThread {
                        webView.evaluateJavascript("window.onImageSelectedFromAndroid('$dataUrl');", null)
                    }
                }
            } catch (e: Exception) {
                sendError("تعذر قراءة الصورة: ${e.localizedMessage}")
            }
        }.start()
    }

    private fun runCloudConversion(imageBytes: ByteArray) {
        val spaces = listOf(
            "https://stabilityai-triposr.hf.space",
            "https://hysts-triposr.hf.space"
        )

        var lastError = "تعذر الاتصال بالخادم السحابي."

        for (spaceUrl in spaces) {
            try {
                updateStatus("جاري رفع الصورة إلى الخادم السحابي...", "الاتصال بـ ${spaceUrl.removePrefix("https://")}")

                val uploadedPath = uploadFileToGradio(spaceUrl, imageBytes)
                val fileObj = JSONObject().apply {
                    put("path", uploadedPath)
                    put("meta", JSONObject().put("_type", "gradio.FileData"))
                }

                updateStatus("جاري عزل العنصر من الخلفية...", "تحليل حدود الجسم بدقة")
                val prepData = JSONArray().apply {
                    put(fileObj)
                    put(true)
                    put(0.85)
                }
                val prepResult = callGradioApi(spaceUrl, "preprocess", prepData)
                val processedFileObj = prepResult.getJSONObject(0)

                updateStatus("جاري بناء المجسم ثلاثي الأبعاد...", "قد يستغرق ذلك 15 إلى 25 ثانية")
                val genData = JSONArray().apply {
                    put(processedFileObj)
                    put(256)
                }
                val genResult = callGradioApi(spaceUrl, "generate", genData)
                val modelObj = genResult.getJSONObject(0)

                val modelUrl = if (modelObj.has("url") && !modelObj.isNull("url")) {
                    modelObj.getString("url")
                } else {
                    "$spaceUrl/gradio_api/file=${modelObj.getString("path")}"
                }

                runOnUiThread {
                    val safeUrl = JSONObject.quote(modelUrl)
                    webView.evaluateJavascript("window.on3DModelReady($safeUrl);", null)
                }
                return

            } catch (e: Exception) {
                lastError = e.message ?: "خطأ غير معروف"
            }
        }

        sendError("فشلت المحاولة على جميع الخوادم المتاحة:\n$lastError")
    }

    private fun uploadFileToGradio(spaceUrl: String, fileBytes: ByteArray): String {
        val boundary = "Boundary-${UUID.randomUUID()}"
        val url = URL("$spaceUrl/gradio_api/upload")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 25000
            readTimeout = 25000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
        }

        DataOutputStream(conn.outputStream).use { out ->
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"files\"; filename=\"image.jpg\"\r\n")
            out.writeBytes("Content-Type: image/jpeg\r\n\r\n")
            out.write(fileBytes)
            out.writeBytes("\r\n--$boundary--\r\n")
            out.flush()
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            throw Exception("رمز استجابة الرفع: $code")
        }

        val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        val jsonArray = JSONArray(response)
        return jsonArray.getString(0)
    }

    private fun callGradioApi(spaceUrl: String, endpoint: String, dataArray: JSONArray): JSONArray {
        val postUrl = URL("$spaceUrl/gradio_api/call/$endpoint")
        val postConn = (postUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 25000
            readTimeout = 25000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
        }

        val payload = JSONObject().put("data", dataArray).toString()
        postConn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

        if (postConn.responseCode !in 200..299) {
            throw Exception("فشل طلب $endpoint (رمز ${postConn.responseCode})")
        }

        val postResp = BufferedReader(InputStreamReader(postConn.inputStream)).use { it.readText() }
        val eventId = JSONObject(postResp).getString("event_id")

        val streamUrl = URL("$spaceUrl/gradio_api/call/$endpoint/$eventId")
        val streamConn = (streamUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30000
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
                    if (isError) {
                        throw Exception("الخادم السحابي مشغول حالياً ($dataStr)")
                    }
                    if (isComplete) {
                        return JSONArray(dataStr)
                    }
                }
            }
        }
        throw Exception("انتهى الاتصال دون استلام نتيجة مكتملة من $endpoint")
    }

    private fun updateStatus(title: String, sub: String) {
        runOnUiThread {
            val t = JSONObject.quote(title)
            val s = JSONObject.quote(sub)
            webView.evaluateJavascript("window.updateConversionStatus($t, $s);", null)
        }
    }

    private fun sendError(msg: String) {
        runOnUiThread {
            val m = JSONObject.quote(msg)
            webView.evaluateJavascript("window.onConversionError($m);", null)
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
