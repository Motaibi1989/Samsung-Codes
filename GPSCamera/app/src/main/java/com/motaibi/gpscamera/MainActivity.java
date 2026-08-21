package com.motaibi.gpscamera;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Address;
import android.location.Geocoder;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 100;

    private TextureView textureView;
    private TextView statusText;
    private Button flashButton;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewBuilder;
    private ImageReader imageReader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraCharacteristics characteristics;
    private String cameraId;
    private Size captureSize;
    private Size previewSize;
    private boolean flashEnabled = false;
    private boolean hasFlash = false;
    private float zoomRatio = 1.0f;
    private Range<Float> zoomRange;

    private LocationManager locationManager;
    private Location currentLocation;
    private int satellitesUsed = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);

        buildUi();
        startBackgroundThread();

        if (hasAllPermissions()) {
            startLocationUpdates();
            if (textureView.isAvailable()) openCamera();
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_PERMISSIONS);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(surfaceTextureListener);
        root.addView(textureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        statusText = new TextView(this);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(15f);
        statusText.setPadding(24, 18, 24, 18);
        statusText.setBackgroundColor(0x88000000);
        statusText.setText("GPS: acquiring location…");
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(statusText, statusParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(10, 12, 10, 20);
        controls.setBackgroundColor(0x99000000);

        Button zoom05 = makeButton("0.5×");
        Button zoom1 = makeButton("1×");
        Button zoom3 = makeButton("3×");
        flashButton = makeButton("Flash Off");
        Button capture = makeButton("CAPTURE");

        zoom05.setOnClickListener(v -> setZoom(0.5f));
        zoom1.setOnClickListener(v -> setZoom(1.0f));
        zoom3.setOnClickListener(v -> setZoom(3.0f));
        flashButton.setOnClickListener(v -> toggleFlash());
        capture.setOnClickListener(v -> capturePhoto());

        controls.addView(zoom05, weightedParams(1f));
        controls.addView(zoom1, weightedParams(1f));
        controls.addView(zoom3, weightedParams(1f));
        controls.addView(flashButton, weightedParams(1.2f));
        controls.addView(capture, weightedParams(1.5f));

        FrameLayout.LayoutParams controlParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(controls, controlParams);

        setContentView(root);
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12f);
        b.setAllCaps(false);
        b.setPadding(4, 4, 4, 4);
        return b;
    }

    private LinearLayout.LayoutParams weightedParams(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 110, weight);
        p.setMargins(3, 0, 3, 0);
        return p;
    }

    private boolean hasAllPermissions() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                if (textureView.isAvailable()) openCamera();
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show();
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            }
        }
    }

    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera();
        }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
    };

    private void startBackgroundThread() {
        cameraThread = new HandlerThread("GPSCameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try { cameraThread.join(); } catch (InterruptedException ignored) { }
            cameraThread = null;
            cameraHandler = null;
        }
    }

    private void openCamera() {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (cameraId == null) {
                for (String id : manager.getCameraIdList()) {
                    CameraCharacteristics c = manager.getCameraCharacteristics(id);
                    Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        cameraId = id;
                        characteristics = c;
                        break;
                    }
                }
            }
            if (cameraId == null || characteristics == null) {
                Toast.makeText(this, "No rear camera found", Toast.LENGTH_LONG).show();
                return;
            }

            Boolean flash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            hasFlash = flash != null && flash;
            if (!hasFlash) flashButton.setEnabled(false);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            }

            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return;
            captureSize = chooseCaptureSize(map.getOutputSizes(android.graphics.ImageFormat.JPEG));
            previewSize = choosePreviewSize(map.getOutputSizes(SurfaceTexture.class));

            imageReader = ImageReader.newInstance(captureSize.getWidth(), captureSize.getHeight(),
                    android.graphics.ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);

            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
            manager.openCamera(cameraId, cameraStateCallback, cameraHandler);
        } catch (Exception e) {
            Toast.makeText(this, "Camera error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private Size chooseCaptureSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1920, 1080);
        List<Size> list = new ArrayList<>(Arrays.asList(sizes));
        list.sort((a, b) -> Long.compare((long)b.getWidth() * b.getHeight(), (long)a.getWidth() * a.getHeight()));
        for (Size s : list) {
            long pixels = (long) s.getWidth() * s.getHeight();
            if (pixels <= 13_000_000L && s.getWidth() >= 3000) return s;
        }
        return list.get(Math.min(1, list.size() - 1));
    }

    private Size choosePreviewSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1920, 1080);
        List<Size> list = new ArrayList<>(Arrays.asList(sizes));
        list.sort((a, b) -> Long.compare((long)b.getWidth() * b.getHeight(), (long)a.getWidth() * a.getHeight()));
        for (Size s : list) {
            if (s.getWidth() <= 1920 && s.getHeight() <= 1080) return s;
        }
        return list.get(list.size() - 1);
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            createPreviewSession();
        }
        @Override public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }
        @Override public void onError(CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
        }
    };

    private void createPreviewSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null || cameraDevice == null || imageReader == null) return;
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(texture);

            previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(previewSurface);
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            applyZoom(previewBuilder);
            applyPreviewFlash(previewBuilder);

            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            captureSession = session;
                            updatePreview();
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession session) {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                    "Camera preview configuration failed", Toast.LENGTH_LONG).show());
                        }
                    }, cameraHandler);
        } catch (CameraAccessException e) {
            Toast.makeText(this, "Preview error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updatePreview() {
        if (captureSession == null || previewBuilder == null) return;
        try {
            applyZoom(previewBuilder);
            applyPreviewFlash(previewBuilder);
            captureSession.setRepeatingRequest(previewBuilder.build(), null, cameraHandler);
        } catch (CameraAccessException ignored) { }
    }

    private void setZoom(float requested) {
        zoomRatio = requested;
        if (zoomRange != null) {
            zoomRatio = Math.max(zoomRange.getLower(), Math.min(zoomRange.getUpper(), requested));
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && requested < 1f) {
            zoomRatio = 1f;
        }
        updatePreview();
        Toast.makeText(this, String.format(Locale.US, "Zoom %.1f×", zoomRatio), Toast.LENGTH_SHORT).show();
    }

    private void applyZoom(CaptureRequest.Builder builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && zoomRange != null) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio);
        }
    }

    private void toggleFlash() {
        if (!hasFlash) return;
        flashEnabled = !flashEnabled;
        flashButton.setText(flashEnabled ? "Flash On" : "Flash Off");
        updatePreview();
    }

    private void applyPreviewFlash(CaptureRequest.Builder builder) {
        if (!hasFlash) return;
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        builder.set(CaptureRequest.FLASH_MODE,
                flashEnabled ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
    }

    private void capturePhoto() {
        if (cameraDevice == null || captureSession == null || imageReader == null) {
            Toast.makeText(this, "Camera is not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            CaptureRequest.Builder still = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            still.addTarget(imageReader.getSurface());
            still.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            applyZoom(still);
            if (hasFlash) {
                if (flashEnabled) {
                    still.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                } else {
                    still.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                    still.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                }
            }

            Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int rotation = getDisplay().getRotation();
            int deviceDegrees;
            switch (rotation) {
                case Surface.ROTATION_90: deviceDegrees = 90; break;
                case Surface.ROTATION_180: deviceDegrees = 180; break;
                case Surface.ROTATION_270: deviceDegrees = 270; break;
                default: deviceDegrees = 0;
            }
            int jpegOrientation = ((sensorOrientation == null ? 90 : sensorOrientation) - deviceDegrees + 360) % 360;
            still.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation);

            captureSession.capture(still.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession session,
                                                         CaptureRequest request,
                                                         TotalCaptureResult result) {
                    updatePreview();
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            Toast.makeText(this, "Capture error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void onImageAvailable(ImageReader reader) {
        try (Image image = reader.acquireNextImage()) {
            if (image == null) return;
            java.nio.ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            saveStampedPhoto(bytes);
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Save error: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private void saveStampedPhoto(byte[] jpegBytes) throws Exception {
        Bitmap original = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        if (original == null) throw new Exception("Unable to decode captured image");
        Bitmap stamped = original.copy(Bitmap.Config.ARGB_8888, true);
        original.recycle();

        Canvas canvas = new Canvas(stamped);
        float textSize = Math.max(34f, stamped.getWidth() / 32f);
        float lineGap = textSize * 1.35f;
        Paint bg = new Paint();
        bg.setColor(Color.BLACK);
        bg.setAlpha(165);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextSize(textSize);

        String date = new SimpleDateFormat("dd MMM yyyy  HH:mm:ss", Locale.getDefault()).format(new Date());
        Location loc = currentLocation;
        String address = lookupAddress(loc);
        String gpsLine;
        String accuracyLine;
        if (loc != null) {
            gpsLine = String.format(Locale.US, "Lat: %.6f    Lon: %.6f", loc.getLatitude(), loc.getLongitude());
            accuracyLine = String.format(Locale.US, "Accuracy: ±%.1f m    Satellites: %d", loc.getAccuracy(), satellitesUsed);
        } else {
            gpsLine = "Location unavailable";
            accuracyLine = "GPS not locked";
        }

        List<String> lines = new ArrayList<>();
        lines.add(date);
        if (address != null && !address.isEmpty()) lines.add(address);
        lines.add(gpsLine);
        lines.add(accuracyLine);
        lines.add("GPS Camera");

        float padding = textSize * 0.7f;
        float blockHeight = padding * 2 + lineGap * lines.size();
        float top = stamped.getHeight() - blockHeight;
        canvas.drawRect(0, top, stamped.getWidth(), stamped.getHeight(), bg);

        float y = top + padding + textSize;
        for (String line : lines) {
            canvas.drawText(line, padding, y, text);
            y += lineGap;
        }

        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        String fileName = "GPSCamera_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GPS Camera");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("Unable to create image file");
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new Exception("Unable to open image file");
            stamped.compress(Bitmap.CompressFormat.JPEG, 95, out);
        }
        stamped.recycle();

        values.clear();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);

        runOnUiThread(() -> Toast.makeText(this,
                "Saved to Pictures/GPS Camera", Toast.LENGTH_LONG).show());
    }

    private String lookupAddress(Location loc) {
        if (loc == null || !Geocoder.isPresent()) return null;
        try {
            Geocoder geocoder = new Geocoder(this, Locale.getDefault());
            List<Address> results = geocoder.getFromLocation(loc.getLatitude(), loc.getLongitude(), 1);
            if (results != null && !results.isEmpty()) {
                Address a = results.get(0);
                String line = a.getAddressLine(0);
                if (line != null && !line.isEmpty()) return line;
                StringBuilder sb = new StringBuilder();
                if (a.getLocality() != null) sb.append(a.getLocality());
                if (a.getAdminArea() != null) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(a.getAdminArea());
                }
                if (a.getCountryName() != null) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(a.getCountryName());
                }
                return sb.toString();
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void startLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0,
                        locationListener, Looper.getMainLooper());
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000, 0,
                        locationListener, Looper.getMainLooper());
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                locationManager.registerGnssStatusCallback(gnssCallback, new Handler(Looper.getMainLooper()));
            }
        } catch (Exception e) {
            statusText.setText("GPS error: " + e.getMessage());
        }
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location location) {
            if (currentLocation == null || location.getAccuracy() <= currentLocation.getAccuracy() + 10f
                    || location.getTime() > currentLocation.getTime() + 5000) {
                currentLocation = location;
            }
            updateGpsStatus();
        }
    };

    private final GnssStatus.Callback gnssCallback = new GnssStatus.Callback() {
        @Override public void onSatelliteStatusChanged(GnssStatus status) {
            int used = 0;
            for (int i = 0; i < status.getSatelliteCount(); i++) {
                if (status.usedInFix(i)) used++;
            }
            satellitesUsed = used;
            updateGpsStatus();
        }
    };

    private void updateGpsStatus() {
        runOnUiThread(() -> {
            if (currentLocation == null) {
                statusText.setText("GPS: acquiring location…  Satellites: " + satellitesUsed);
            } else {
                statusText.setText(String.format(Locale.US,
                        "GPS: %.6f, %.6f   Accuracy: ±%.1f m   Satellites: %d",
                        currentLocation.getLatitude(), currentLocation.getLongitude(),
                        currentLocation.getAccuracy(), satellitesUsed));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cameraThread == null) startBackgroundThread();
        if (textureView != null && textureView.isAvailable()
                && cameraDevice == null
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        super.onPause();
    }

    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(locationListener);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    locationManager.unregisterGnssStatusCallback(gnssCallback);
                }
            } catch (Exception ignored) { }
        }
        closeCamera();
        stopBackgroundThread();
        super.onDestroy();
    }
}
