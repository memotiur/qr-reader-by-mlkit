package bmm.qubitsolutionlab.qrreaderbymlkit;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView; // Import for displaying zoom progress
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.karumi.dexter.Dexter;
import com.karumi.dexter.PermissionToken;
import com.karumi.dexter.listener.PermissionDeniedResponse;
import com.karumi.dexter.listener.PermissionGrantedResponse;
import com.karumi.dexter.listener.PermissionRequest;
import com.karumi.dexter.listener.single.PermissionListener;

public class MainActivity extends AppCompatActivity {

    private ImageAnalysis imageAnalysis;
    private PreviewView previewView;
    private CameraControl cameraControl; // Declare CameraControl
    private float zoomLevel = 1.0f; // Initialize zoom level
    private ScaleGestureDetector scaleGestureDetector; // Declare ScaleGestureDetector

    private ImageView scanningLine;
    private ObjectAnimator animator;
    private ImageView scanningArea;
    private SeekBar zoomSeekBar;
    private ImageView flashIcon;
    private TextView zoomLevelText; // TextView for displaying zoom progress

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        scanningLine = findViewById(R.id.scanningLine);
        scanningArea = findViewById(R.id.scanningArea);
        zoomSeekBar = findViewById(R.id.zoomSeekBar);
        flashIcon = findViewById(R.id.flashIcon);
        zoomLevelText = findViewById(R.id.zoomLevelText); // Initialize the TextView

        // Initialize ScaleGestureDetector
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());

        // Permission check
        checkPermission();

        RelativeLayout.LayoutParams scanningLineParams = (RelativeLayout.LayoutParams) scanningLine.getLayoutParams();
        scanningLineParams.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
        scanningLine.setLayoutParams(scanningLineParams);

        // Start the scanning line animation
        startScanningAnimation();

        // SeekBar for zoom control
        zoomSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                zoomLevel = progress / 10.0f; // Set zoom level based on SeekBar value
                if (cameraControl != null) {
                    cameraControl.setZoomRatio(zoomLevel); // Apply zoom
                }
                // Update the zoom level TextView
                zoomLevelText.setText(String.format("Zoom: %.1fx", zoomLevel));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Optional
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Optional
            }
        });

        boolean[] isFlashOn = {false}; // Using an array to hold the state

        flashIcon.setOnClickListener(v -> {
            // Toggle the flash state
            isFlashOn[0] = !isFlashOn[0];

            // Toggle flash on the camera
            if (cameraControl != null) {
                cameraControl.enableTorch(isFlashOn[0]);
            }

            // Update the flash icon based on the new state
            flashIcon.setImageResource(isFlashOn[0] ? R.drawable.ic_flash_on : R.drawable.ic_flash_off);
        });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Delegate the touch events to the ScaleGestureDetector
        scaleGestureDetector.onTouchEvent(event);
        return super.onTouchEvent(event);
    }

    private void checkPermission() {
        Dexter.withContext(this)
                .withPermission(Manifest.permission.CAMERA)
                .withListener(new PermissionListener() {
                    @Override
                    public void onPermissionGranted(PermissionGrantedResponse response) {
                        // Permission granted, start camera
                        startCamera();
                    }

                    @Override
                    public void onPermissionDenied(PermissionDeniedResponse response) {
                        // Permission denied, show a message to the user
                        Toast.makeText(getApplicationContext(), "Camera permission is required to use this feature", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onPermissionRationaleShouldBeShown(PermissionRequest permission, PermissionToken token) {
                        // Show rationale and request permission again
                        token.continuePermissionRequest();
                    }
                }).check();
    }

    public void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(getApplicationContext());
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindPreview(cameraProvider); // Bind the preview and camera lifecycle here
            } catch (Exception e) {
                Log.e("MOTIUR", "Camera initialization failed.", e);
            }
        }, ContextCompat.getMainExecutor(getApplicationContext()));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void bindPreview(@NonNull ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        // Initialize ImageAnalysis
        imageAnalysis = new ImageAnalysis.Builder().build();
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(getApplicationContext()), imageProxy -> {
            if (imageProxy.getImage() != null) { // Check if scanning is not paused
                InputImage image = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());

                BarcodeScanner scanner = BarcodeScanning.getClient();
                scanner.process(image)
                        .addOnSuccessListener(barcodes -> {
                            if (!barcodes.isEmpty()) {
                                Barcode barcode = barcodes.get(0); // Process first barcode only
                                String rawValue = barcode.getRawValue();

                                imageAnalysis.clearAnalyzer(); // Clear the analyzer to stop further processing
                                showResult(rawValue);
                            }
                        })
                        .addOnFailureListener(e -> Log.e("MOTIUR", "Barcode scanning failed: ", e))
                        .addOnCompleteListener(task -> imageProxy.close());
            } else {
                imageProxy.close();
            }
        });

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

        // Get CameraControl
        cameraControl = cameraProvider.bindToLifecycle(this, cameraSelector, preview).getCameraControl();

        // Set the initial zoom level after camera control is initialized
        if (cameraControl != null) {
            cameraControl.setZoomRatio(zoomLevel); // Apply zoom here
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        clearImageAnalyzer(); // Centralized analyzer clearing
    }

    @Override
    public void onPause() {
        super.onPause();
        clearImageAnalyzer(); // Centralized analyzer clearing
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        clearImageAnalyzer(); // Centralized analyzer clearing
    }

    @Override
    public void onResume() {
        super.onResume();
        if (imageAnalysis == null) {
            // Start camera only if it hasn't been started yet
            startCamera();
        }
    }

    private void clearImageAnalyzer() {
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer(); // Clear the analyzer if it exists
        }
    }

    private void startScanningAnimation() {
        animator = ObjectAnimator.ofFloat(scanningLine, "translationY", 0f, scanningArea.getHeight());
        animator.setDuration(2000);
        animator.setRepeatCount(ObjectAnimator.INFINITE);
        animator.setRepeatMode(ObjectAnimator.RESTART);
        animator.start();
    }

    private void showResult(String rawValue) {
        // Implement result handling logic here
        Toast.makeText(this, "Scanned Value: " + rawValue, Toast.LENGTH_SHORT).show();
    }

    // Inner class for handling scale gestures
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleFactor = detector.getScaleFactor();
            zoomLevel = Math.max(1.0f, Math.min(zoomLevel * scaleFactor, 10.0f)); // Limit zoom level between 1x to 10x

            if (cameraControl != null) {
                cameraControl.setZoomRatio(zoomLevel); // Apply zoom
            }

            // Update the zoom level TextView
            zoomSeekBar.setProgress((int) (zoomLevel * 10)); // Update SeekBar based on zoom level
            zoomLevelText.setText(String.format("Zoom: %.1fx", zoomLevel));

            return true;
        }
    }

}
