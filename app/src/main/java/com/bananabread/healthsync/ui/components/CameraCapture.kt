package com.bananabread.healthsync.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Composable
fun CameraCapture(
    capturedImages: List<Bitmap>,
    onImageCaptured: (Bitmap) -> Unit,
    onRemoveImage: (Int) -> Unit,
    onFinish: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    
    val imageCapture = remember { 
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build() 
    }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    LaunchedEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                Log.e("CameraCapture", "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Scan Pages (${capturedImages.size})", 
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.weight(1f))
            if (capturedImages.isNotEmpty()) {
                Button(
                    onClick = onFinish,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Done, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Finish")
                }
            }
        }

        // Bottom section: Capture button and preview list
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(bottom = 32.dp, top = 16.dp)
        ) {
            // Captured images list
            AnimatedVisibility(visible = capturedImages.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(capturedImages) { index, bitmap ->
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(8.dp))
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            IconButton(
                                onClick = { onRemoveImage(index) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(24.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            // Capture button
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                FloatingActionButton(
                    onClick = {
                        imageCapture.takePicture(
                            cameraExecutor,
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val bitmap = image.toBitmap()
                                    onImageCaptured(bitmap)
                                    image.close()
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    Log.e("CameraCapture", "Capture failed", exception)
                                }
                            }
                        )
                    },
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    shape = CircleShape,
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(Icons.Default.Camera, contentDescription = "Capture", modifier = Modifier.size(36.dp))
                }
            }
        }
    }
}

private fun ImageProxy.toBitmap(): Bitmap {
    val nv21 = yuv420888ToNv21()
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val outputStream = java.io.ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, outputStream)
    val imageBytes = outputStream.toByteArray()
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}

private fun ImageProxy.yuv420888ToNv21(): ByteArray {
    val yBuffer = planes[0].buffer.toByteArray()
    val uBuffer = planes[1].buffer.toByteArray()
    val vBuffer = planes[2].buffer.toByteArray()

    return ByteArray(yBuffer.size + uBuffer.size + vBuffer.size).apply {
        System.arraycopy(yBuffer, 0, this, 0, yBuffer.size)
        System.arraycopy(vBuffer, 0, this, yBuffer.size, vBuffer.size)
        System.arraycopy(uBuffer, 0, this, yBuffer.size + vBuffer.size, uBuffer.size)
    }
}

private fun ByteBuffer.toByteArray(): ByteArray {
    rewind()
    return ByteArray(remaining()).also { get(it) }
}
