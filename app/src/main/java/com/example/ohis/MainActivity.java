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
import android.view.KeyEvent;
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
import java.io.FileOutputStream;
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

    private Button btnCapture, btnGallery, btnUpload, btnFlash, btnSwitchCamera;
    private ProgressBar uploadProgress;

    private Uri imageUriToUpload; // 1. Raw Image
    private Uri eyeUriToUpload;   // 2. Eye Segment (Sclera + Iris)
    private Uri conjUriToUpload;  // 3. Conjunctiva Segment

    private boolean isCloudinaryInitialized = false;
    private boolean isFlashOn = false;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;

    // Track 3-image multi-upload state
    private int uploadCount = 0;
    private int successfulUploads = 0;

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

        // HIDE THE "OHIS" ACTION BAR
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

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
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera);
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

        btnSwitchCamera.setOnClickListener(v -> {
            lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK)
                    ? CameraSelector.LENS_FACING_FRONT
                    : CameraSelector.LENS_FACING_BACK;
            startCamera();
        });

        btnUpload.setOnClickListener(v -> {
            uploadAllImages();
        });

        btnAnalytics.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, AnalyticsActivity.class)));
        btnHistory.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, HistoryActivity.class)));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (imageUriToUpload == null) {
                takePhoto();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build();

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build();

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

                // Update flash button visibility based on whether the new camera has a flash
                runOnUiThread(() -> {
                    if (camera.getCameraInfo().hasFlashUnit()) {
                        btnFlash.setVisibility(View.VISIBLE);
                    } else {
                        btnFlash.setVisibility(View.GONE);
                        isFlashOn = false;
                        btnFlash.setText("🔦 Flash");
                    }
                });

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
        btnSwitchCamera.setVisibility(View.GONE);

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

                extractBiomarkers(uprightBitmap);

            } catch (Exception e) {
                Log.e(TAG, "Processing failed", e);
                showError("Processing failed. Please try again.");
            }
        });
    }

    private int getEdgeStrength(Bitmap bmp, int x, int y) {
        if (x + 1 >= bmp.getWidth() || y + 1 >= bmp.getHeight()) return 0;

        int c = bmp.getPixel(x, y);
        int right = bmp.getPixel(x + 1, y);
        int bottom = bmp.getPixel(x, y + 1);

        int dx = Math.abs(Color.red(c) - Color.red(right));
        int dy = Math.abs(Color.red(c) - Color.red(bottom));

        return dx + dy;
    }

    private void extractBiomarkers(Bitmap originalBitmap) {
        int origW = originalBitmap.getWidth();
        int origH = originalBitmap.getHeight();

        // =========================================================
        // STEP 1: EYE — pupil-hunting oval
        // =========================================================

        final int SCALE_EYE = 400;
        Bitmap tinyBmp = Bitmap.createScaledBitmap(originalBitmap, SCALE_EYE, SCALE_EYE, false);

        int bestPupilX = SCALE_EYE / 2;
        int bestPupilY = SCALE_EYE / 2;

        int searchRadius = SCALE_EYE / 6;

        float maxContrastScore = -1;
        for (int y = searchRadius; y < SCALE_EYE - searchRadius; y += 2) {
            for (int x = searchRadius; x < SCALE_EYE - searchRadius; x += 2) {
                int centerBrightness = getBrightness(tinyBmp.getPixel(x, y));
                if (centerBrightness > 110) continue; // must be dark (pupil)

                int leftBrightness  = getBrightness(tinyBmp.getPixel(x - searchRadius, y));
                int rightBrightness = getBrightness(tinyBmp.getPixel(x + searchRadius, y));
                int topBrightness   = getBrightness(tinyBmp.getPixel(x, y - searchRadius));
                int botBrightness   = getBrightness(tinyBmp.getPixel(x, y + searchRadius));

                float surroundAvg = (leftBrightness + rightBrightness + topBrightness + botBrightness) / 4f;
                float contrastScore = surroundAvg - centerBrightness;
                if (contrastScore > maxContrastScore) {
                    maxContrastScore = contrastScore;
                }
            }
        }

        if (maxContrastScore > 0) {
            float threshold = maxContrastScore * 0.95f; // top 5% only
            double weightedSumX = 0, weightedSumY = 0, totalWeight = 0;

            for (int y = searchRadius; y < SCALE_EYE - searchRadius; y += 2) {
                for (int x = searchRadius; x < SCALE_EYE - searchRadius; x += 2) {
                    int centerBrightness = getBrightness(tinyBmp.getPixel(x, y));
                    if (centerBrightness > 110) continue;

                    int leftBrightness  = getBrightness(tinyBmp.getPixel(x - searchRadius, y));
                    int rightBrightness = getBrightness(tinyBmp.getPixel(x + searchRadius, y));
                    int topBrightness   = getBrightness(tinyBmp.getPixel(x, y - searchRadius));
                    int botBrightness   = getBrightness(tinyBmp.getPixel(x, y + searchRadius));

                    float surroundAvg = (leftBrightness + rightBrightness + topBrightness + botBrightness) / 4f;
                    float score = surroundAvg - centerBrightness;

                    if (score >= threshold) {
                        double w = (double) score * score; // score² for tight clustering
                        weightedSumX += x * w;
                        weightedSumY += y * w;
                        totalWeight  += w;
                    }
                }
            }

            if (totalWeight > 0) {
                bestPupilX = (int) (weightedSumX / totalWeight);
                bestPupilY = (int) (weightedSumY / totalWeight);
            }
        }

        float actualPupilX = ((float) bestPupilX / SCALE_EYE) * origW;
        float actualPupilY = ((float) bestPupilY / SCALE_EYE) * origH;

        float eyeRadiusX = origW * 0.32f;
        float eyeRadiusY = origW * 0.18f;

        float clampedPupilX = Math.max(eyeRadiusX, Math.min(origW - eyeRadiusX, actualPupilX));
        float clampedPupilY = Math.max(eyeRadiusY, Math.min(origH - eyeRadiusY, actualPupilY));

        RectF eyeBounds = new RectF(
                clampedPupilX - eyeRadiusX,
                clampedPupilY - eyeRadiusY,
                clampedPupilX + eyeRadiusX,
                clampedPupilY + eyeRadiusY
        );

        Bitmap finalEye = extractTransparentGeometricRegion(originalBitmap, eyeBounds, true);

        // =========================================================
        // STEP 2: CONJUNCTIVA — flood fill from lower eyelid
        // =========================================================

        int processWidth  = 600;
        int processHeight = (int) ((float) origH / origW * processWidth);
        Bitmap scaled = Bitmap.createScaledBitmap(originalBitmap, processWidth, processHeight, false);

        int w = scaled.getWidth();
        int h = scaled.getHeight();
        int[] pixels = new int[w * h];
        scaled.getPixels(pixels, 0, w, 0, 0, w, h);

        float[] mucosalScores = new float[pixels.length];
        float maxMucosalScore  = 0;
        int   mucosalSeedIdx   = -1;
        float[] hsv = new float[3];

        int seedSearchStartY = (int) (h * 0.70f);

        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int yPos = i / w;

            Color.colorToHSV(p, hsv);
            float hue        = hsv[0]; // 0–360
            float saturation = hsv[1];

            int r = Color.red(p);
            int g = Color.green(p);
            int b = Color.blue(p);
            float vascularity = (r - g) + (r - b);
            float score = saturation * vascularity;
            if (score < 0) score = 0;

            if (hue >= 10f && hue <= 30f) {
                score *= 0.4f; // strongly penalize skin-orange
            }

            mucosalScores[i] = score;

            if (yPos >= seedSearchStartY && score > 10f && score > maxMucosalScore) {
                maxMucosalScore = score;
                mucosalSeedIdx  = i;
            }
        }

        if (mucosalSeedIdx == -1 || maxMucosalScore < 10) {
            showError("Could not detect exposed Conjunctiva. Please pull the lower eyelid down firmly.");
            return;
        }

        boolean[] conjunctivaMask = floodFill(w, h, mucosalSeedIdx, mucosalScores, maxMucosalScore * 0.55f);

        Bitmap finalConjunctiva = applyMaskToOriginal(originalBitmap, conjunctivaMask, w, h);

        // =========================================================
        // STEP 3: Cache for upload
        // =========================================================
        eyeUriToUpload   = saveBitmapToCache(finalEye,          "eye_segment");
        conjUriToUpload  = saveBitmapToCache(finalConjunctiva,  "conj_segment");

        showResults(finalEye, finalConjunctiva);
    }

    private Uri saveBitmapToCache(Bitmap bitmap, String namePrefix) {
        try {
            File file = new File(getCacheDir(), namePrefix + "_" + System.currentTimeMillis() + ".png");
            FileOutputStream out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            out.close();
            return Uri.fromFile(file);
        } catch (Exception e) {
            Log.e(TAG, "Failed to cache bitmap for upload", e);
            return null;
        }
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

    private Bitmap applyMaskToOriginal(Bitmap original, boolean[] smallMask, int smallW, int smallH) {
        int origW = original.getWidth();
        int origH = original.getHeight();

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

        Bitmap tinyMaskBitmap = Bitmap.createBitmap(maskPixels, maskCropW, maskCropH, Bitmap.Config.ARGB_8888);
        Bitmap smoothMask = Bitmap.createScaledBitmap(tinyMaskBitmap, cropW, cropH, true);

        Bitmap output = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(smoothMask, 0, 0, paint);

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
            btnUpload.setText("Upload 3 Images 🚀");
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
            default: break;
        }

        // Handle front camera mirroring
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            matrix.postScale(-1, 1);
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
        eyeUriToUpload = null;
        conjUriToUpload = null;

        previewContainer.setVisibility(View.GONE);
        segmentationLayout.setVisibility(View.INVISIBLE);

        viewFinder.setVisibility(View.VISIBLE);
        alignmentOverlay.setVisibility(View.VISIBLE);

        btnCapture.setText("📷");
        btnCapture.setTextSize(24);
        btnCapture.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#388E3C")));

        btnGallery.setVisibility(View.VISIBLE);
        // Only show flash if current camera supports it
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            btnFlash.setVisibility(View.VISIBLE);
        } else {
            btnFlash.setVisibility(View.GONE);
        }
        btnSwitchCamera.setVisibility(View.VISIBLE);

        btnUpload.setVisibility(View.GONE);
        btnUpload.setEnabled(false);

        uploadProgress.setVisibility(View.GONE);
    }

    private void initCloudinary() {
        try {
            // Check if it's already alive to prevent the double-init crash
            MediaManager.get();
            isCloudinaryInitialized = true;
        } catch (IllegalStateException e) {
            try {
                Map<String, String> config = new HashMap<>();
                config.put("cloud_name", BuildConfig.CLOUDINARY_CLOUD_NAME);
                config.put("api_key", BuildConfig.CLOUDINARY_API_KEY);
                config.put("api_secret", BuildConfig.CLOUDINARY_API_SECRET);
                MediaManager.init(this, config);
                isCloudinaryInitialized = true;
            } catch (Exception ex) {
                Log.e(TAG, "Cloudinary Init Failed (Check Config/API Keys!): " + ex.getMessage());
            }
        }
    }

    private void uploadAllImages() {
        if (imageUriToUpload == null || eyeUriToUpload == null || conjUriToUpload == null) {
            Toast.makeText(this, "Wait for processing to finish!", Toast.LENGTH_SHORT).show();
            return;
        }

        btnUpload.setEnabled(false);
        btnUpload.setText("Uploading 0/3...");
        btnUpload.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#808080")));

        uploadProgress.setVisibility(View.VISIBLE);
        uploadProgress.setIndeterminate(true);

        uploadCount = 0;
        successfulUploads = 0;

        // Launch all three simultaneously
        uploadSingleToCloudinary(imageUriToUpload, "raw_capture");
        uploadSingleToCloudinary(eyeUriToUpload, "eye_segment");
        uploadSingleToCloudinary(conjUriToUpload, "conjunctiva_segment");
    }

    private void uploadSingleToCloudinary(Uri fileUri, String tag) {
        try {
            MediaManager.get().upload(fileUri)
                    .option("tags", tag) // Tags help you sort them in your Cloudinary Dashboard
                    .callback(new UploadCallback() {
                        @Override public void onStart(String requestId) {}
                        @Override public void onProgress(String requestId, long bytes, long totalBytes) {}

                        @Override public void onSuccess(String requestId, Map resultData) {
                            handleUploadComplete(true);
                        }

                        @Override public void onError(String requestId, ErrorInfo error) {
                            Log.e(TAG, "Cloudinary Upload Failed for " + tag + ": " + error.getDescription());
                            handleUploadComplete(false);
                        }

                        @Override public void onReschedule(String requestId, ErrorInfo error) {}
                    })
                    .dispatch();
        } catch (Exception e) {
            Log.e(TAG, "Failed to dispatch upload: ", e);
            handleUploadComplete(false);
        }
    }

    private synchronized void handleUploadComplete(boolean success) {
        uploadCount++;
        if (success) successfulUploads++;

        runOnUiThread(() -> {
            btnUpload.setText("Uploading " + uploadCount + "/3...");

            if (uploadCount == 3) {
                uploadProgress.setVisibility(View.GONE);

                if (successfulUploads == 3) {
                    Toast.makeText(MainActivity.this, "All 3 Images Uploaded Successfully! 🚀", Toast.LENGTH_LONG).show();
                    resetToCameraPreview();
                } else {
                    Toast.makeText(MainActivity.this, "Some uploads failed! Check network/API keys.", Toast.LENGTH_LONG).show();
                    btnUpload.setEnabled(true);
                    btnUpload.setText("Retry Upload");
                    btnUpload.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FFA500")));
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        processingExecutor.shutdown();
    }
}