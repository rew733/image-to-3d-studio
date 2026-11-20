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
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var pickMediaLauncher: ActivityResultLauncher<PickVisualMediaRequest>

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ملتقط الصور الرسمي الحديث في Android 14
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
    }

    private fun processAndSendImageToWebView(uri: Uri) {
        Thread {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (originalBitmap != null) {
                    // تحجيم ذكي للصورة (حد أقصى 1024 بكسل) لتسريع الرفع ومنع استهلاك الذاكرة
                    val maxDim = 1024
                    val scale = minOf(
                        maxDim.toFloat() / originalBitmap.width,
                        maxDim.toFloat() / originalBitmap.height,
                        1.0f
                    )
                    val newWidth = (originalBitmap.width * scale).toInt()
                    val newHeight = (originalBitmap.height * scale).toInt()
                    val resizedBitmap = Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)

                    val outputStream = ByteArrayOutputStream()
                    resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                    val base64String = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                    val dataUrl = "data:image/jpeg;base64,$base64String"

                    runOnUiThread {
                        webView.evaluateJavascript("window.onImageSelectedFromAndroid('$dataUrl');", null)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
