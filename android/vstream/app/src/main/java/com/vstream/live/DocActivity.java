package com.vstream.live;

import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

/** Shows the terms and policies bundled with the app (works offline). Web and e-mail links open outside. */
public class DocActivity extends AppCompatActivity {

    static final String EXTRA_ANCHOR = "anchor";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BG);
        Button back = Ui.flat(this, "\u2039  Back");
        back.setOnClickListener(v -> finish());
        col.addView(back, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));
        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(false);
        web.setBackgroundColor(Ui.BG);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("file:///android_asset/")) {
                    return false;
                }
                Ui.openWeb(DocActivity.this, url);
                return true;
            }
        });
        col.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(col);
        Ui.edgeSafe(this);
        String anchor = getIntent().getStringExtra(EXTRA_ANCHOR);
        web.loadUrl("file:///android_asset/legal/vstream-legal.html" + (anchor == null || anchor.isEmpty() ? "" : "#" + anchor));
    }
}