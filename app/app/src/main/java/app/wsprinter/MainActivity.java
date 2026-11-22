package app.wsprinter;

import android.app.Activity;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

/** Debug-only screen; endpoint configuration lives in the overflow menu. */
public final class MainActivity extends Activity {
    private final Handler handler = new Handler();
    private TextView diagnostics;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            boolean exempt = Build.VERSION.SDK_INT < 23 || ((PowerManager) getSystemService(POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName());
            diagnostics.setText(Diag.summary() + "\npower:    " + (exempt ? "unrestricted" : "battery optimized; use Always-on power menu") + "\n\n" + Diag.logText());
            handler.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("WS Printer · Debug");
        ScrollView scroll = new ScrollView(this);
        diagnostics = new TextView(this);
        diagnostics.setTypeface(Typeface.MONOSPACE); diagnostics.setTextSize(12);
        diagnostics.setTextColor(Color.rgb(190, 220, 190)); diagnostics.setBackgroundColor(Color.rgb(18, 22, 24));
        int padding = (int) (12 * getResources().getDisplayMetrics().density);
        diagnostics.setPadding(padding, padding, padding, padding); diagnostics.setTextIsSelectable(true);
        scroll.setBackgroundColor(Color.rgb(18, 22, 24)); scroll.setFillViewport(true); scroll.addView(diagnostics); setContentView(scroll);
        // Development convenience only; release builds cannot be configured by an external Intent.
        if (BuildConfig.DEBUG && getIntent().hasExtra("url")) {
            try { saveUrl(getIntent().getStringExtra("url")); } catch (IllegalArgumentException e) { Diag.log("invalid debug URL"); }
        }
        PrintService.start(this);
        if (PrintService.savedUrl(this).isEmpty()) configure();
    }
    @Override public void onResume() { super.onResume(); handler.post(refresh); }
    @Override public void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "WebSocket URL");
        menu.add(0, 2, 1, "Always-on power");
        return true;
    }
    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) configure();
        else if (Build.VERSION.SDK_INT >= 23) {
            try {
                PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
                if (!power.isIgnoringBatteryOptimizations(getPackageName())) {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                } else Diag.log("battery optimization exemption already enabled");
            } catch (ActivityNotFoundException e) { Diag.log("use device battery settings to allow background operation"); }
        }
        return true;
    }
    private void saveUrl(String url) {
        String valid = SocketLink.validate(url);
        if (!getSharedPreferences("config", MODE_PRIVATE).edit().putString("url", valid).commit()) throw new IllegalArgumentException("Could not save URL");
        PrintService.start(this);
    }
    private void configure() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true); input.setHint("wss://your-server/printer"); input.setText(PrintService.savedUrl(this));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("WebSocket URL").setView(input)
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try { saveUrl(input.getText().toString()); dialog.dismiss(); }
            catch (IllegalArgumentException e) { input.setError(e.getMessage()); }
        }));
        dialog.show();
    }
}
