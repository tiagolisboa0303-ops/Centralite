package com.centrallite.dashboard;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity implements LocationListener {
    private DashboardView dashboard;
    private LocationManager locationManager;
    private BroadcastReceiver batteryReceiver;
    private BroadcastReceiver bluetoothReceiver;
    private BluetoothAdapter bluetoothAdapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Boolean lastChargingState = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        dashboard = new DashboardView(this);
        setContentView(dashboard);
        enterImmersiveMode();

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        startBluetoothMonitor();
        startBatteryMonitor();
        startGps();

        // On a normal launcher start, give Android a moment to finish booting the UI
        // and then try the already-paired Ford SYNC automatically.
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                autoConnectSync(false);
            }
        }, 1400);
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void startGps() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 100);
            return;
        }
        requestLocationUpdates();
    }

    @SuppressWarnings("MissingPermission")
    private void requestLocationUpdates() {
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
            dashboard.gpsStatus = "Procurando";
        } catch (Exception e) {
            dashboard.gpsStatus = "Indisponível";
        }
        dashboard.invalidate();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestLocationUpdates();
        } else {
            dashboard.gpsStatus = "Sem permissão";
            dashboard.invalidate();
        }
    }

    private void startBatteryMonitor() {
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean chargingNow = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL;

                dashboard.battery = scale > 0 ? Math.round(level * 100f / scale) : 0;
                dashboard.charging = chargingNow;

                // We use external power as the practical ignition signal for this tablet setup.
                if (lastChargingState == null) {
                    lastChargingState = chargingNow;
                    if (chargingNow) {
                        dashboard.startIgnitionAnimation();
                        handler.postDelayed(new Runnable() {
                            @Override public void run() { autoConnectSync(false); }
                        }, 900);
                    }
                } else if (lastChargingState != chargingNow) {
                    boolean wasCharging = lastChargingState;
                    lastChargingState = chargingNow;
                    if (!wasCharging && chargingNow) {
                        dashboard.startIgnitionAnimation();
                        handler.postDelayed(new Runnable() {
                            @Override public void run() { autoConnectSync(false); }
                        }, 900);
                    } else if (wasCharging && !chargingNow) {
                        dashboard.startHazardAnimation();
                    }
                }
                dashboard.invalidate();
            }
        };
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private void startBluetoothMonitor() {
        bluetoothReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && isSyncDevice(device)) {
                    if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                        dashboard.syncStatus = "Conectado";
                    } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                        dashboard.syncStatus = "Desconectado";
                    }
                    dashboard.invalidate();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        registerReceiver(bluetoothReceiver, filter);
    }

    @Override
    public void onLocationChanged(Location location) {
        float kmh = location.hasSpeed() ? location.getSpeed() * 3.6f : 0f;
        if (kmh < 1.5f) kmh = 0f;
        dashboard.speed = Math.round(kmh);
        dashboard.gpsStatus = "Conectado";
        dashboard.invalidate();
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public void onProviderEnabled(String provider) { dashboard.gpsStatus = "Ligado"; dashboard.invalidate(); }
    @Override public void onProviderDisabled(String provider) { dashboard.gpsStatus = "Desligado"; dashboard.invalidate(); }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        // If the user came back from Bluetooth settings, refresh the SYNC state.
        handler.postDelayed(new Runnable() {
            @Override public void run() { updateSyncStatus(); }
        }, 500);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (batteryReceiver != null) {
            try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) { }
        }
        if (bluetoothReceiver != null) {
            try { unregisterReceiver(bluetoothReceiver); } catch (Exception ignored) { }
        }
        if (locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (Exception ignored) { }
        }
        handler.removeCallbacksAndMessages(null);
        if (dashboard != null) dashboard.stopAnimations();
    }

    private void launchPackage(String pkg, String fallbackUri) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) {
            startActivity(launch);
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUri)));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
            } catch (Exception ignored) { }
        }
    }

    private boolean isSyncDevice(BluetoothDevice device) {
        if (device == null) return false;
        String name = null;
        try { name = device.getName(); } catch (Exception ignored) { }
        if (name == null) return false;
        String upper = name.toUpperCase(Locale.US);
        return upper.contains("SYNC") || upper.contains("FORD");
    }

    private BluetoothDevice findPairedSyncDevice() {
        if (bluetoothAdapter == null) return null;
        try {
            Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice device : bonded) {
                    if (isSyncDevice(device)) return device;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void updateSyncStatus() {
        if (bluetoothAdapter == null) {
            dashboard.syncStatus = "Sem Bluetooth";
        } else if (!bluetoothAdapter.isEnabled()) {
            dashboard.syncStatus = "Bluetooth off";
        } else {
            BluetoothDevice sync = findPairedSyncDevice();
            if (sync == null) {
                dashboard.syncStatus = "Pareie 1x";
            } else if (bluetoothAdapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED) {
                dashboard.syncStatus = "Conectado";
            } else {
                dashboard.syncStatus = "Pronto";
            }
        }
        dashboard.invalidate();
    }

    /**
     * Android 5.1 can turn Bluetooth on programmatically. Ford SYNC normally reconnects
     * automatically to a bonded device when both sides become available. We also make a
     * best-effort A2DP reconnect through the legacy profile API used by old Android builds.
     * If that hidden method is blocked by the device ROM, the normal system auto-reconnect
     * still remains available and the manual Bluetooth button is the fallback.
     */
    private void autoConnectSync(final boolean userInitiated) {
        if (bluetoothAdapter == null) {
            dashboard.syncStatus = "Sem Bluetooth";
            dashboard.invalidate();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            dashboard.syncStatus = "Ligando BT...";
            dashboard.invalidate();
            try { bluetoothAdapter.enable(); } catch (Exception ignored) { }
            handler.postDelayed(new Runnable() {
                @Override public void run() { autoConnectSync(userInitiated); }
            }, 2600);
            return;
        }

        final BluetoothDevice syncDevice = findPairedSyncDevice();
        if (syncDevice == null) {
            dashboard.syncStatus = "Pareie 1x";
            dashboard.invalidate();
            if (userInitiated) {
                try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); } catch (Exception ignored) { }
            }
            return;
        }

        if (bluetoothAdapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED) {
            dashboard.syncStatus = "Conectado";
            dashboard.invalidate();
            return;
        }

        dashboard.syncStatus = "Conectando...";
        dashboard.invalidate();

        try {
            bluetoothAdapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(final int profile, final BluetoothProfile proxy) {
                    boolean alreadyConnected = false;
                    try { alreadyConnected = proxy.getConnectedDevices().contains(syncDevice); } catch (Exception ignored) { }

                    if (alreadyConnected) {
                        dashboard.syncStatus = "Conectado";
                    } else {
                        boolean requested = false;
                        try {
                            Method connect = proxy.getClass().getMethod("connect", BluetoothDevice.class);
                            connect.setAccessible(true);
                            Object result = connect.invoke(proxy, syncDevice);
                            requested = !(result instanceof Boolean) || ((Boolean) result);
                        } catch (Exception ignored) { }
                        dashboard.syncStatus = requested ? "Conectando..." : "Aguardando SYNC";
                    }
                    dashboard.invalidate();

                    handler.postDelayed(new Runnable() {
                        @Override public void run() {
                            try { bluetoothAdapter.closeProfileProxy(profile, proxy); } catch (Exception ignored) { }
                            updateSyncStatus();
                        }
                    }, 3500);
                }

                @Override public void onServiceDisconnected(int profile) { }
            }, BluetoothProfile.A2DP);
        } catch (Exception e) {
            dashboard.syncStatus = "Aguardando SYNC";
            dashboard.invalidate();
        }
    }

    private class DashboardView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect src = new Rect();
        private final RectF dst = new RectF();
        private final RectF[] buttons = new RectF[7];
        private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, dd 'de' MMMM 'de' yyyy", new Locale("pt", "BR"));
        private Bitmap background;

        int speed = 0;
        int battery = 0;
        boolean charging = false;
        String gpsStatus = "Iniciando";
        String syncStatus = "Iniciando";

        // animationMode: 0 idle, 1 ignition/headlights, 2 shutdown/hazards
        int animationMode = 0;
        boolean animationLightsOn = false;
        int animationTogglesLeft = 0;
        boolean idleGlow = false;
        long touchDownAt = 0L;
        int touchDownButton = -1;

        DashboardView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            stroke.setStyle(Paint.Style.STROKE);
            background = BitmapFactory.decodeResource(getResources(), R.drawable.central_fusion_bg);
            if (background != null) {
                src.set(0, 0, background.getWidth(), background.getHeight());
            }
            post(clockTick);
            postDelayed(idleGlowTick, 6000);
        }

        private final Runnable clockTick = new Runnable() {
            @Override public void run() {
                invalidate();
                postDelayed(this, 1000);
            }
        };

        private final Runnable animationTick = new Runnable() {
            @Override public void run() {
                if (animationTogglesLeft <= 0) {
                    animationLightsOn = false;
                    animationMode = 0;
                    invalidate();
                    return;
                }
                animationLightsOn = !animationLightsOn;
                animationTogglesLeft--;
                invalidate();
                postDelayed(this, animationMode == 1 ? 300 : 430);
            }
        };

        private final Runnable idleGlowTick = new Runnable() {
            @Override public void run() {
                idleGlow = !idleGlow;
                invalidate();
                postDelayed(this, idleGlow ? 650 : 7350);
            }
        };

        void startIgnitionAnimation() {
            removeCallbacks(animationTick);
            animationMode = 1;
            animationLightsOn = false;
            animationTogglesLeft = 6; // 3 complete flashes
            post(animationTick);
        }

        void startHazardAnimation() {
            removeCallbacks(animationTick);
            animationMode = 2;
            animationLightsOn = false;
            animationTogglesLeft = 6; // 3 complete hazard flashes
            post(animationTick);
        }

        void stopAnimations() {
            removeCallbacks(clockTick);
            removeCallbacks(animationTick);
            removeCallbacks(idleGlowTick);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            if (background != null) {
                dst.set(0, 0, w, h);
                c.drawBitmap(background, src, dst, paint);
            } else {
                c.drawColor(Color.BLACK);
            }

            prepareButtons(w, h);
            drawDynamicClock(c, w, h);
            drawDynamicSpeed(c, w, h);
            drawDynamicBattery(c, w, h);
            drawDynamicGps(c, w, h);
            drawSyncTile(c, w, h);
            drawChromeTile(c, w, h);
            drawNewPipeTile(c, w, h);
            drawCarAnimation(c, w, h);
        }

        private void drawDynamicClock(Canvas c, int w, int h) {
            paint.setColor(Color.rgb(7, 11, 15));
            c.drawRect(w * 0.020f, h * 0.095f, w * 0.275f, h * 0.250f, paint);
            c.drawRect(w * 0.025f, h * 0.292f, w * 0.280f, h * 0.345f, paint);
            c.drawRect(w * 0.875f, h * 0.010f, w * 0.945f, h * 0.060f, paint);

            text.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(h * 0.150f);
            c.drawText(timeFmt.format(new Date()), w * 0.035f, h * 0.235f, text);

            text.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            text.setTextSize(h * 0.030f);
            text.setColor(Color.rgb(220, 220, 224));
            String date = dateFmt.format(new Date());
            if (date.length() > 0) date = date.substring(0,1).toUpperCase(new Locale("pt","BR")) + date.substring(1);
            c.drawText(date, w * 0.040f, h * 0.327f, text);

            text.setTextAlign(Paint.Align.RIGHT);
            text.setTextSize(h * 0.030f);
            text.setColor(Color.WHITE);
            c.drawText(timeFmt.format(new Date()), w * 0.925f, h * 0.045f, text);
        }

        private void drawDynamicSpeed(Canvas c, int w, int h) {
            paint.setColor(Color.rgb(8, 11, 15));
            c.drawCircle(w * 0.868f, h * 0.280f, h * 0.062f, paint);

            text.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(h * 0.110f);
            text.setColor(Color.WHITE);
            c.drawText(String.valueOf(speed), w * 0.868f, h * 0.310f, text);
        }

        private void drawDynamicBattery(Canvas c, int w, int h) {
            paint.setColor(Color.rgb(16, 21, 28));
            c.drawRect(w * 0.095f, h * 0.470f, w * 0.180f, h * 0.575f, paint);

            text.setTextAlign(Paint.Align.LEFT);
            text.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
            text.setTextSize(h * 0.052f);
            text.setColor(Color.WHITE);
            c.drawText(battery + "%", w * 0.100f, h * 0.528f, text);

            text.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            text.setTextSize(h * 0.024f);
            text.setColor(Color.rgb(105, 230, 85));
            c.drawText(charging ? "Carregando" : "Em uso", w * 0.100f, h * 0.565f, text);
        }

        private void drawDynamicGps(Canvas c, int w, int h) {
            paint.setColor(Color.rgb(16, 21, 28));
            c.drawRect(w * 0.835f, h * 0.500f, w * 0.950f, h * 0.545f, paint);

            text.setTextAlign(Paint.Align.LEFT);
            text.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            text.setTextSize(h * 0.024f);
            text.setColor(Color.rgb(105, 230, 85));
            c.drawText(gpsStatus, w * 0.845f, h * 0.530f, text);
        }

        private void prepareButtons(int w, int h) {
            float top = h * 0.640f;
            float bottom = h * 0.855f;
            float[][] xs = {
                    {0.018f, 0.155f},
                    {0.165f, 0.298f},
                    {0.307f, 0.435f},
                    {0.445f, 0.568f},
                    {0.577f, 0.698f},
                    {0.707f, 0.833f},
                    {0.842f, 0.978f}
            };
            for (int i = 0; i < buttons.length; i++) {
                buttons[i] = new RectF(w * xs[i][0], top, w * xs[i][1], bottom);
            }
        }

        /** Replace the old Spotify tile visually without touching the approved background artwork. */
        private void drawSyncTile(Canvas c, int w, int h) {
            RectF r = buttons[2];
            if (r == null) return;
            float radius = h * 0.020f;

            paint.setColor(Color.rgb(25, 29, 38));
            c.drawRoundRect(r, radius, radius, paint);
            stroke.setColor(Color.rgb(78, 82, 90));
            stroke.setStrokeWidth(Math.max(1f, h * 0.0015f));
            c.drawRoundRect(r, radius, radius, stroke);

            float cx = r.centerX();
            float iconCy = r.top + r.height() * 0.38f;
            paint.setColor(Color.rgb(34, 112, 230));
            c.drawOval(new RectF(cx - r.width() * 0.25f, iconCy - r.height() * 0.22f,
                    cx + r.width() * 0.25f, iconCy + r.height() * 0.22f), paint);

            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
            text.setTextSize(h * 0.032f);
            text.setColor(Color.WHITE);
            c.drawText("SYNC", cx, iconCy + h * 0.010f, text);

            text.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            text.setTextSize(h * 0.035f);
            c.drawText("SYNC", cx, r.top + r.height() * 0.80f, text);

            text.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            text.setTextSize(h * 0.0175f);
            text.setColor(syncStatus.equals("Conectado") ? Color.rgb(105, 230, 85) : Color.rgb(185, 190, 198));
            c.drawText(syncStatus, cx, r.top + r.height() * 0.925f, text);
        }


        /** Replace the old Música tile with a lightweight Google Chrome shortcut. */
        private void drawChromeTile(Canvas c, int w, int h) {
            RectF r = buttons[4];
            if (r == null) return;
            float radius = h * 0.020f;

            paint.setColor(Color.rgb(25, 29, 38));
            c.drawRoundRect(r, radius, radius, paint);
            stroke.setColor(Color.rgb(78, 82, 90));
            stroke.setStrokeWidth(Math.max(1f, h * 0.0015f));
            c.drawRoundRect(r, radius, radius, stroke);

            float cx = r.centerX();
            float cy = r.top + r.height() * 0.38f;
            float rr = Math.min(r.width(), r.height()) * 0.23f;

            // Chrome-style tri-color ring, drawn directly to keep the APK light.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(219, 68, 55));
            c.drawArc(new RectF(cx-rr, cy-rr, cx+rr, cy+rr), 210, 120, true, paint);
            paint.setColor(Color.rgb(244, 180, 0));
            c.drawArc(new RectF(cx-rr, cy-rr, cx+rr, cy+rr), 330, 120, true, paint);
            paint.setColor(Color.rgb(15, 157, 88));
            c.drawArc(new RectF(cx-rr, cy-rr, cx+rr, cy+rr), 90, 120, true, paint);

            paint.setColor(Color.rgb(66, 133, 244));
            c.drawCircle(cx, cy, rr * 0.48f, paint);
            stroke.setColor(Color.rgb(235, 235, 235));
            stroke.setStrokeWidth(Math.max(1f, rr * 0.10f));
            c.drawCircle(cx, cy, rr * 0.51f, stroke);

            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            text.setTextSize(h * 0.035f);
            text.setColor(Color.WHITE);
            c.drawText("Chrome", cx, r.top + r.height() * 0.80f, text);
        }

        /** Replace Configurações with a NewPipe / YouTube Música shortcut. */
        private void drawNewPipeTile(Canvas c, int w, int h) {
            RectF r = buttons[5];
            if (r == null) return;
            float radius = h * 0.020f;

            paint.setColor(Color.rgb(25, 29, 38));
            c.drawRoundRect(r, radius, radius, paint);
            stroke.setColor(Color.rgb(78, 82, 90));
            stroke.setStrokeWidth(Math.max(1f, h * 0.0015f));
            c.drawRoundRect(r, radius, radius, stroke);

            float cx = r.centerX();
            float cy = r.top + r.height() * 0.37f;
            float iw = r.width() * 0.44f;
            float ih = r.height() * 0.30f;

            // YouTube-like red player icon. It is intentionally generic; NewPipe opens on tap.
            paint.setColor(Color.rgb(230, 35, 35));
            c.drawRoundRect(new RectF(cx - iw / 2f, cy - ih / 2f,
                    cx + iw / 2f, cy + ih / 2f), ih * 0.25f, ih * 0.25f, paint);

            Path play = new Path();
            play.moveTo(cx - iw * 0.075f, cy - ih * 0.22f);
            play.lineTo(cx - iw * 0.075f, cy + ih * 0.22f);
            play.lineTo(cx + iw * 0.18f, cy);
            play.close();
            paint.setColor(Color.WHITE);
            c.drawPath(play, paint);

            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            text.setTextSize(h * 0.032f);
            text.setColor(Color.WHITE);
            c.drawText("YouTube", cx, r.top + r.height() * 0.76f, text);

            text.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            text.setTextSize(h * 0.019f);
            text.setColor(Color.rgb(190, 195, 202));
            c.drawText("Música • NewPipe", cx, r.top + r.height() * 0.91f, text);
        }

        private void drawCarAnimation(Canvas c, int w, int h) {
            // Very subtle idle glow so the car feels alive without continuously taxing this old tablet.
            if (idleGlow && animationMode == 0) {
                paint.setColor(Color.argb(18, 120, 175, 255));
                c.drawOval(new RectF(w * 0.285f, h * 0.305f, w * 0.765f, h * 0.650f), paint);
            }

            if (!animationLightsOn) return;

            if (animationMode == 1) {
                // Headlight flash: white/blue soft glows positioned over the visible Fusion headlights.
                paint.setColor(Color.argb(175, 235, 247, 255));
                c.drawOval(new RectF(w * 0.425f, h * 0.425f, w * 0.555f, h * 0.505f), paint);
                paint.setColor(Color.argb(105, 205, 230, 255));
                c.drawOval(new RectF(w * 0.255f, h * 0.420f, w * 0.320f, h * 0.495f), paint);
            } else if (animationMode == 2) {
                // Hazard flash: amber glows on both front lamp areas.
                paint.setColor(Color.argb(195, 255, 155, 35));
                c.drawOval(new RectF(w * 0.515f, h * 0.420f, w * 0.555f, h * 0.490f), paint);
                c.drawOval(new RectF(w * 0.255f, h * 0.423f, w * 0.292f, h * 0.488f), paint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                touchDownAt = System.currentTimeMillis();
                touchDownButton = findButtonAt(event.getX(), event.getY());
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                int button = findButtonAt(event.getX(), event.getY());
                long held = System.currentTimeMillis() - touchDownAt;
                if (button >= 0 && button == touchDownButton) {
                    // Configurações continuam acessíveis: segure Aplicativos por ~0,7 s.
                    if (button == 6 && held >= 650) {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    } else {
                        handleButton(button);
                    }
                }
                touchDownButton = -1;
                return true;
            }
            return true;
        }

        private int findButtonAt(float x, float y) {
            for (int i = 0; i < buttons.length; i++) {
                if (buttons[i] != null && buttons[i].contains(x, y)) return i;
            }
            return -1;
        }

        private void handleButton(int index) {
            switch (index) {
                case 0:
                    launchPackage("com.waze", "waze://?navigate=yes");
                    break;
                case 1:
                    launchPackage("com.google.android.apps.maps", "geo:0,0?q=");
                    break;
                case 2:
                    autoConnectSync(true);
                    break;
                case 3:
                    startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                    break;
                case 4:
                    launchPackage("com.android.chrome", "https://www.google.com");
                    break;
                case 5:
                    // NewPipe supports background audio on old Android versions.
                    // If it is not installed yet, open the official download page in the browser.
                    Intent newPipe = getPackageManager().getLaunchIntentForPackage("org.schabi.newpipe");
                    if (newPipe != null) {
                        startActivity(newPipe);
                    } else {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://newpipe.net/#download")));
                        } catch (Exception ignored) { }
                    }
                    break;
                case 6:
                    startActivity(new Intent(MainActivity.this, AppsActivity.class));
                    break;
            }
        }
    }
}
