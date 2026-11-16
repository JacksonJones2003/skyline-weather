package com.skyline.weather;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

// In-app reader for stories and videos: a top bar in the forecast's current sky colours, the page below.
public class ReaderActivity extends Activity {
    private WebView web;
    private View fullView;
    private FrameLayout root;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setDecorFitsSystemWindows(false);
        int top = parse(getIntent().getStringExtra("top"), 0xFF2F6FC0), bottom = parse(getIntent().getStringExtra("bottom"), 0xFF8EC0EA);
        float dp = getResources().getDisplayMetrics().density;
        root = new FrameLayout(this);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom}));
        TextView back = new TextView(this); back.setText("\u2039"); back.setTextColor(Color.WHITE); back.setTextSize(30);
        back.setPadding((int) (16 * dp), 0, (int) (14 * dp), (int) (4 * dp)); back.setOnClickListener(v -> onBackPressed());
        TextView title = new TextView(this); title.setText(getIntent().getStringExtra("title")); title.setTextColor(Color.WHITE);
        title.setTextSize(16); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(0, 0, (int) (16 * dp), 0);
        bar.addView(back); bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true); web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String sc = r.getUrl().getScheme();
                return !("http".equals(sc) || "https".equals(sc));   // stay in the reader; ignore app-launch links
            }
        });
        web.setWebChromeClient(new WebChromeClient() {   // full-screen video
            @Override public void onShowCustomView(View v, CustomViewCallback cb) { fullView = v; root.addView(v, new FrameLayout.LayoutParams(-1, -1)); }
            @Override public void onHideCustomView() { if (fullView != null) { root.removeView(fullView); fullView = null; } }
        });
        col.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        col.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(col);
        boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        root.setBackgroundColor(night ? 0xFF101318 : Color.WHITE);
        web.setBackgroundColor(night ? 0xFF101318 : Color.WHITE);
        if (android.os.Build.VERSION.SDK_INT >= 33) web.getSettings().setAlgorithmicDarkeningAllowed(night);   // dark pages for sites without their own dark mode
        bar.setOnApplyWindowInsetsListener((v, in) -> {
            Insets s = in.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(s.left, s.top, s.right, (int) (6 * dp)); v.setMinimumHeight(s.top + (int) (52 * dp));
            web.setPadding(0, 0, 0, s.bottom);
            return in;
        });
        setContentView(root);
        web.loadUrl(getIntent().getStringExtra("url"));
    }
    static int parse(String c, int def) { try { return Color.parseColor(c.trim()); } catch (Exception e) { try { String[] p = c.replaceAll("[^0-9,]", "").split(","); return Color.rgb(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])); } catch (Exception e2) { return def; } } }
    @Override public void onBackPressed() {
        if (fullView != null) { web.getWebChromeClient().onHideCustomView(); return; }
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
    @Override protected void onDestroy() { web.destroy(); super.onDestroy(); }
}
