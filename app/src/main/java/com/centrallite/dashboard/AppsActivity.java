package com.centrallite.dashboard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class AppsActivity extends Activity {
    private List<ResolveInfo> apps;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        Intent launcher = new Intent(Intent.ACTION_MAIN, null);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        apps = getPackageManager().queryIntentActivities(launcher, 0);
        Collections.sort(apps, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                return a.loadLabel(getPackageManager()).toString().compareToIgnoreCase(
                        b.loadLabel(getPackageManager()).toString());
            }
        });

        GridView grid = new GridView(this);
        grid.setBackgroundColor(Color.rgb(9,11,15));
        grid.setNumColumns(4);
        grid.setHorizontalSpacing(16);
        grid.setVerticalSpacing(16);
        grid.setPadding(22,22,22,22);
        grid.setAdapter(new AppAdapter(this));
        grid.setOnItemClickListener((parent, view, position, id) -> {
            ResolveInfo ri = apps.get(position);
            Intent i = getPackageManager().getLaunchIntentForPackage(ri.activityInfo.packageName);
            if (i != null) startActivity(i);
        });
        setContentView(grid);
    }

    private class AppAdapter extends BaseAdapter {
        private final Context context;
        AppAdapter(Context c) { context = c; }
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int p) { return apps.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View convertView, ViewGroup parent) {
            ResolveInfo ri = apps.get(p);
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(10,18,10,18);

            ImageView icon = new ImageView(context);
            icon.setImageDrawable(ri.loadIcon(getPackageManager()));
            box.addView(icon, new LinearLayout.LayoutParams(72,72));

            TextView name = new TextView(context);
            name.setText(ri.loadLabel(getPackageManager()));
            name.setTextColor(Color.WHITE);
            name.setTextSize(14);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(1);
            box.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return box;
        }
    }
}
