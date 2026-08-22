package com.centrallite.dashboard;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
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
            dashboard.gpsStatus = "GPS procurando sinal";
        } catch (Exception e) {
            dashboard.gpsStatus = "GPS indisponível";
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestLocationUpdates();
        } else {
            dashboard.gpsStatus = "Permita Localização para velocidade";
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
        dashboard.gpsStatus = "GPS conectado";
        dashboard.invalidate();
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
        dashboard.gpsStatus = "GPS ligado";
    }

    @Override
    public void onProviderDisabled(String provider) {
        dashboard.gpsStatus = "Ative o GPS";
        dashboard.invalidate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (batteryReceiver != null) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (Exception ignored) {
            }
        }
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (Exception ignored) {
            }
        }
    }

    private void launchPackage(String pkg, String fallbackUri) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) {
            startActivity(i);
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUri)));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
            } catch (Exception ignored) {
            }
        }
    }

    private class DashboardView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, dd 'de' MMMM 'de' yyyy", new Locale("pt", "BR"));
        private final RectF[] buttons = new RectF[7];
        int speed = 0;
        int battery = 0;
        boolean charging = false;
        String gpsStatus = "GPS iniciando";

        DashboardView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.FILL);
            textPaint.setColor(Color.WHITE);
            textPaint.setSubpixelText(true);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);
            accentPaint.setColor(Color.rgb(70, 140, 255));
            post(clockTick);
        }

        private final Runnable clockTick = new Runnable() {
            @Override
            public void run() {
                invalidate();
                postDelayed(this, 1000);
            }
        };

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            drawBackground(c, w, h);
            drawTopBar(c, w, h);
            drawHero(c, w, h);
            drawSpeedGauge(c, w, h);
            drawSideCards(c, w, h);
            drawTiles(c, w, h);
            drawFooter(c, w, h);
        }

        private void drawBackground(Canvas c, int w, int h) {
            paint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{Color.rgb(5, 7, 12), Color.rgb(10, 16, 28), Color.rgb(7, 10, 16)},
                    null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            // soft radial glow in the middle
            paint.setShader(new RadialGradient(w * 0.5f, h * 0.40f, h * 0.55f,
                    new int[]{Color.argb(80, 100, 140, 200), Color.argb(0, 100, 140, 200)},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            // subtle hex-like dots
            paint.setColor(Color.argb(22, 255, 255, 255));
            float gap = Math.max(18f, h * 0.03f);
            float radius = Math.max(1.5f, h * 0.003f);
            for (float y = h * 0.18f; y < h * 0.74f; y += gap) {
                float rowShift = (((int) (y / gap)) % 2 == 0) ? 0 : gap / 2f;
                for (float x = w * 0.22f + rowShift; x < w * 0.78f; x += gap) {
                    c.drawCircle(x, y, radius, paint);
                }
            }
        }

        private void drawTopBar(Canvas c, int w, int h) {
            float barH = h * 0.085f;
            paint.setColor(Color.argb(210, 0, 0, 0));
            c.drawRect(0, 0, w, barH, paint);

            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            textPaint.setTextSize(h * 0.034f);
            textPaint.setColor(Color.WHITE);
            c.drawText("Central Lite", w * 0.06f, h * 0.05f, textPaint);

            textPaint.setTextSize(h * 0.024f);
            textPaint.setColor(Color.rgb(170, 185, 205));
            c.drawText("Fusion 2014 • Ford", w * 0.17f, h * 0.05f, textPaint);

            textPaint.setTextAlign(Paint.Align.RIGHT);
            textPaint.setTextSize(h * 0.03f);
            textPaint.setColor(Color.WHITE);
            c.drawText(timeFmt.format(new Date()), w * 0.96f, h * 0.05f, textPaint);
        }

        private void drawHero(Canvas c, int w, int h) {
            // Big time/date block
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
            textPaint.setTextSize(h * 0.15f);
            textPaint.setColor(Color.WHITE);
            c.drawText(timeFmt.format(new Date()), w * 0.035f, h * 0.23f, textPaint);

            paint.setColor(Color.argb(120, 255, 255, 255));
            c.drawRect(w * 0.04f, h * 0.27f, w * 0.25f, h * 0.272f, paint);
            paint.setShader(new LinearGradient(w * 0.04f, h * 0.27f, w * 0.25f, h * 0.27f,
                    new int[]{Color.argb(0,255,255,255), Color.argb(180,255,255,255), Color.argb(0,255,255,255)},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(w * 0.04f, h * 0.268f, w * 0.25f, h * 0.274f, paint);
            paint.setShader(null);

            textPaint.setTextSize(h * 0.038f);
            textPaint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            textPaint.setColor(Color.rgb(210, 215, 220));
            c.drawText(capitalize(dateFmt.format(new Date())), w * 0.04f, h * 0.33f, textPaint);

            // Brand title
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD_ITALIC));
            textPaint.setTextSize(h * 0.08f);
            textPaint.setColor(Color.rgb(225, 229, 235));
            c.drawText("FUSION", w * 0.52f, h * 0.18f, textPaint);

            textPaint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            textPaint.setTextSize(h * 0.042f);
            textPaint.setColor(Color.rgb(190, 195, 200));
            c.drawText("2014", w * 0.52f, h * 0.22f, textPaint);

            paint.setColor(Color.argb(110, 255, 255, 255));
            c.drawRect(w * 0.40f, h * 0.20f, w * 0.46f, h * 0.202f, paint);
            c.drawRect(w * 0.58f, h * 0.20f, w * 0.64f, h * 0.202f, paint);

            drawCarIllustration(c, w, h);
        }

        private void drawCarIllustration(Canvas c, int w, int h) {
            float cx = w * 0.52f;
            float top = h * 0.27f;
            float carW = w * 0.36f;
            float carH = h * 0.26f;

            // shadow
            paint.setColor(Color.argb(90, 0, 0, 0));
            c.drawOval(new RectF(cx - carW * 0.42f, top + carH * 0.82f, cx + carW * 0.42f, top + carH * 1.02f), paint);

            // body
            Path body = new Path();
            float left = cx - carW / 2f;
            float right = cx + carW / 2f;
            float base = top + carH * 0.72f;
            body.moveTo(left + carW * 0.08f, base);
            body.lineTo(left + carW * 0.15f, top + carH * 0.42f);
            body.quadTo(left + carW * 0.28f, top + carH * 0.18f, left + carW * 0.46f, top + carH * 0.16f);
            body.lineTo(left + carW * 0.64f, top + carH * 0.18f);
            body.quadTo(left + carW * 0.78f, top + carH * 0.24f, left + carW * 0.86f, top + carH * 0.42f);
            body.lineTo(right - carW * 0.05f, top + carH * 0.48f);
            body.quadTo(right, top + carH * 0.53f, right - carW * 0.02f, base);
            body.lineTo(right - carW * 0.11f, base);
            body.quadTo(right - carW * 0.18f, base + carH * 0.05f, right - carW * 0.25f, base + carH * 0.04f);
            body.lineTo(left + carW * 0.26f, base + carH * 0.04f);
            body.quadTo(left + carW * 0.18f, base + carH * 0.05f, left + carW * 0.12f, base);
            body.close();

            paint.setShader(new LinearGradient(left, top, right, top + carH,
                    new int[]{Color.rgb(245, 246, 248), Color.rgb(178, 183, 192), Color.rgb(118, 122, 130)},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawPath(body, paint);
            paint.setShader(null);

            // windows
            Path window = new Path();
            window.moveTo(left + carW * 0.29f, top + carH * 0.28f);
            window.lineTo(left + carW * 0.47f, top + carH * 0.24f);
            window.lineTo(left + carW * 0.61f, top + carH * 0.24f);
            window.quadTo(left + carW * 0.72f, top + carH * 0.25f, left + carW * 0.78f, top + carH * 0.38f);
            window.lineTo(left + carW * 0.67f, top + carH * 0.42f);
            window.lineTo(left + carW * 0.34f, top + carH * 0.42f);
            window.close();
            paint.setColor(Color.argb(210, 25, 35, 48));
            c.drawPath(window, paint);

            // doors and details
            strokePaint.setColor(Color.argb(100, 255, 255, 255));
            strokePaint.setStrokeWidth(h * 0.0022f);
            c.drawLine(left + carW * 0.51f, top + carH * 0.24f, left + carW * 0.53f, base + carH * 0.03f, strokePaint);
            c.drawLine(left + carW * 0.67f, top + carH * 0.27f, left + carW * 0.70f, base, strokePaint);
            c.drawLine(left + carW * 0.15f, base - carH * 0.04f, right - carW * 0.1f, base - carH * 0.04f, strokePaint);
            c.drawLine(left + carW * 0.22f, top + carH * 0.52f, left + carW * 0.34f, top + carH * 0.50f, strokePaint);
            c.drawLine(right - carW * 0.20f, top + carH * 0.49f, right - carW * 0.13f, top + carH * 0.51f, strokePaint);

            // grille / lights
            strokePaint.setColor(Color.argb(170, 20, 20, 20));
            strokePaint.setStrokeWidth(h * 0.004f);
            c.drawRoundRect(new RectF(left + carW * 0.03f, top + carH * 0.44f, left + carW * 0.23f, top + carH * 0.60f),
                    carH * 0.06f, carH * 0.06f, strokePaint);
            paint.setColor(Color.argb(190, 255, 220, 140));
            c.drawOval(new RectF(left + carW * 0.17f, top + carH * 0.40f, left + carW * 0.22f, top + carH * 0.47f), paint);
            c.drawOval(new RectF(right - carW * 0.12f, top + carH * 0.41f, right - carW * 0.06f, top + carH * 0.47f), paint);

            // wheels
            drawWheel(c, left + carW * 0.27f, base + carH * 0.03f, carH * 0.17f);
            drawWheel(c, right - carW * 0.22f, base + carH * 0.03f, carH * 0.17f);
        }

        private void drawWheel(Canvas c, float cx, float cy, float r) {
            paint.setColor(Color.rgb(25, 25, 28));
            c.drawCircle(cx, cy, r, paint);
            paint.setColor(Color.rgb(170, 175, 185));
            c.drawCircle(cx, cy, r * 0.63f, paint);
            strokePaint.setColor(Color.rgb(85, 90, 98));
            strokePaint.setStrokeWidth(r * 0.08f);
            for (int i = 0; i < 6; i++) {
                double ang = Math.toRadians(i * 60);
                c.drawLine(cx, cy,
                        (float) (cx + Math.cos(ang) * r * 0.55f),
                        (float) (cy + Math.sin(ang) * r * 0.55f), strokePaint);
            }
            paint.setColor(Color.rgb(65, 68, 72));
            c.drawCircle(cx, cy, r * 0.16f, paint);
        }

        private void drawSpeedGauge(Canvas c, int w, int h) {
            float cx = w * 0.905f;
            float cy = h * 0.295f;
            float r = h * 0.16f;

            // card glow
            paint.setColor(Color.argb(28, 255, 255, 255));
            c.drawRoundRect(new RectF(cx - r * 1.15f, cy - r * 1.15f, cx + r * 1.15f, cy + r * 1.15f), 28, 28, paint);

            strokePaint.setStrokeWidth(h * 0.007f);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setShader(new LinearGradient(cx - r, cy + r, cx + r, cy - r,
                    new int[]{Color.argb(80, 120, 130, 145), Color.argb(140, 65, 110, 220)},
                    null, Shader.TileMode.CLAMP));
            RectF oval = new RectF(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(oval, 132, 276, false, strokePaint);
            strokePaint.setShader(null);

            // fine ticks
            strokePaint.setColor(Color.argb(80, 255, 255, 255));
            strokePaint.setStrokeWidth(h * 0.0018f);
            for (int i = 0; i <= 24; i++) {
                double ang = Math.toRadians(135 + (270d / 24d) * i);
                float r1 = r * 0.83f;
                float r2 = r * 0.92f;
                c.drawLine((float) (cx + Math.cos(ang) * r1), (float) (cy + Math.sin(ang) * r1),
                        (float) (cx + Math.cos(ang) * r2), (float) (cy + Math.sin(ang) * r2), strokePaint);
            }

            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setColor(Color.WHITE);
            textPaint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            textPaint.setTextSize(h * 0.04f);
            c.drawText("Velocidade", cx, cy - r * 0.38f, textPaint);
            textPaint.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
            textPaint.setTextSize(h * 0.12f);
            c.drawText(String.valueOf(speed), cx, cy + h * 0.015f, textPaint);
            textPaint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            textPaint.setTextSize(h * 0.05f);
            c.drawText("km/h", cx, cy + r * 0.40f, textPaint);
        }

        private void drawSideCards(Canvas c, int w, int h) {
            float cardW = w * 0.18f;
            float cardH = h * 0.145f;
            float radius = h * 0.02f;
            float leftX = w * 0.02f;
            float leftY = h * 0.46f;
            float rightX = w * 0.82f;
            float rightY = h * 0.49f;

            drawInfoCard(c, new RectF(leftX, leftY, leftX + cardW, leftY + cardH), "Bateria",
                    battery + "%", charging ? "Carregando" : "Em uso", true);
            drawInfoCard(c, new RectF(rightX, rightY, rightX + cardW, rightY + cardH), "GPS",
                    gpsStatus.equals("GPS conectado") ? "Conectado" : "Status", gpsStatus, false);
        }

        private void drawInfoCard(Canvas c, RectF rect, String title, String value, String subtitle, boolean batteryCard) {
            float radius = getHeight() * 0.02f;
            paint.setColor(Color.argb(210, 18, 22, 30));
            c.drawRoundRect(rect, radius, radius, paint);
            strokePaint.setColor(Color.argb(60, 255, 255, 255));
            strokePaint.setStrokeWidth(getHeight() * 0.0016f);
            strokePaint.setStyle(Paint.Style.STROKE);
            c.drawRoundRect(rect, radius, radius, strokePaint);

            float iconX = rect.left + rect.width() * 0.16f;
            float centerY = rect.centerY();
            if (batteryCard) {
                drawBatteryIcon(c, iconX, centerY, rect.height() * 0.55f);
            } else {
                drawLocationIcon(c, iconX, centerY, rect.height() * 0.22f);
            }

            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setColor(Color.WHITE);
            textPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            textPaint.setTextSize(getHeight() * 0.028f);
            c.drawText(title, rect.left + rect.width() * 0.42f, rect.top + rect.height() * 0.32f, textPaint);

            textPaint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
            textPaint.setTextSize(getHeight() * 0.055f);
            if (!batteryCard) textPaint.setTextSize(getHeight() * 0.036f);
            c.drawText(value, rect.left + rect.width() * 0.42f, rect.top + rect.height() * 0.63f, textPaint);

            textPaint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            textPaint.setTextSize(getHeight() * 0.028f);
            textPaint.setColor(Color.rgb(80, 230, 120));
            c.drawText(subtitle, rect.left + rect.width() * 0.42f, rect.top + rect.height() * 0.84f, textPaint);
        }

        private void drawBatteryIcon(Canvas c, float cx, float cy, float size) {
            float w = size * 0.45f;
            float h = size;
            RectF body = new RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(getHeight() * 0.003f);
            strokePaint.setColor(Color.WHITE);
            c.drawRoundRect(body, 8, 8, strokePaint);
            paint.setColor(Color.WHITE);
            c.drawRect(cx - w * 0.12f, body.top - h * 0.08f, cx + w * 0.12f, body.top, paint);
            float innerGap = w * 0.10f;
            float segH = (h - innerGap * 6f) / 5f;
            int segments = Math.max(1, Math.round(battery / 20f));
            for (int i = 0; i < 5; i++) {
                RectF seg = new RectF(body.left + innerGap, body.bottom - innerGap - (i + 1) * segH - i * innerGap,
                        body.right - innerGap, body.bottom - innerGap - i * (segH + innerGap));
                paint.setColor(i < segments ? Color.rgb(110, 225, 100) : Color.argb(45, 255, 255, 255));
                c.drawRoundRect(seg, 4, 4, paint);
            }
        }

        private void drawLocationIcon(Canvas c, float cx, float cy, float r) {
            Path p = new Path();
            p.moveTo(cx, cy + r * 1.85f);
            p.cubicTo(cx - r * 1.2f, cy + r * 0.65f, cx - r * 1.4f, cy - r * 0.2f, cx, cy - r * 0.95f);
            p.cubicTo(cx + r * 1.4f, cy - r * 0.2f, cx + r * 1.2f, cy + r * 0.65f, cx, cy + r * 1.85f);
            paint.setColor(Color.WHITE);
            c.drawPath(p, paint);
            paint.setColor(Color.rgb(20, 20, 22));
            c.drawCircle(cx, cy, r * 0.42f, paint);
        }

        private void drawTiles(Canvas c, int w, int h) {
            String[] labels = {"Waze", "Google Maps", "Spotify", "Bluetooth", "Música", "Configurações", "Aplicativos"};
            int[] iconColors = {
                    Color.rgb(73, 196, 255),
                    Color.rgb(70, 125, 255),
                    Color.rgb(45, 210, 80),
                    Color.rgb(55, 120, 255),
                    Color.rgb(255, 132, 62),
                    Color.rgb(205, 205, 210),
                    Color.rgb(225, 225, 230)
            };

            float left = w * 0.02f;
            float right = w * 0.98f;
            float top = h * 0.70f;
            float bottom = h * 0.92f;
            float gap = w * 0.012f;
            float bw = (right - left - gap * 6f) / 7f;
            float bh = bottom - top;
            float radius = h * 0.02f;

            for (int i = 0; i < 7; i++) {
                float x1 = left + i * (bw + gap);
                buttons[i] = new RectF(x1, top, x1 + bw, bottom);
                paint.setColor(Color.argb(215, 26, 30, 40));
                c.drawRoundRect(buttons[i], radius, radius, paint);
                strokePaint.setStyle(Paint.Style.STROKE);
                strokePaint.setStrokeWidth(h * 0.0017f);
                strokePaint.setColor(Color.argb(70, 255, 255, 255));
                c.drawRoundRect(buttons[i], radius, radius, strokePaint);

                float iconCy = buttons[i].top + bh * 0.39f;
                drawTileIcon(c, i, buttons[i].centerX(), iconCy, Math.min(bw, bh) * 0.19f, iconColors[i]);

                textPaint.setTextAlign(Paint.Align.CENTER);
                textPaint.setColor(Color.WHITE);
                textPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
                textPaint.setTextSize(h * 0.038f);
                c.drawText(labels[i], buttons[i].centerX(), buttons[i].top + bh * 0.82f, textPaint);
            }
        }

        private void drawTileIcon(Canvas c, int index, float cx, float cy, float size, int color) {
            paint.setColor(color);
            switch (index) {
                case 0: // Waze simple bubble
                    c.drawRoundRect(new RectF(cx - size, cy - size * 0.85f, cx + size, cy + size * 0.55f), size * 0.35f, size * 0.35f, paint);
                    paint.setColor(Color.WHITE);
                    c.drawCircle(cx - size * 0.35f, cy - size * 0.10f, size * 0.14f, paint);
                    c.drawCircle(cx + size * 0.18f, cy - size * 0.10f, size * 0.14f, paint);
                    strokePaint.setColor(Color.BLACK);
                    strokePaint.setStrokeWidth(size * 0.08f);
                    strokePaint.setStyle(Paint.Style.STROKE);
                    Path smile = new Path();
                    smile.moveTo(cx - size * 0.34f, cy + size * 0.10f);
                    smile.quadTo(cx, cy + size * 0.34f, cx + size * 0.34f, cy + size * 0.10f);
                    c.drawPath(smile, strokePaint);
                    paint.setColor(Color.WHITE);
                    c.drawCircle(cx - size * 0.55f, cy + size * 0.70f, size * 0.22f, paint);
                    c.drawCircle(cx + size * 0.45f, cy + size * 0.70f, size * 0.22f, paint);
                    break;
                case 1: // map pin
                    Path pin = new Path();
                    pin.moveTo(cx, cy + size * 1.25f);
                    pin.cubicTo(cx - size * 0.95f, cy + size * 0.25f, cx - size * 1.10f, cy - size * 0.50f, cx, cy - size);
                    pin.cubicTo(cx + size * 1.10f, cy - size * 0.50f, cx + size * 0.95f, cy + size * 0.25f, cx, cy + size * 1.25f);
                    c.drawPath(pin, paint);
                    paint.setColor(Color.YELLOW);
                    c.drawCircle(cx, cy - size * 0.05f, size * 0.35f, paint);
                    break;
                case 2: // spotify
                    c.drawCircle(cx, cy, size * 1.05f, paint);
                    strokePaint.setStyle(Paint.Style.STROKE);
                    strokePaint.setColor(Color.BLACK);
                    strokePaint.setStrokeWidth(size * 0.16f);
                    for (int i = 0; i < 3; i++) {
                        Path arc = new Path();
                        float dy = i * size * 0.27f;
                        arc.moveTo(cx - size * 0.5f, cy - size * 0.1f + dy);
                        arc.quadTo(cx, cy - size * 0.38f + dy, cx + size * 0.56f, cy - size * 0.06f + dy);
                        c.drawPath(arc, strokePaint);
                    }
                    break;
                case 3: // bluetooth
                    strokePaint.setStyle(Paint.Style.STROKE);
                    strokePaint.setColor(color);
                    strokePaint.setStrokeWidth(size * 0.16f);
                    Path bt = new Path();
                    bt.moveTo(cx - size * 0.12f, cy - size * 1.05f);
                    bt.lineTo(cx - size * 0.12f, cy + size * 1.05f);
                    bt.lineTo(cx + size * 0.65f, cy + size * 0.42f);
                    bt.lineTo(cx - size * 0.12f, cy);
                    bt.lineTo(cx + size * 0.65f, cy - size * 0.42f);
                    bt.close();
                    c.drawPath(bt, strokePaint);
                    break;
                case 4: // music
                    paint.setColor(color);
                    c.drawRoundRect(new RectF(cx - size * 0.9f, cy - size * 0.9f, cx + size * 0.9f, cy + size * 0.9f), size * 0.25f, size * 0.25f, paint);
                    paint.setColor(Color.WHITE);
                    strokePaint.setColor(Color.WHITE);
                    strokePaint.setStyle(Paint.Style.STROKE);
                    strokePaint.setStrokeWidth(size * 0.18f);
                    c.drawLine(cx - size * 0.05f, cy - size * 0.45f, cx - size * 0.05f, cy + size * 0.35f, strokePaint);
                    c.drawLine(cx - size * 0.05f, cy - size * 0.45f, cx + size * 0.42f, cy - size * 0.60f, strokePaint);
                    c.drawCircle(cx - size * 0.20f, cy + size * 0.45f, size * 0.20f, paint);
                    c.drawCircle(cx + size * 0.25f, cy + size * 0.26f, size * 0.20f, paint);
                    break;
                case 5: // gear-ish
                    strokePaint.setStyle(Paint.Style.STROKE);
                    strokePaint.setStrokeWidth(size * 0.20f);
                    strokePaint.setColor(color);
                    c.drawCircle(cx, cy, size * 0.72f, strokePaint);
                    paint.setColor(color);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.toRadians(i * 45);
                        float tx = (float) (cx + Math.cos(a) * size * 1.12f);
                        float ty = (float) (cy + Math.sin(a) * size * 1.12f);
                        c.drawRoundRect(new RectF(tx - size * 0.10f, ty - size * 0.24f, tx + size * 0.10f, ty + size * 0.24f), 4, 4, paint);
                    }
                    c.drawCircle(cx, cy, size * 0.28f, paint);
                    break;
                case 6: // apps grid
                    paint.setColor(color);
                    float s = size * 0.42f;
                    for (int r = 0; r < 2; r++) {
                        for (int cl = 0; cl < 2; cl++) {
                            float dx = (cl == 0 ? -1 : 1) * size * 0.48f;
                            float dy = (r == 0 ? -1 : 1) * size * 0.48f;
                            c.drawRoundRect(new RectF(cx + dx - s / 2f, cy + dy - s / 2f, cx + dx + s / 2f, cy + dy + s / 2f), 8, 8, paint);
                        }
                    }
                    break;
            }
        }

        private void drawFooter(Canvas c, int w, int h) {
            float footerTop = h * 0.93f;
            paint.setColor(Color.argb(200, 6, 8, 12));
            c.drawRect(0, footerTop, w, h, paint);
            paint.setColor(Color.argb(80, 255, 255, 255));
            c.drawRect(0, footerTop, w, footerTop + h * 0.002f, paint);

            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(h * 0.024f);
            textPaint.setColor(Color.rgb(175, 180, 185));
            textPaint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            c.drawText("Central Lite • toque em um atalho", w * 0.50f, h * 0.972f, textPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_UP) return true;
            for (int i = 0; i < buttons.length; i++) {
                if (buttons[i] != null && buttons[i].contains(e.getX(), e.getY())) {
                    onButton(i);
                    return true;
                }
            }
            return true;
        }

        private void onButton(int index) {
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
                    try {
                        startActivity(Intent.createChooser(music, "Escolher música"));
                    } catch (Exception ignored) {
                    }
                    break;
                case 5:
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                    break;
                case 6:
                    startActivity(new Intent(MainActivity.this, AppsActivity.class));
                    break;
            }
        }

        private String capitalize(String text) {
            if (TextUtils.isEmpty(text)) return "";
            return text.substring(0, 1).toUpperCase(new Locale("pt", "BR")) + text.substring(1);
        }
    }
}
