package com.example.ohis;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.cloudinary.android.MediaManager;
import com.cloudinary.android.callback.ErrorInfo;
import com.cloudinary.android.callback.UploadCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private ImageView imageView;
    private Uri imageUriToUpload;
    private boolean isCloudinaryInitialized = false;

    // Launcher for the camera
    private final ActivityResultLauncher<Intent> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Bundle extras = result.getData().getExtras();
                    if (extras != null) {
                        Bitmap imageBitmap = (Bitmap) extras.get("data");
                        if (imageBitmap != null) {
                            imageView.setImageBitmap(imageBitmap);

                            // Save bitmap to cache to upload it
                            imageUriToUpload = saveBitmapToCache(imageBitmap);
                            findViewById(R.id.btnUpload).setEnabled(true);
                        }
                    }
                }
            }
    );

    // Launcher for camera permission request
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    launchCamera();
                } else {
                    Toast.makeText(this, "Camera permission is required to capture images", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initCloudinary();

        imageView = findViewById(R.id.imageView);
        Button btnCapture = findViewById(R.id.btnCapture);
        Button btnUpload = findViewById(R.id.btnUpload);
        Button btnAnalytics = findViewById(R.id.btnAnalytics);
        Button btnHistory = findViewById(R.id.btnHistory);

        btnCapture.setOnClickListener(v -> checkCameraPermissionAndLaunch());

        btnUpload.setOnClickListener(v -> {
            if (imageUriToUpload != null) {
                uploadToCloudinary(imageUriToUpload);
            }
        });

        btnAnalytics.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, AnalyticsActivity.class)));
        btnHistory.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, HistoryActivity.class)));
    }

    private void checkCameraPermissionAndLaunch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchCamera() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            cameraLauncher.launch(takePictureIntent);
        } else {
            Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show();
        }
    }

    private void initCloudinary() {
        if (!isCloudinaryInitialized) {
            try {
                Map<String, String> config = new HashMap<>();
                // Securely load from local.properties via BuildConfig
                config.put("cloud_name", BuildConfig.CLOUDINARY_CLOUD_NAME);
                config.put("api_key", BuildConfig.CLOUDINARY_API_KEY);
                config.put("api_secret", BuildConfig.CLOUDINARY_API_SECRET);

                MediaManager.init(this, config);
                isCloudinaryInitialized = true;
            } catch (Exception e) {
                // Ignore if already initialized or log it
                Log.w(TAG, "Cloudinary initialization: " + e.getMessage());
            }
        }
    }

    private void uploadToCloudinary(Uri fileUri) {
        Toast.makeText(this, "Uploading...", Toast.LENGTH_SHORT).show();
        MediaManager.get().upload(fileUri)
                .callback(new UploadCallback() {
                    @Override
                    public void onStart(String requestId) {}

                    @Override
                    public void onProgress(String requestId, long bytes, long totalBytes) {}

                    @Override
                    public void onSuccess(String requestId, Map resultData) {
                        Toast.makeText(MainActivity.this, "Upload Successful!", Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onError(String requestId, ErrorInfo error) {
                        Toast.makeText(MainActivity.this, "Upload Failed: " + error.getDescription(), Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onReschedule(String requestId, ErrorInfo error) {}
                })
                .dispatch();
    }

    // Helper to convert thumbnail Bitmap to a File for Cloudinary upload
    private Uri saveBitmapToCache(Bitmap bitmap) {
        try {
            File cachePath = new File(getCacheDir(), "images");
            if (!cachePath.exists()) {
                if (!cachePath.mkdirs()) {
                    Log.e(TAG, "Failed to create cache directory");
                }
            }
            File imageFile = new File(cachePath, "capture_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream stream = new FileOutputStream(imageFile)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            return Uri.fromFile(imageFile);
        } catch (IOException e) {
            Log.e(TAG, "Error saving bitmap to cache", e);
            return null;
        }
    }
}