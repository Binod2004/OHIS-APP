package com.example.ohis;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.cloudinary.android.MediaManager;
import com.cloudinary.android.callback.ErrorInfo;
import com.cloudinary.android.callback.UploadCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private ImageView imageView;
    private Button btnCapture, btnUpload, btnAnalytics, btnHistory;
    private Uri imageUriToUpload;
    private boolean isCloudinaryInitialized = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initCloudinary();

        imageView = findViewById(R.id.imageView);
        btnCapture = findViewById(R.id.btnCapture);
        btnUpload = findViewById(R.id.btnUpload);
        btnAnalytics = findViewById(R.id.btnAnalytics);
        btnHistory = findViewById(R.id.btnHistory);

        // Launcher for the camera
        ActivityResultLauncher<Intent> cameraLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Bundle extras = result.getData().getExtras();
                        Bitmap imageBitmap = (Bitmap) extras.get("data");
                        imageView.setImageBitmap(imageBitmap);

                        // Save bitmap to cache to upload it
                        imageUriToUpload = saveBitmapToCache(imageBitmap);
                        btnUpload.setEnabled(true);
                    }
                }
        );

        btnCapture.setOnClickListener(v -> {
            Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            cameraLauncher.launch(takePictureIntent);
        });

        btnUpload.setOnClickListener(v -> {
            if (imageUriToUpload != null) {
                uploadToCloudinary(imageUriToUpload);
            }
        });

        btnAnalytics.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, AnalyticsActivity.class)));
        btnHistory.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, HistoryActivity.class)));
    }

    private void initCloudinary() {
        if (!isCloudinaryInitialized) {
            try {
                Map<String, String> config = new HashMap<>();
                config.put("cloud_name", "YOUR_CLOUD_NAME");
                config.put("api_key", "YOUR_API_KEY");
                config.put("api_secret", "YOUR_API_SECRET");
                MediaManager.init(this, config);
                isCloudinaryInitialized = true;
            } catch (Exception e) {
                // Ignore if already initialized
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
            cachePath.mkdirs();
            File imageFile = new File(cachePath, "capture_" + System.currentTimeMillis() + ".png");
            FileOutputStream stream = new FileOutputStream(imageFile);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            stream.close();
            return Uri.fromFile(imageFile);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}
1234