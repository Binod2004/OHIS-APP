package com.example.ohis;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.content.res.ColorStateList;
import android.graphics.Color;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.cloudinary.android.MediaManager;
import com.cloudinary.android.callback.ErrorInfo;
import com.cloudinary.android.callback.UploadCallback;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private PreviewView viewFinder;
    private ImageView previewImage;
    private Button btnCapture, btnGallery, btnUpload, btnFlash;
    private ProgressBar uploadProgress;
    private Uri imageUriToUpload;
    private boolean isCloudinaryInitialized = false;
    private boolean isFlashOn = false;

    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private Camera camera;

    // Gallery picker launcher
    private final ActivityResultLauncher<String> galleryLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    showPreviewImage(uri);
                }
            }
    );

    // Camera permission launcher
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initCloudinary();

        viewFinder = findViewById(R.id.viewFinder);
        previewImage = findViewById(R.id.previewImage);
        btnCapture = findViewById(R.id.btnCapture);
        btnGallery = findViewById(R.id.btnGallery);
        btnUpload = findViewById(R.id.btnUpload);
        btnFlash = findViewById(R.id.btnFlash);
        uploadProgress = findViewById(R.id.uploadProgress);

        Button btnAnalytics = findViewById(R.id.btnAnalytics);
        Button btnHistory = findViewById(R.id.btnHistory);

        cameraExecutor = Executors.newSingleThreadExecutor();

        // Check permission and start camera feed immediately
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }

        btnCapture.setOnClickListener(v -> {
            if (imageUriToUpload == null) {
                takePhoto();
            } else {
                resetToCameraPreview();
            }
        });

        btnGallery.setOnClickListener(v -> galleryLauncher.launch("image/*"));

        btnFlash.setOnClickListener(v -> {
            if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
                isFlashOn = !isFlashOn;
                camera.getCameraControl().enableTorch(isFlashOn);
                btnFlash.setText(isFlashOn ? "🔦 Flash On" : "🔦 Flash Off");
            } else {
                Toast.makeText(this, "No flash available", Toast.LENGTH_SHORT).show();
            }
        });

        btnUpload.setOnClickListener(v -> {
            if (imageUriToUpload != null) {
                uploadToCloudinary(imageUriToUpload);
            }
        });

        btnAnalytics.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, AnalyticsActivity.class)));
        btnHistory.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, HistoryActivity.class)));
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder().build();
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Use case binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        if (imageCapture == null) return;

        File photoFile = new File(getCacheDir(), "capture_" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                Uri savedUri = Uri.fromFile(photoFile);
                runOnUiThread(() -> showPreviewImage(savedUri));
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "Photo capture failed", exception);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Capture failed", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void showPreviewImage(Uri uri) {
        imageUriToUpload = uri;
        previewImage.setImageURI(uri);
        previewImage.setVisibility(View.VISIBLE);
        viewFinder.setVisibility(View.INVISIBLE); // Hide live camera

        // Toggle buttons: morph Capture to Re-Capture
        btnCapture.setText("Re-Capture");
        btnCapture.setTextSize(16);
        btnCapture.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#D32F2F"))); // Red

        btnGallery.setVisibility(View.GONE);
        btnFlash.setVisibility(View.GONE);

        btnUpload.setVisibility(View.VISIBLE);
        btnUpload.setEnabled(true);
    }

    private void resetToCameraPreview() {
        imageUriToUpload = null;
        previewImage.setVisibility(View.GONE);
        viewFinder.setVisibility(View.VISIBLE);

        // Toggle buttons: morph Re-Capture back to Circular Camera button
        btnCapture.setText("📷");
        btnCapture.setTextSize(24);
        btnCapture.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#388E3C"))); // Green

        btnGallery.setVisibility(View.VISIBLE);
        btnFlash.setVisibility(View.VISIBLE);

        btnUpload.setVisibility(View.GONE);
        btnUpload.setEnabled(false);

        uploadProgress.setVisibility(View.GONE);
        uploadProgress.setProgress(0);
    }

    private void initCloudinary() {
        if (!isCloudinaryInitialized) {
            try {
                Map<String, String> config = new HashMap<>();
                config.put("cloud_name", BuildConfig.CLOUDINARY_CLOUD_NAME);
                config.put("api_key", BuildConfig.CLOUDINARY_API_KEY);
                config.put("api_secret", BuildConfig.CLOUDINARY_API_SECRET);

                MediaManager.init(this, config);
                isCloudinaryInitialized = true;
            } catch (Exception e) {
                Log.w(TAG, "Cloudinary initialization: " + e.getMessage());
            }
        }
    }

    private void uploadToCloudinary(Uri fileUri) {
        Toast.makeText(this, "Uploading...", Toast.LENGTH_SHORT).show();
        btnUpload.setEnabled(false);
        btnUpload.setText("Uploading...");

        uploadProgress.setVisibility(View.VISIBLE);
        uploadProgress.setProgress(0);

        MediaManager.get().upload(fileUri)
                .callback(new UploadCallback() {
                    @Override
                    public void onStart(String requestId) {}

                    @Override
                    public void onProgress(String requestId, long bytes, long totalBytes) {
                        int progress = (int) ((bytes * 100) / totalBytes);
                        // True enables smooth animation on Android 7+
                        runOnUiThread(() -> uploadProgress.setProgress(progress, true));
                    }

                    @Override
                    public void onSuccess(String requestId, Map resultData) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Upload Successful!", Toast.LENGTH_LONG).show();
                            resetToCameraPreview();
                            btnUpload.setText("Upload 🚀");
                            uploadProgress.setVisibility(View.GONE);
                        });
                    }

                    @Override
                    public void onError(String requestId, ErrorInfo error) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Upload Failed: " + error.getDescription(), Toast.LENGTH_LONG).show();
                            btnUpload.setEnabled(true);
                            btnUpload.setText("Upload 🚀");
                            uploadProgress.setVisibility(View.GONE);
                        });
                    }

                    @Override
                    public void onReschedule(String requestId, ErrorInfo error) {}
                })
                .dispatch();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}