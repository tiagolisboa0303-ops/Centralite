package com.centrallite.dashboard;

import android.Manifest;
import android.app.Activity;
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
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements LocationListener {
    private DashboardView dashboard;
    private LocationManager locationManager;
    private BroadcastReceiver batteryReceiver;

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
        startBatteryMonitor();
        startGps();
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
                dashboard.battery = scale > 0 ? Math.round(level * 100f / scale) : 0;
                dashboard.charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL;
                dashboard.invalidate();
            }
        };
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (batteryReceiver != null) {
            try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) { }
        }
        if (locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (Exception ignored) { }
        }
        if (dashboard != null) dashboard.stopClock();
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

    private class DashboardView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
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

        DashboardView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            background = BitmapFactory.decodeResource(getResources(), R.drawable.central_fusion_bg);
            if (background != null) {
                src.set(0, 0, background.getWidth(), background.getHeight());
            }
            post(clockTick);
        }

        private final Runnable clockTick = new Runnable() {
            @Override public void run() {
                invalidate();
                postDelayed(this, 1000);
            }
        };

        void stopClock() {
            removeCallbacks(clockTick);
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

            drawDynamicClock(c, w, h);
            drawDynamicSpeed(c, w, h);
            drawDynamicBattery(c, w, h);
            drawDynamicGps(c, w, h);
            prepareButtons(w, h);
        }

        private void drawDynamicClock(Canvas c, int w, int h) {
            // Cover the static mockup values while preserving the approved artwork.
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
            // Center of the speedometer in the approved artwork.
            paint.setColor(Color.rgb(8, 11, 15));
            c.drawCircle(w * 0.868f, h * 0.280f, h * 0.062f, paint);

            text.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(h * 0.110f);
            text.setColor(Color.WHITE);
            c.drawText(String.valueOf(speed), w * 0.868f, h * 0.310f, text);
        }

        private void drawDynamicBattery(Canvas c, int w, int h) {
            // Inner portion of the battery card only.
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

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_UP) return true;
            for (int i = 0; i < buttons.length; i++) {
                if (buttons[i] != null && buttons[i].contains(event.getX(), event.getY())) {
                    handleButton(i);
                    return true;
                }
            }
            return true;
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
                    launchPackage("com.spotify.music", "spotify:");
                    break;
                case 3:
                    startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                    break;
                case 4:
                    Intent music = new Intent(Intent.ACTION_MAIN);
                    music.addCategory(Intent.CATEGORY_APP_MUSIC);
                    try { startActivity(Intent.createChooser(music, "Escolher música")); } catch (Exception ignored) { }
                    break;
                case 5:
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                    break;
                case 6:
                    startActivity(new Intent(MainActivity.this, AppsActivity.class));
                    break;
            }
        }
    }
}
