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
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.view.KeyEvent;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.speech.tts.TextToSpeech;

import java.io.File;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Calendar;
import java.util.Locale;
import java.util.Set;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

public class MainActivity extends Activity implements LocationListener {
    private FrameLayout root;
    private DashboardView dashboard;
    private CarMotionView carMotion;
    private FordSplashView fordSplash;
    private FrameLayout mapCard;
    private FrameLayout musicCard;
    private TextView musicTitleView;
    private TextView musicSourceView;
    private boolean musicPanelVisible = false;
    private MapView miniMap;
    private Marker mapMarker;
    private TextView speedBadge;
    private boolean mapCentered = false;
    private LocationManager locationManager;
    private BroadcastReceiver batteryReceiver;
    private BroadcastReceiver bluetoothReceiver;
    private BluetoothAdapter bluetoothAdapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Boolean lastChargingState = null;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean greetingPending = false;
    private long lastGreetingAt = 0L;
    private long lastShutdownSequenceAt = 0L;
    private MediaPlayer shutdownPlayer;
    private boolean parkingMode = false;
    private long lastStartupSequenceAt = 0L;

    private final Runnable parkingRunnable = new Runnable() {
        @Override public void run() {
            enterParkingMode();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        root = new FrameLayout(this);
        dashboard = new DashboardView(this);
        root.addView(dashboard, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        carMotion = new CarMotionView(this);
        root.addView(carMotion, new FrameLayout.LayoutParams(1, 1));

        fordSplash = new FordSplashView(this);
        root.addView(fordSplash, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        setupMiniMap();
        setupMusicPanel();
        positionOverlayViews();
        enterImmersiveMode();
        initVoiceAssistant();

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

        if (getIntent() != null && getIntent().getBooleanExtra("wake_from_power", false)) {
            handler.postDelayed(new Runnable() {
                @Override public void run() { handleIgnitionWake(); }
            }, 250);
        }
        if (getIntent() != null && getIntent().getBooleanExtra("shutdown_from_power", false)) {
            handler.postDelayed(new Runnable() {
                @Override public void run() { handleIgnitionOff(); }
            }, 150);
        }

        handler.postDelayed(new Runnable() {
            @Override public void run() { playStartupSequence(); }
        }, 350);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("wake_from_power", false)) {
            handleIgnitionWake();
        }
        if (intent != null && intent.getBooleanExtra("shutdown_from_power", false)) {
            handleIgnitionOff();
        }
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
            try {
                Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (last != null) updateMiniMap(last);
            } catch (Exception ignored) { }
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
                        playStartupSequence();
                    }
                } else if (lastChargingState != chargingNow) {
                    boolean wasCharging = lastChargingState;
                    lastChargingState = chargingNow;
                    if (!wasCharging && chargingNow) {
                        playStartupSequence();
                    } else if (wasCharging && !chargingNow) {
                        handleIgnitionOff();
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
        if (speedBadge != null) speedBadge.setText(dashboard.speed + " km/h");
        updateMiniMap(location);
        dashboard.invalidate();
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public void onProviderEnabled(String provider) { dashboard.gpsStatus = "Ligado"; dashboard.invalidate(); }
    @Override public void onProviderDisabled(String provider) { dashboard.gpsStatus = "Desligado"; dashboard.invalidate(); }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        if (miniMap != null) miniMap.onResume();
        // If the user came back from Bluetooth settings, refresh the SYNC state.
        handler.postDelayed(new Runnable() {
            @Override public void run() { updateSyncStatus(); }
        }, 500);
    }

    @Override
    protected void onPause() {
        if (miniMap != null) miniMap.onPause();
        super.onPause();
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
        if (carMotion != null) carMotion.stopAnimations();
        if (miniMap != null) {
            try { miniMap.onDetach(); } catch (Exception ignored) { }
        }
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }
            tts = null;
        }
        if (shutdownPlayer != null) {
            try { shutdownPlayer.release(); } catch (Exception ignored) { }
            shutdownPlayer = null;
        }
    }

    private void handleIgnitionOff() {
        long now = SystemClock.uptimeMillis();
        if (lastShutdownSequenceAt != 0L && now - lastShutdownSequenceAt < 2500L) return;
        lastShutdownSequenceAt = now;
        lastChargingState = false;
        handler.removeCallbacks(parkingRunnable);
        if (carMotion != null) {
            carMotion.startAnimations();
            carMotion.startHazardAnimation();
        }
        playShutdownChime();
        scheduleParkingMode();
    }

