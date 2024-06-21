package com.kelompok5.compvis2
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var imageCapture: ImageCapture
    private lateinit var tts: TextToSpeech

    private val REQUEST_CODE_PERMISSIONS = 101
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

    private val API_KEY_REV = "VLq42KySXR6TPhN45HNkJFkblB3TZOxLAFuvM5qXmSFvXbwM-jorp-ks"
    private val API_KEY = API_KEY_REV.reversed()
    private val GPT_URL = "https://api.openai.com/v1/chat/completions"

    private val TAG = "CV"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)
        Log.d(TAG, "Activity created")

        // Initialize TTS
        tts = TextToSpeech(this, this)
        Log.d(TAG, "TextToSpeech initialized")

        // Check camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            Log.d(TAG, "Camera permissions requested")
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        Log.d(TAG, "Camera executor created")

        val scanButton = findViewById<Button>(R.id.ScanButton)
        scanButton.setOnClickListener {
            try {
                takePhoto()
            } catch (e: Exception) {
                showToast("Failed to take photo: ${e.message}")
                Log.e(TAG, "Failed to take photo: ${e.message}")
            }
        }
        Log.d(TAG, "Scan button initialized")
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        Log.d(TAG, "Camera executor shut down")

        try {
            tts.stop()
            tts.shutdown()
            Log.d(TAG, "TextToSpeech shut down")
        } catch (e: Exception) {
            showToast("Failed to shutdown TTS: ${e.message}")
            Log.e(TAG, "Failed to shutdown TTS: ${e.message}")
        }
    }

    private fun allPermissionsGranted(): Boolean {
        for (permission in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    private fun startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.Camera).surfaceProvider)
            }

            imageCapture = ImageCapture.Builder().build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            try {
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                Log.d(TAG, "Camera bound to lifecycle")
            } catch (exc: Exception) {
                showToast("Use case binding failed: ${exc.message}")
                Log.e(TAG, "Use case binding failed: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(this))
        Log.d(TAG, "Camera provider future listener added")
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: throw IllegalStateException("ImageCapture is not initialized")

        val file = File(externalMediaDirs.first(), "${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

        imageCapture.takePicture(
            outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    recognizeNotation(bitmap, findViewById(R.id.Scanned))
                    Log.d(TAG, "Photo captured and processed")
                }

                override fun onError(exception: ImageCaptureException) {
                    showToast("Capture failed: ${exception.message}")
                    Log.e(TAG, "Capture failed: ${exception.message}")
                }
            })
        Log.d(TAG, "Take photo request initiated")
    }

    private fun recognizeNotation(bitmap: Bitmap, scannedTextView: TextView) {
        val base64Image = bitmapToBase64(bitmap)
        val prompt =    "Notasi uang rupiah berapa yang ada di foto? Jika ada lebih dari satu lembar, berapa jumlahnya? singkat padat jelas contoh Ada selembar 1000 rupiah dan 2000 rupiah, total 3000 rupiah"
        val json = """
        {
            "model": "gpt-4o",
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "text",
                            "text": "$prompt"
                            
                        },
                        {
                            "type": "image_url",
                            "image_url": {
                                "url": "data:image/jpeg;base64,$base64Image"
                            }
                        }
                    ]
                }
            ],
            "max_tokens": 64
        }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .connectTimeout(40, TimeUnit.SECONDS) // Set connection timeout
            .readTimeout(40, TimeUnit.SECONDS) // Set read timeout
            .writeTimeout(40, TimeUnit.SECONDS) // Set write timeout
            .build()

        val body = RequestBody.create("application/json".toMediaTypeOrNull(), json)
        val request = Request.Builder()
            .url(GPT_URL)
            .addHeader("Authorization", "Bearer $API_KEY")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
                showToast("Failed to recognize notation: ${e.message}")
                Log.e(TAG, "Failed to recognize notation: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) throw IOException("Unexpected code $response")

                    val responseBody = response.body?.string()
                    Log.d(TAG, "Recognition response received: $responseBody")

                    runOnUiThread {
                        try {
                            val jsonResponse = JSONObject(responseBody)
                            val choicesArray = jsonResponse.getJSONArray("choices")
                            if (choicesArray.length() > 0) {
                                val choice = choicesArray.getJSONObject(0)
                                val message = choice.getJSONObject("message")
                                val content = message.getString("content")

                                scannedTextView.text = content
                                speak(content)
                                Log.d(TAG, "Text displayed and spoken: $content")
                            } else {
                                showToast("Empty response from GPT-4")
                                Log.e(TAG, "Empty response from GPT-4")
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            showToast("Failed to parse response: ${e.message}")
                            Log.e(TAG, "Failed to parse response: ${e.message}")
                        }
                    }
                }
            }
        })
        Log.d(TAG, "Recognize notation request sent")
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
        val imageBytes = outputStream.toByteArray()
        return android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
    }

    private fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    private fun speak(text: String) {
        val locale = Locale("id", "ID") // Indonesian language and Indonesia locale
        val result = tts.setLanguage(locale)

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "Indonesian language not supported")
            // Handle case where Indonesian language is not supported on this device
            // You can display a message or fallback to default TTS behavior
        } else {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            Log.d(TAG, "Text spoken: $text")
        }
    }


    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            Log.d(TAG, "TextToSpeech initialized successfully")
            // TTS engine is initialized successfully
        } else {
            showToast("Text to speech initialization failed")
            Log.e(TAG, "Text to speech initialization failed")
        }
    }
}