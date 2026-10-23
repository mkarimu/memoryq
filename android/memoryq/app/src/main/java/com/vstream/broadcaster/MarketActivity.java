package com.vstream.broadcaster;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * The Memoryq Marketplace (buy and sell with members) - the same page as memoryq.com/market, signed in with the app's
 * account. Photos for a listing are picked from the phone's gallery.
 */
public class MarketActivity extends AppCompatActivity {

    private WebView web;
    private ProgressBar bar;
    private String base;
    private ValueCallback<Uri[]> pending;
    private final ActivityResultLauncher<String> pick = registerForActivityResult(
            new ActivityResultContracts.GetMultipleContents(), this::picked);

    /** https://memoryq.com for the site's name, the old address (port 8080) for a bare server address. */
    static String siteBase(String host) {
        String h = host == null ? "" : host.trim();
        if (h.isEmpty()) {
            return "https://memoryq.com";
        }
        boolean bare = h.matches("[0-9.]+") || h.contains(":") || h.equals("localhost");
        return bare ? "http://" + h + (h.contains(":") ? "" : ":8080") : "https://" + h;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences("vstream", MODE_PRIVATE);
        base = siteBase(prefs.getString("host", ""));
        String token = prefs.getString("session", "");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(Color.parseColor("#16202a"));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(4), dp(16), dp(4));
        TextView back = new TextView(this);
        back.setText("\u2039");
        back.setTextSize(30);
        back.setTextColor(Color.WHITE);
        back.setPadding(dp(10), 0, dp(14), dp(4));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView title = new TextView(this);
        title.setText("\uD83D\uDED2  Marketplace");
        title.setTextSize(20);
        title.setTextColor(Color.WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        top.addView(title);
        root.addView(top);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));
        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String url = u.toString();
                if (url.startsWith(base + "/market") || url.startsWith(base + "/api/")) {
                    return false;
                }
                // everything else (memoryq.com/me, other sites) opens in the browser
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MarketActivity.this, "No browser found", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                bar.setProgress(p);
                bar.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pending != null) {
                    pending.onReceiveValue(null);
                }
                pending = callback;
                try {
                    pick.launch("image/*");
                } catch (ActivityNotFoundException e) {
                    pending = null;
                    return false;
                }
                return true;
            }
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web.canGoBack()) {
                    web.goBack();
                } else {
                    finish();
                }
            }
        });
        String start = getIntent().getStringExtra("page");
        String hash = token.isEmpty() ? "" : "#token=" + token;
        web.loadUrl(base + "/market" + hash);
        if (start != null && !start.isEmpty()) {
            web.postDelayed(() -> web.evaluateJavascript("location.hash='" + start.replace("'", "") + "'", null), 1500);
        }
    }

    private void picked(List<Uri> uris) {
        if (pending == null) {
            return;
        }
        pending.onReceiveValue(uris == null || uris.isEmpty() ? null : uris.toArray(new Uri[0]));
        pending = null;
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}