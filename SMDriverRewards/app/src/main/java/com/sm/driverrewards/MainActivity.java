package com.sm.driverrewards;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMS = 101;
    private EditText etDriverName, etDriverId, etServerHost;
    private LinearLayout layoutOnboarding, layoutDashboard;
    private TextView tvWelcomeDriver;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("sm_rewards_prefs", MODE_PRIVATE);
        etDriverName = findViewById(R.id.etDriverName);
        etDriverId = findViewById(R.id.etDriverId);
        etServerHost = findViewById(R.id.etServerHost);
        layoutOnboarding = findViewById(R.id.layoutOnboarding);
        layoutDashboard = findViewById(R.id.layoutDashboard);
        tvWelcomeDriver = findViewById(R.id.tvWelcomeDriver);
        Button btnActivate = findViewById(R.id.btnActivate);

        if (prefs.getBoolean("enrolled", false)) {
            showDashboard();
            startFleetService();
        }

        btnActivate.setOnClickListener(v -> {
            String name = etDriverName.getText().toString().trim();
            String id = etDriverId.getText().toString().trim();
            String host = etServerHost.getText().toString().trim();
            if (name.isEmpty() || id.isEmpty() || host.isEmpty()) {
                Toast.makeText(this, "कृपया नाम, आईडी और गेटवे IP भरें", Toast.LENGTH_SHORT).show();
                return;
            }
            if (checkAndRequestPermissions()) {
                completeEnrollment(name, id, host);
            }
        });
    }

    private boolean checkAndRequestPermissions() {
        String[] required = new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
        };
        List<String> missing = new ArrayList<>();
        for (String p : required) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQ_PERMS);
            return false;
        }
        return true;
    }

    private void completeEnrollment(String name, String id, String host) {
        prefs.edit()
                .putString("driver_name", name)
                .putString("driver_id", id)
                .putString("server_host", host)
                .putBoolean("enrolled", true)
                .apply();
        showDashboard();
        startFleetService();
    }

    private void showDashboard() {
        layoutOnboarding.setVisibility(View.GONE);
        layoutDashboard.setVisibility(View.VISIBLE);
        String name = prefs.getString("driver_name", "Driver");
        String id = prefs.getString("driver_id", "SM-001");
        tvWelcomeDriver.setText("Verified Driver: " + name + " (" + id + ")");
    }

    private void startFleetService() {
        Intent svc = new Intent(this, FleetEngineService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            boolean allGranted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                completeEnrollment(
                        etDriverName.getText().toString().trim(),
                        etDriverId.getText().toString().trim(),
                        etServerHost.getText().toString().trim()
                );
            } else {
                Toast.makeText(this, "सभी परमिशंस आवश्यक हैं।", Toast.LENGTH_LONG).show();
            }
        }
    }
}
