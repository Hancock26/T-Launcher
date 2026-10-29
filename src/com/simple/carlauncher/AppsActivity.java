package com.simple.carlauncher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AppsActivity extends Activity {

    private final List<ResolveInfo> apps = new ArrayList<>();
    private BaseAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(16), dp(24), 0);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1B2C8C, 0xFF20206E, 0xFF371764}));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(Color.WHITE);
        back.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x1FFFFFFF);
        back.setBackground(circle);
        MainActivity.pressable(back);
        back.setOnClickListener(Ui.click(new Runnable() { public void run() { finish(); } }));
        header.addView(back, new LinearLayout.LayoutParams(dp(52), dp(52)));

        TextView title = new TextView(this);
        title.setText("Aplicaciones");
        title.setTextSize(26);
        title.setTextColor(Color.WHITE);
        title.setPadding(dp(18), 0, 0, 0);
        header.addView(title);
        root.addView(header);

        GridView grid = new GridView(this);
        grid.setNumColumns(6);
        grid.setVerticalSpacing(dp(8));
        grid.setPadding(0, dp(16), 0, dp(16));
        grid.setClipToPadding(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        adapter = new BaseAdapter() {
            public int getCount() { return apps.size(); }
            public Object getItem(int p) { return apps.get(p); }
            public long getItemId(int p) { return p; }
            public View getView(int p, View convert, ViewGroup parent) {
                return buildItem(apps.get(p), convert);
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int pos, long id) {
            ResolveInfo ri = apps.get(pos);
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            }
        });
        grid.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int pos, long id) {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + apps.get(pos).activityInfo.packageName));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
            }
        });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        apps.clear();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (!ri.activityInfo.packageName.equals(getPackageName())) apps.add(ri);
        }
        Collator collator = Collator.getInstance();
        Collections.sort(apps, new java.util.Comparator<ResolveInfo>() {
            public int compare(ResolveInfo a, ResolveInfo b) {
                return collator.compare(a.loadLabel(pm).toString(), b.loadLabel(pm).toString());
            }
        });
        adapter.notifyDataSetChanged();
    }

    private View buildItem(ResolveInfo ri, View convert) {
        LinearLayout item;
        ImageView icon;
        TextView label;
        if (convert == null) {
            item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(dp(6), dp(14), dp(6), dp(14));
            icon = new ImageView(this);
            item.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
            label = new TextView(this);
            label.setTextSize(14);
            label.setTextColor(0xE6FFFFFF);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(0, dp(8), 0, 0);
            item.addView(label, new LinearLayout.LayoutParams(-1, -2));
        } else {
            item = (LinearLayout) convert;
            icon = (ImageView) item.getChildAt(0);
            label = (TextView) item.getChildAt(1);
        }
        PackageManager pm = getPackageManager();
        icon.setImageDrawable(ri.loadIcon(pm));
        label.setText(ri.loadLabel(pm));
        return item;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
