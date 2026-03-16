package com.example.ohis;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private PreviewView viewFinder;
    private LinearLayout previewContainer;
    private LinearLayout segmentationLayout;
    private ImageView previewImage, imgEyeSegment, imgConjunctivaSegment;
    private View alignmentOverlay;
    private TextView tvErrorMessage;

    private Button btnCapture, btnGallery, btnUpload, btnFlash;
    private ProgressBar uploadProgress;

    private Uri imageUriToUpload;
    private boolean isCloudinaryInitialized = false;
    private boolean isFlashOn = false;

    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private ExecutorService processingExecutor;
    private Camera camera;

    private final ActivityResultLauncher<String> galleryLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    processAndShowImage(uri);
                }
            }
    );

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
        alignmentOverlay = findViewById(R.id.alignmentOverlay);
        previewContainer = findViewById(R.id.previewContainer);
        segmentationLayout = findViewById(R.id.segmentationLayout);
        previewImage = findViewById(R.id.previewImage);
        imgEyeSegment = findViewById(R.id.imgEyeSegment);
        imgConjunctivaSegment = findViewById(R.id.imgConjunctivaSegment);
        tvErrorMessage = findViewById(R.id.tvErrorMessage);

        btnCapture = findViewById(R.id.btnCapture);
        btnGallery = findViewById(R.id.btnGallery);
        btnUpload = findViewById(R.id.btnUpload);
        btnFlash = findViewById(R.id.btnFlash);
        uploadProgress = findViewById(R.id.uploadProgress);

        Button btnAnalytics = findViewById(R.id.btnAnalytics);
        Button btnHistory = findViewById(R.id.btnHistory);

        cameraExecutor = Executors.newSingleThreadExecutor();
        processingExecutor = Executors.newSingleThreadExecutor();

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

                // FORCE MAXIMUM QUALITY (Reduces compression artifacts and blur)
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build();

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
        tvErrorMessage.setVisibility(View.GONE);

        File photoFile = new File(getCacheDir(), "capture_" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                Uri savedUri = Uri.fromFile(photoFile);
                processAndShowImage(savedUri);
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "Photo capture failed", exception);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Capture failed", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void processAndShowImage(Uri uri) {
        imageUriToUpload = uri;

        viewFinder.setVisibility(View.INVISIBLE);
        alignmentOverlay.setVisibility(View.INVISIBLE);
        previewContainer.setVisibility(View.VISIBLE);
        segmentationLayout.setVisibility(View.INVISIBLE);

        btnCapture.setText("Re-Capture");
        btnCapture.setTextSize(16);
        btnCapture.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#D32F2F")));

        btnGallery.setVisibility(View.GONE);
        btnFlash.setVisibility(View.GONE);

        btnUpload.setVisibility(View.VISIBLE);
        btnUpload.setEnabled(false);
        btnUpload.setText("Extracting Tissue...");
        btnUpload.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#808080")));

        uploadProgress.setVisibility(View.VISIBLE);
        uploadProgress.setIndeterminate(true);

        processingExecutor.execute(() -> {
            try {
                final Bitmap uprightBitmap = getBitmapWithCorrectRotation(uri);
                if (uprightBitmap == null) throw new Exception("Failed to load image");

                new Handler(Looper.getMainLooper()).post(() -> previewImage.setImageBitmap(uprightBitmap));

                // Launch the new Commercial Erythema Region-Growing Engine
                extractBiomarkers(uprightBitmap);

            } catch (Exception e) {
                Log.e(TAG, "Processing failed", e);
                showError("Processing failed. Please try again.");
            }
        });
    }

    /**
     * ADVANCED TISSUE EXTRACTION ENGINE
     * Combines the "Good" geometric pupil-hunting algorithm for the Eye
     * with the Erythema Region-Growing algorithm for the Conjunctiva.
     */
    private void extractBiomarkers(Bitmap originalBitmap) {
        int origW = originalBitmap.getWidth();
        int origH = originalBitmap.getHeight();

        // ==========================================
        // STEP 1: THE "GOOD" METHOD FOR EYE (PUPIL HUNTING OVAL)
        // ==========================================
        int scaleSmall = 300; // Increased precision to perfectly lock onto the pupil center
        Bitmap tinyBmp = Bitmap.createScaledBitmap(originalBitmap, scaleSmall, scaleSmall, false);

        int bestPupilX = scaleSmall / 2;
        int bestPupilY = scaleSmall / 2;
        int maxContrastScore = -1;
        int searchRadius = scaleSmall / 8;

        // Pass 1: Find the maximum contrast score in the image
        for (int y = searchRadius; y < scaleSmall - searchRadius; y += 2) {
            for (int x = searchRadius; x < scaleSmall - searchRadius; x += 2) {
                int centerPixel = tinyBmp.getPixel(x, y);
                int centerBrightness = getBrightness(centerPixel);

                if (centerBrightness > 120) continue; // Skip bright spots

                int leftBrightness = getBrightness(tinyBmp.getPixel(x - searchRadius, y));
                int rightBrightness = getBrightness(tinyBmp.getPixel(x + searchRadius, y));

                int contrastScore = ((leftBrightness + rightBrightness) / 2) - centerBrightness;

                if (contrastScore > maxContrastScore) {
                    maxContrastScore = contrastScore;
                }
            }
        }

        // Pass 2: "Center of Mass" Math for PERFECT centering
        int sumX = 0, sumY = 0, count = 0;
        if (maxContrastScore > 0) {
            for (int y = searchRadius; y < scaleSmall - searchRadius; y += 2) {
                for (int x = searchRadius; x < scaleSmall - searchRadius; x += 2) {
                    int centerPixel = tinyBmp.getPixel(x, y);
                    int centerBrightness = getBrightness(centerPixel);
                    if (centerBrightness > 120) continue;

                    int leftBrightness = getBrightness(tinyBmp.getPixel(x - searchRadius, y));
                    int rightBrightness = getBrightness(tinyBmp.getPixel(x + searchRadius, y));
                    int contrastScore = ((leftBrightness + rightBrightness) / 2) - centerBrightness;

                    // Group all pixels that score in the top 10%
                    if (contrastScore >= maxContrastScore * 0.90f) {
                        sumX += x;
                        sumY += y;
                        count++;
                    }
                }
            }
            if (count > 0) {
                bestPupilX = sumX / count;
                bestPupilY = sumY / count;
            }
        }

        float actualPupilX = ((float) bestPupilX / scaleSmall) * origW;
        float actualPupilY = ((float) bestPupilY / scaleSmall) * origH;

        float eyeRadiusX = origW * 0.35f;
        float eyeRadiusY = origW * 0.20f;

        // PERFECT CENTERING: Force the crop bounding box to be absolutely symmetric.
        float safeRadiusX = Math.min(eyeRadiusX, Math.min(actualPupilX, origW - actualPupilX));
        float safeRadiusY = Math.min(eyeRadiusY, Math.min(actualPupilY, origH - actualPupilY));

        RectF eyeBounds = new RectF(
                actualPupilX - safeRadiusX,
                actualPupilY - safeRadiusY,
                actualPupilX + safeRadiusX,
                actualPupilY + safeRadiusY
        );

        Bitmap finalEye = extractTransparentGeometricRegion(originalBitmap, eyeBounds, true);

        // ==========================================
        // STEP 2: ADVANCED FLOOD FILL FOR CONJUNCTIVA
        // ==========================================
        int processWidth = 600; // DOUBLED RESOLUTION for massive precision increase on vascular edges!
        int processHeight = (int) ((float) origH / origW * processWidth);
        Bitmap scaled = Bitmap.createScaledBitmap(originalBitmap, processWidth, processHeight, false);

        int w = scaled.getWidth();
        int h = scaled.getHeight();
        int[] pixels = new int[w * h];
        scaled.getPixels(pixels, 0, w, 0, 0, w, h);

        float[] mucosalScores = new float[pixels.length];
        float maxMucosalScore = 0;
        int mucosalSeedIdx = -1;
        float[] hsv = new float[3];

        // Ensure we only search for conjunctiva strictly BELOW the pupil
        int pupilYInProcessScale = (int) (((float) bestPupilY / scaleSmall) * h);

        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = Color.red(p);
            int g = Color.green(p);
            int b = Color.blue(p);

            Color.colorToHSV(p, hsv);
            float saturation = hsv[1];

            float vascularity = (r - g) + (r - b);
            float score = saturation * vascularity;

            if (score < 0) score = 0;
            mucosalScores[i] = score;

            if (score > maxMucosalScore) {
                int yPos = i / w;
                // Heuristic: Must be below the pupil!
                if (yPos > pupilYInProcessScale + (h * 0.05f)) {
                    maxMucosalScore = score;
                    mucosalSeedIdx = i;
                }
            }
        }

        if (mucosalSeedIdx == -1 || maxMucosalScore < 10) {
            showError("Could not detect exposed Conjunctiva. Please pull the lower eyelid down firmly.");
            return;
        }

        // Flood Fill to extract ONLY the connected mucosal tissue
        boolean[] conjunctivaMask = floodFill(w, h, mucosalSeedIdx, mucosalScores, maxMucosalScore * 0.45f);

        Bitmap finalConjunctiva = applyMaskToOriginal(originalBitmap, conjunctivaMask, w, h);

        showResults(finalEye, finalConjunctiva);
    }

    private int getBrightness(int color) {
        return (Color.red(color) + Color.green(color) + Color.blue(color)) / 3;
    }

    private Bitmap extractTransparentGeometricRegion(Bitmap src, RectF bounds, boolean isOval) {
        int left = Math.max(0, (int) bounds.left);
        int top = Math.max(0, (int) bounds.top);
        int right = Math.min(src.getWidth(), (int) bounds.right);
        int bottom = Math.min(src.getHeight(), (int) bounds.bottom);
        int width = right - left;
        int height = bottom - top;

        if (width <= 0 || height <= 0) return src;

        Bitmap output = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setColor(Color.BLACK);

        if (isOval) {
            canvas.drawOval(new RectF(left, top, right, bottom), paint);
        } else {
            canvas.drawRect(new RectF(left, top, right, bottom), paint);
        }

        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(src, 0, 0, paint);

        return Bitmap.createBitmap(output, left, top, width, height);
    }

    /**
     * Magic Wand / Region Growing Algorithm.
     * Starts at the 'seed' pixel and expands outwards as long as neighboring pixels
     * meet the strict threshold for the specific tissue type.
     */
    private boolean[] floodFill(int w, int h, int seedIdx, float[] scores, float threshold) {
        boolean[] mask = new boolean[w * h];
        boolean[] visited = new boolean[w * h];
        Queue<Integer> queue = new LinkedList<>();

        queue.add(seedIdx);
        visited[seedIdx] = true;
        mask[seedIdx] = true;

        int[] dx = {-1, 1, 0, 0};
        int[] dy = {0, 0, -1, 1};

        while (!queue.isEmpty()) {
            int current = queue.poll();
            int cx = current % w;
            int cy = current / w;

            for (int i = 0; i < 4; i++) {
                int nx = cx + dx[i];
                int ny = cy + dy[i];

                if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                    int nIdx = ny * w + nx;
                    if (!visited[nIdx]) {
                        visited[nIdx] = true;
                        // If this neighboring pixel is highly similar to our target tissue, add it!
                        if (scores[nIdx] >= threshold) {
                            mask[nIdx] = true;
                            queue.add(nIdx);
                        }
                    }
                }
            }
        }
        return mask;
    }

    /**
     * Completely re-engineered for High Fidelity.
     * Generates a perfectly anti-aliased bitmap mask, completely eliminating jagged Minecraft edges.
     */
    private Bitmap applyMaskToOriginal(Bitmap original, boolean[] smallMask, int smallW, int smallH) {
        int origW = original.getWidth();
        int origH = original.getHeight();

        // Find Bounding Box of the mask
        int minX = smallW, minY = smallH, maxX = 0, maxY = 0;
        boolean hasPixels = false;

        for (int y = 0; y < smallH; y++) {
            for (int x = 0; x < smallW; x++) {
                if (smallMask[y * smallW + x]) {
                    hasPixels = true;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }

        if (!hasPixels) return original;

        float scaleX = (float) origW / smallW;
        float scaleY = (float) origH / smallH;

        int cropX = Math.max(0, (int) (minX * scaleX));
        int cropY = Math.max(0, (int) (minY * scaleY));
        int cropW = Math.min(origW - cropX, (int) ((maxX - minX + 1) * scaleX));
        int cropH = Math.min(origH - cropY, (int) ((maxY - minY + 1) * scaleY));

        if (cropW <= 0 || cropH <= 0) return original;

        // --- THE FIX: SMOOTH BILINEAR MASK GENERATION ---
        int maskCropW = maxX - minX + 1;
        int maskCropH = maxY - minY + 1;
        int[] maskPixels = new int[maskCropW * maskCropH];

        for (int y = 0; y < maskCropH; y++) {
            for (int x = 0; x < maskCropW; x++) {
                if (smallMask[(minY + y) * smallW + (minX + x)]) {
                    maskPixels[y * maskCropW + x] = Color.BLACK;
                } else {
                    maskPixels[y * maskCropW + x] = Color.TRANSPARENT;
                }
            }
        }

        // Create the small mask image
        Bitmap tinyMaskBitmap = Bitmap.createBitmap(maskPixels, maskCropW, maskCropH, Bitmap.Config.ARGB_8888);

        // Magically smooth and scale it up using hardware interpolation (true = smooth!)
        Bitmap smoothMask = Bitmap.createScaledBitmap(tinyMaskBitmap, cropW, cropH, true);

        // --- APPLY THE SILKY SMOOTH MASK ---
        Bitmap output = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        // 1. Draw the buttery smooth mask
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(smoothMask, 0, 0, paint);

        // 2. Lay the original high-resolution photo on top!
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        Rect srcRect = new Rect(cropX, cropY, cropX + cropW, cropY + cropH);
        Rect destRect = new Rect(0, 0, cropW, cropH);
        canvas.drawBitmap(original, srcRect, destRect, paint);

        return output;
    }

    private void showResults(Bitmap eye, Bitmap conj) {
        new Handler(Looper.getMainLooper()).post(() -> {
            imgEyeSegment.setImageBitmap(eye);
            imgConjunctivaSegment.setImageBitmap(conj);

            segmentationLayout.setVisibility(View.VISIBLE);
            uploadProgress.setVisibility(View.GONE);
            btnUpload.setEnabled(true);
            btnUpload.setText("Upload 🚀");
            btnUpload.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FFA500")));
        });
    }

    private Bitmap getBitmapWithCorrectRotation(Uri uri) throws Exception {
        InputStream is = getContentResolver().openInputStream(uri);
        Bitmap bitmap = BitmapFactory.decodeStream(is);
        is.close();

        InputStream isExif = getContentResolver().openInputStream(uri);
        ExifInterface exif = new ExifInterface(isExif);
        int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
        isExif.close();

        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
            default: return bitmap;
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private void showError(String msg) {
        new Handler(Looper.getMainLooper()).post(() -> {
            uploadProgress.setVisibility(View.GONE);
            tvErrorMessage.setText(msg);
            tvErrorMessage.setVisibility(View.VISIBLE);
            resetToCameraPreview();
            tvErrorMessage.setVisibility(View.VISIBLE);
        });
    }

    private void resetToCameraPreview() {
        imageUriToUpload = null;
        previewContainer.setVisibility(View.GONE);
        segmentationLayout.setVisibility(View.INVISIBLE);

        viewFinder.setVisibility(View.VISIBLE);
        alignmentOverlay.setVisibility(View.VISIBLE);

        btnCapture.setText("📷");
        btnCapture.setTextSize(24);
        btnCapture.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#388E3C")));

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
        btnUpload.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#808080")));

        uploadProgress.setVisibility(View.VISIBLE);
        uploadProgress.setProgress(0);

        MediaManager.get().upload(fileUri)
                .callback(new UploadCallback() {
                    @Override
                    public void onStart(String requestId) {}

                    @Override
                    public void onProgress(String requestId, long bytes, long totalBytes) {
                        int progress = (int) ((bytes * 100) / totalBytes);
                        runOnUiThread(() -> uploadProgress.setProgress(progress, true));
                    }

                    @Override
                    public void onSuccess(String requestId, Map resultData) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Upload Successful!", Toast.LENGTH_LONG).show();
                            resetToCameraPreview();
                        });
                    }

                    @Override
                    public void onError(String requestId, ErrorInfo error) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Upload Failed: " + error.getDescription(), Toast.LENGTH_LONG).show();
                            btnUpload.setEnabled(true);
                            btnUpload.setText("Upload 🚀");
                            btnUpload.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FFA500")));
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
        processingExecutor.shutdown();
    }
}