    private void scheduleParkingMode() {
        handler.removeCallbacks(parkingRunnable);
        // Give the hazard animation and shutdown chime time to finish first.
        handler.postDelayed(parkingRunnable, 3600);
    }

    private void enterParkingMode() {
        if (parkingMode) return;
        parkingMode = true;

        // Stop the work that matters most for battery drain while the car is parked.
        try {
            if (locationManager != null) locationManager.removeUpdates(this);
        } catch (Exception ignored) { }
        if (miniMap != null) {
            try { miniMap.onPause(); } catch (Exception ignored) { }
        }
        if (carMotion != null) carMotion.stopAnimations();
        if (dashboard != null) dashboard.stopAnimations();

        // Let the shutdown sound finish over SYNC, then turn Bluetooth off for parking.
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            try { bluetoothAdapter.disable(); } catch (Exception ignored) { }
        }

        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        // Android 5.1: avoid Device Administrator/lockNow because Play Protect can block
        // sideloaded apps that request sensitive device-control capabilities.
        // Clearing KEEP_SCREEN_ON lets the tablet use its normal screen timeout.
        // We dim immediately so parked battery drain is minimal while the timeout counts down.
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = 0.01f;
            getWindow().setAttributes(lp);
        } catch (Exception ignored) { }
    }

    private void exitParkingMode() {
        handler.removeCallbacks(parkingRunnable);
        boolean wasParking = parkingMode;
        parkingMode = false;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
        } catch (Exception ignored) { }

        if (!wasParking) return;

        if (dashboard != null) dashboard.startAnimations();
        if (carMotion != null) carMotion.startAnimations();
        if (miniMap != null) {
            try { miniMap.onResume(); } catch (Exception ignored) { }
        }
        requestLocationUpdates();

        if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
            try { bluetoothAdapter.enable(); } catch (Exception ignored) { }
        }
        handler.postDelayed(new Runnable() {
            @Override public void run() { autoConnectSync(false); }
        }, 1800);
    }

    private void handleIgnitionWake() {
        // On Android 5.1 these flags allow the dedicated car dashboard to appear immediately
        // after power returns. A secure PIN/pattern can still require the user's unlock.
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Exception ignored) { }
        playStartupSequence();
        enterImmersiveMode();
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                try { getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON); }
                catch (Exception ignored) { }
            }
        }, 2200);
    }

    private void playStartupSequence() {
        long now = SystemClock.uptimeMillis();
        if (lastStartupSequenceAt != 0L && now - lastStartupSequenceAt < 3500L) return;
        lastStartupSequenceAt = now;

        exitParkingMode();
        if (fordSplash != null) fordSplash.play();
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (carMotion != null) carMotion.startIgnitionAnimation();
                if (dashboard != null) dashboard.startIgnitionAnimation();
            }
        }, 1650);
        handler.postDelayed(new Runnable() {
            @Override public void run() { autoConnectSync(false); }
        }, 1050);
        handler.postDelayed(new Runnable() {
            @Override public void run() { scheduleStartupGreeting(); }
        }, 1700);
    }

    private void initVoiceAssistant() {
        TextToSpeech.OnInitListener listener = new TextToSpeech.OnInitListener() {
            @Override public void onInit(int status) {
                if (status != TextToSpeech.SUCCESS || tts == null) return;
                int result = tts.setLanguage(new Locale("pt", "BR"));
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED;
                if (!ttsReady) {
                    try {
                        tts.setLanguage(Locale.getDefault());
                        ttsReady = true;
                    } catch (Exception ignored) { }
                }
                try {
                    // Calmer, less synthetic cadence for an automotive-assistant feel.
                    tts.setSpeechRate(0.86f);
                    tts.setPitch(0.92f);
                    if (android.os.Build.VERSION.SDK_INT >= 21) {
                        tts.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build());
                    }
                } catch (Exception ignored) { }
                if (ttsReady && greetingPending) {
                    greetingPending = false;
                    speakStartupGreeting();
                }
            }
        };

        // Prefer Google Text-to-Speech when installed; it is considerably more natural
        // than the old Samsung/Pico engine commonly found on Android 5.1.
        try {
            getPackageManager().getApplicationInfo("com.google.android.tts", 0);
            tts = new TextToSpeech(this, listener, "com.google.android.tts");
        } catch (Exception notInstalled) {
            tts = new TextToSpeech(this, listener);
        }
    }

    private void scheduleStartupGreeting() {
        // Give Ford SYNC a few seconds to reconnect first, so the greeting usually plays in the car.
        handler.postDelayed(new Runnable() {
            @Override public void run() { speakStartupGreeting(); }
        }, 4200);
    }

    private void speakStartupGreeting() {
        long now = SystemClock.uptimeMillis();
        if (lastGreetingAt != 0L && now - lastGreetingAt < 30000L) return;
        if (!ttsReady || tts == null) {
            greetingPending = true;
            return;
        }

        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String greeting;
        if (hour >= 5 && hour < 12) greeting = "Bom dia";
        else if (hour >= 12 && hour < 18) greeting = "Boa tarde";
        else greeting = "Boa noite";

        String message = greeting + ". Bem-vindo. Coloque o cinto de segurança e verifique os faróis. Boa viagem.";
        lastGreetingAt = now;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "central_lite_startup");
            } else {
                tts.speak(message, TextToSpeech.QUEUE_FLUSH, null);
            }
        } catch (Exception ignored) { }
    }

    private void playShutdownChime() {
        try {
            if (shutdownPlayer != null) {
                try { shutdownPlayer.release(); } catch (Exception ignored) { }
                shutdownPlayer = null;
            }
            shutdownPlayer = MediaPlayer.create(this, R.raw.shutdown_chime);
            if (shutdownPlayer == null) return;
            try { shutdownPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC); } catch (Exception ignored) { }
            shutdownPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) {
                    try { mp.release(); } catch (Exception ignored) { }
                    if (shutdownPlayer == mp) shutdownPlayer = null;
                }
            });
            shutdownPlayer.start();
        } catch (Exception ignored) { }
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

    private void setupMiniMap() {
        try {
            IConfigurationProvider config = Configuration.getInstance();
            config.setUserAgentValue(getPackageName());
            File basePath = new File(getCacheDir(), "osmdroid");
            File tilePath = new File(basePath, "tiles");
            if (!basePath.exists()) basePath.mkdirs();
            if (!tilePath.exists()) tilePath.mkdirs();
            config.setOsmdroidBasePath(basePath);
            config.setOsmdroidTileCache(tilePath);
        } catch (Exception ignored) { }

        mapCard = new FrameLayout(this);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.rgb(14, 18, 24));
        cardBg.setCornerRadius(dp(14));
        cardBg.setStroke(dp(2), Color.rgb(92, 104, 118));
        mapCard.setBackground(cardBg);
        mapCard.setPadding(dp(2), dp(2), dp(2), dp(2));
        mapCard.setClipToOutline(true);

        miniMap = new MapView(this);
        miniMap.setTileSource(TileSourceFactory.MAPNIK);
        miniMap.setBuiltInZoomControls(false);
        miniMap.setMultiTouchControls(true);
        miniMap.setTilesScaledToDpi(true);
        miniMap.getController().setZoom(16.0);
        mapCard.addView(miniMap, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView mapLabel = makePill("MAPA", 11);
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT);
        labelLp.leftMargin = dp(8);
        labelLp.topMargin = dp(8);
        mapCard.addView(mapLabel, labelLp);

        TextView openMaps = makePill("NAVEGAR ↗", 10);
        openMaps.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                launchNavigator();
            }
        });
        FrameLayout.LayoutParams openLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT);
        openLp.rightMargin = dp(8);
        openLp.topMargin = dp(8);
        mapCard.addView(openMaps, openLp);

        TextView openMusic = makePill("MÚSICA", 9);
        openMusic.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMusicPanel(); }
        });
        FrameLayout.LayoutParams musicLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        musicLp.topMargin = dp(8);
        mapCard.addView(openMusic, musicLp);

        speedBadge = makePill("0 km/h", 15);
        speedBadge.setTextColor(Color.WHITE);
        FrameLayout.LayoutParams speedLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.LEFT);
        speedLp.leftMargin = dp(8);
        speedLp.bottomMargin = dp(8);
        mapCard.addView(speedBadge, speedLp);

        TextView attribution = new TextView(this);
        attribution.setText("© OpenStreetMap");
        attribution.setTextColor(Color.rgb(245, 245, 245));
        attribution.setTextSize(8);
        attribution.setPadding(dp(4), dp(2), dp(4), dp(2));
        GradientDrawable attrBg = new GradientDrawable();
        attrBg.setColor(Color.argb(165, 0, 0, 0));
        attrBg.setCornerRadius(dp(5));
        attribution.setBackground(attrBg);
        FrameLayout.LayoutParams attrLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.RIGHT);
        attrLp.rightMargin = dp(6);
        attrLp.bottomMargin = dp(6);
        mapCard.addView(attribution, attrLp);

        root.addView(mapCard, new FrameLayout.LayoutParams(1, 1));
    }

    private void setupMusicPanel() {
        musicCard = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(12, 16, 23));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(2), Color.rgb(74, 92, 116));
        musicCard.setBackground(bg);
        musicCard.setPadding(dp(10), dp(10), dp(10), dp(10));
        musicCard.setVisibility(View.GONE);

        TextView label = makePill("TOCANDO AGORA", 10);
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT);
        musicCard.addView(label, labelLp);

        TextView mapButton = makePill("MAPA ↗", 10);
        mapButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMapPanel(); }
        });
        FrameLayout.LayoutParams mapBtnLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT);
        musicCard.addView(mapButton, mapBtnLp);

        musicTitleView = new TextView(this);
        musicTitleView.setText("Música no Chrome");
        musicTitleView.setTextColor(Color.WHITE);
        musicTitleView.setTextSize(18);
        musicTitleView.setGravity(Gravity.CENTER);
        musicTitleView.setMaxLines(3);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        titleLp.leftMargin = dp(6);
        titleLp.rightMargin = dp(6);
        titleLp.topMargin = dp(10);
        musicCard.addView(musicTitleView, titleLp);

        musicSourceView = new TextView(this);
        musicSourceView.setText("Controles de reprodução");
        musicSourceView.setTextColor(Color.rgb(170, 182, 198));
        musicSourceView.setTextSize(11);
        musicSourceView.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams sourceLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        sourceLp.bottomMargin = dp(70);
        musicCard.addView(musicSourceView, sourceLp);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        TextView prev = makeMediaControl("◀◀");
        TextView play = makeMediaControl("▶ ❚❚");
        TextView next = makeMediaControl("▶▶");
        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); }
        });
        play.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); }
        });
        controls.addView(prev);
        controls.addView(play);
        controls.addView(next);
        FrameLayout.LayoutParams controlsLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(52), Gravity.BOTTOM);
        controlsLp.bottomMargin = dp(8);
        musicCard.addView(controls, controlsLp);

        root.addView(musicCard, new FrameLayout.LayoutParams(1, 1));
    }

    private TextView makeMediaControl(String value) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(Color.WHITE);
        v.setTextSize(17);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(30, 39, 52));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), Color.rgb(84, 104, 130));
        v.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        v.setLayoutParams(lp);
        return v;
    }

    private void showMusicPanel() {
        musicPanelVisible = true;
        if (mapCard != null) mapCard.setVisibility(View.GONE);
        if (musicCard != null) {
            musicCard.setVisibility(View.VISIBLE);
            musicCard.bringToFront();
        }
        if (fordSplash != null && fordSplash.getVisibility() == View.VISIBLE) fordSplash.bringToFront();
    }

    private void showMapPanel() {
        musicPanelVisible = false;
        if (musicCard != null) musicCard.setVisibility(View.GONE);
        if (mapCard != null) {
            mapCard.setVisibility(View.VISIBLE);
            mapCard.bringToFront();
        }
        if (fordSplash != null && fordSplash.getVisibility() == View.VISIBLE) fordSplash.bringToFront();
    }

    private void dispatchMediaKey(int keyCode) {
        try {
            AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) return;
            long now = SystemClock.uptimeMillis();
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        } catch (Exception ignored) { }
    }

    private TextView makePill(String value, int textSp) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(Color.WHITE);
        v.setTextSize(textSp);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(9), dp(5), dp(9), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(205, 0, 0, 0));
        bg.setCornerRadius(dp(8));
        v.setBackground(bg);
        return v;
    }

    private void positionOverlayViews() {
        root.post(new Runnable() {
            @Override public void run() {
                int w = root.getWidth();
                int h = root.getHeight();
                if (w <= 0 || h <= 0) return;

                // Car image crop plus a little breathing room so the movement never clips.
                FrameLayout.LayoutParams carLp = (FrameLayout.LayoutParams) carMotion.getLayoutParams();
                carLp.leftMargin = Math.round(w * 0.240f);
                carLp.topMargin = Math.round(h * 0.244f);
                carLp.width = Math.round(w * 0.528f);
                carLp.height = Math.round(h * 0.451f);
                carMotion.setLayoutParams(carLp);

                // Right-side always-on map: replaces the large speedometer/GPS block.
                FrameLayout.LayoutParams mapLp = (FrameLayout.LayoutParams) mapCard.getLayoutParams();
                mapLp.leftMargin = Math.round(w * 0.770f);
                mapLp.topMargin = Math.round(h * 0.105f);
                mapLp.width = Math.round(w * 0.215f);
                mapLp.height = Math.round(h * 0.480f);
                mapCard.setLayoutParams(mapLp);

                if (musicCard != null) {
                    FrameLayout.LayoutParams musicLp = (FrameLayout.LayoutParams) musicCard.getLayoutParams();
                    musicLp.leftMargin = mapLp.leftMargin;
                    musicLp.topMargin = mapLp.topMargin;
                    musicLp.width = mapLp.width;
                    musicLp.height = mapLp.height;
                    musicCard.setLayoutParams(musicLp);
                }
                if (musicPanelVisible && musicCard != null) musicCard.bringToFront();
                else mapCard.bringToFront();
            }
        });
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.max(1, Math.round(value * density));
    }

    private void updateMiniMap(Location location) {
        if (location == null || miniMap == null) return;
        try {
            GeoPoint point = new GeoPoint(location.getLatitude(), location.getLongitude());
            if (mapMarker == null) {
                mapMarker = new Marker(miniMap);
                mapMarker.setTitle("Posição atual");
                mapMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
                miniMap.getOverlays().add(mapMarker);
            }
            mapMarker.setPosition(point);
            // Keep the map following the vehicle. Pinch/drag still works, and it recenters on the next GPS update.
            miniMap.getController().setCenter(point);
            if (!mapCentered) {
                miniMap.getController().setZoom(16.5);
                mapCentered = true;
            }
            miniMap.invalidate();
        } catch (Exception ignored) { }
    }

    private void launchNavigator() {
        // Sygic GPS Navigation & Maps. The classic/standard Android package is com.sygic.aura.
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.sygic.aura");
        if (launch != null) {
            try {
                launch.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(launch);
                return;
            } catch (Exception ignored) { }
        }

        // Fallback: open Sygic's Play Store entry if the app was removed.
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.sygic.aura")));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=com.sygic.aura")));
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

    private class CarMotionView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Rect src = new Rect();
        private final RectF dst = new RectF();
        private Bitmap carBitmap;
        private long motionStartedAt = SystemClock.uptimeMillis();
        private int lightMode = 0; // 0 idle, 1 headlights, 2 hazards
        private boolean lightsOn = false;
        private int lightTogglesLeft = 0;
        private boolean motionRunning = false;

        CarMotionView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            carBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.fusion_car_anim);
            if (carBitmap != null) src.set(0, 0, carBitmap.getWidth(), carBitmap.getHeight());
            startAnimations();
        }

        private final Runnable motionTick = new Runnable() {
            @Override public void run() {
                if (!motionRunning) return;
                invalidate();
                // ~12.5 fps is visibly smooth while staying friendly to the old SM-T280 GPU.
                postDelayed(this, 80);
            }
        };

        private final Runnable lightTick = new Runnable() {
            @Override public void run() {
                if (lightTogglesLeft <= 0) {
                    lightsOn = false;
                    lightMode = 0;
                    invalidate();
                    return;
                }
                lightsOn = !lightsOn;
                lightTogglesLeft--;
                invalidate();
                postDelayed(this, lightMode == 1 ? 300 : 420);
            }
        };

        void startIgnitionAnimation() {
            removeCallbacks(lightTick);
            lightMode = 1;
            lightsOn = false;
            lightTogglesLeft = 6; // 3 flashes
            post(lightTick);
        }

        void startHazardAnimation() {
            removeCallbacks(lightTick);
            lightMode = 2;
            lightsOn = false;
            lightTogglesLeft = 6; // 3 flashes
            post(lightTick);
        }

        void startAnimations() {
            if (motionRunning) return;
            motionRunning = true;
            motionStartedAt = SystemClock.uptimeMillis();
            post(motionTick);
        }

        void stopAnimations() {
            motionRunning = false;
            removeCallbacks(motionTick);
            removeCallbacks(lightTick);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (carBitmap == null || getWidth() <= 0 || getHeight() <= 0) return;

            int w = getWidth();
            int h = getHeight();
            // Exact crop alignment inside the padded overlay view.
            dst.set(w * 0.0239f, h * 0.0447f, w * 0.9761f, h * 0.9553f);

            float seconds = (SystemClock.uptimeMillis() - motionStartedAt) / 1000f;
            // Real visible movement: gentle lateral sway + vertical float + small presentation rotation.
            float dx = (float) Math.sin(seconds * 0.72f) * w * 0.0085f;
            float dy = (float) Math.sin(seconds * 1.05f) * h * 0.0100f;
            float rotation = (float) Math.sin(seconds * 0.48f) * 1.15f;
            float breathe = 1.0f + (float) Math.sin(seconds * 0.55f) * 0.006f;
            float yaw = 1.0f + (float) Math.sin(seconds * 0.34f) * 0.012f;

            float cx = dst.centerX();
            float cy = dst.centerY();
            c.save();
            c.translate(dx, dy);
            c.rotate(rotation, cx, cy);
            c.scale(breathe * yaw, breathe, cx, cy);
            c.drawBitmap(carBitmap, src, dst, paint);

            if (lightsOn) {
                if (lightMode == 1) drawHeadlights(c, w, h);
                else if (lightMode == 2) drawHazards(c, w, h);
            }
            c.restore();
        }

        private void drawHeadlights(Canvas c, int w, int h) {
            paint.setColor(Color.argb(190, 238, 248, 255));
            c.drawOval(new RectF(w * 0.335f, h * 0.390f, w * 0.575f, h * 0.555f), paint);
            paint.setColor(Color.argb(135, 210, 236, 255));
            c.drawOval(new RectF(w * 0.018f, h * 0.405f, w * 0.105f, h * 0.550f), paint);
        }

        private void drawHazards(Canvas c, int w, int h) {
            paint.setColor(Color.argb(205, 255, 156, 35));
            c.drawOval(new RectF(w * 0.505f, h * 0.410f, w * 0.575f, h * 0.540f), paint);
            c.drawOval(new RectF(w * 0.030f, h * 0.420f, w * 0.083f, h * 0.540f), paint);
        }
    }

    private class FordSplashView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Rect src = new Rect();
        private final RectF dst = new RectF();
        private Bitmap logoBitmap;
        private boolean showing = false;
        private long startedAt = 0L;

        FordSplashView(Context context) {
            super(context);
            setVisibility(View.GONE);
            setClickable(false);
            logoBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.ford_logo_start);
            if (logoBitmap != null) src.set(0, 0, logoBitmap.getWidth(), logoBitmap.getHeight());
        }

        private final Runnable frameTick = new Runnable() {
            @Override public void run() {
                if (!showing) return;
                invalidate();
                if (SystemClock.uptimeMillis() - startedAt >= 2500L) {
                    showing = false;
                    setVisibility(View.GONE);
                    return;
                }
                postDelayed(this, 40);
            }
        };

        void play() {
            showing = true;
            startedAt = SystemClock.uptimeMillis();
            setVisibility(View.VISIBLE);
            bringToFront();
            removeCallbacks(frameTick);
            post(frameTick);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (!showing) return;
            int w = getWidth(), h = getHeight();
            float elapsed = SystemClock.uptimeMillis() - startedAt;
            float alpha;
            if (elapsed < 280f) alpha = elapsed / 280f;
            else if (elapsed > 2150f) alpha = Math.max(0f, 1f - ((elapsed - 2150f) / 350f));
            else alpha = 1f;
            float scale = 0.92f + Math.min(1f, elapsed / 550f) * 0.08f;

            paint.setColor(Color.argb((int)(230 * alpha), 6, 10, 18));
            c.drawRect(0, 0, w, h, paint);

            float cx = w * 0.50f;
            float cy = h * 0.43f;
            float logoW = w * 0.62f * scale;
            float logoH = logoW * 0.44f;

            paint.setColor(Color.argb((int)(52 * alpha), 90, 160, 255));
            c.drawOval(new RectF(cx - logoW * 0.63f, cy - logoH * 0.72f,
                    cx + logoW * 0.63f, cy + logoH * 0.72f), paint);

            if (logoBitmap != null) {
                dst.set(cx - logoW / 2f, cy - logoH / 2f, cx + logoW / 2f, cy + logoH / 2f);
                paint.setAlpha((int)(255 * alpha));
                c.drawBitmap(logoBitmap, src, dst, paint);
                paint.setAlpha(255);
            }

            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
            text.setTextSize(h * 0.040f);
            text.setColor(Color.argb((int)(215 * alpha), 220, 228, 240));
            c.drawText("Central Fusion", cx, cy + logoH * 0.78f + h * 0.04f, text);
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
        boolean renderingRunning = false;

        DashboardView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            stroke.setStyle(Paint.Style.STROKE);
            background = BitmapFactory.decodeResource(getResources(), R.drawable.central_fusion_bg);
            if (background != null) {
                src.set(0, 0, background.getWidth(), background.getHeight());
            }
            startAnimations();
        }

        private final Runnable clockTick = new Runnable() {
            @Override public void run() {
                if (!renderingRunning) return;
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

        void startAnimations() {
            if (renderingRunning) return;
            renderingRunning = true;
            post(clockTick);
        }

        void stopAnimations() {
            renderingRunning = false;
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
            drawNavigatorTile(c, w, h);
            drawSyncTile(c, w, h);
            drawChromeTile(c, w, h);
            drawNewPipeTile(c, w, h);
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
        /** Replace the old navigation tile with Sygic. */
        private void drawNavigatorTile(Canvas c, int w, int h) {
            RectF r = buttons[1];
            if (r == null) return;
            float radius = h * 0.020f;

            paint.setColor(Color.rgb(25, 29, 38));
            c.drawRoundRect(r, radius, radius, paint);
            stroke.setColor(Color.rgb(78, 82, 90));
            stroke.setStrokeWidth(Math.max(1f, h * 0.0015f));
            c.drawRoundRect(r, radius, radius, stroke);

            float cx = r.centerX();
            float cy = r.top + r.height() * 0.37f;
            float rr = Math.min(r.width(), r.height()) * 0.22f;

            // Lightweight navigation/compass symbol.
            paint.setColor(Color.rgb(45, 140, 235));
            c.drawCircle(cx, cy, rr, paint);
            stroke.setColor(Color.rgb(235, 245, 255));
            stroke.setStrokeWidth(Math.max(2f, rr * 0.10f));
            c.drawCircle(cx, cy, rr * 0.82f, stroke);

            Path nav = new Path();
            nav.moveTo(cx + rr * 0.12f, cy - rr * 0.62f);
            nav.lineTo(cx - rr * 0.48f, cy + rr * 0.48f);
            nav.lineTo(cx + rr * 0.04f, cy + rr * 0.22f);
            nav.lineTo(cx + rr * 0.42f, cy + rr * 0.54f);
            nav.close();
            paint.setColor(Color.WHITE);
            c.drawPath(nav, paint);

            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            text.setTextSize(h * 0.031f);
            text.setColor(Color.WHITE);
            c.drawText("Sygic", cx, r.top + r.height() * 0.78f, text);

            text.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            text.setTextSize(h * 0.018f);
            text.setColor(Color.rgb(190, 195, 202));
            c.drawText("Navegação", cx, r.top + r.height() * 0.91f, text);
        }

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
            c.drawText("Música", cx, r.top + r.height() * 0.76f, text);

            text.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            text.setTextSize(h * 0.019f);
            text.setColor(Color.rgb(190, 195, 202));
            c.drawText("NewPipe", cx, r.top + r.height() * 0.91f, text);
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
                    launchNavigator();
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
