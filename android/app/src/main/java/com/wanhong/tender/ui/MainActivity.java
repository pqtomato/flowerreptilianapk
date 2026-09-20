package com.wanhong.tender.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.wanhong.tender.R;
import com.wanhong.tender.server.LocalApiServer;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 主界面：WebView 加载应用内的本地服务地址（127.0.0.1）。
 *
 * <p>这样复用现有手机端页面，页面里所有 {@code /api/...} 调用都落到应用内的
 * {@link LocalApiServer}，完全离线可用（只有采集时才联网）。
 */
public class MainActivity extends Activity {

    /** 通知点击时带过来的"要打开哪个视图" */
    public static final String EXTRA_VIEW = "view";
    /** 直达「新公告」筛选（采集完成通知用） */
    public static final String VIEW_NEW = "new";

    private static final int REQ_NOTIFICATION = 1001;

    private WebView webView;
    private ProgressBar progressBar;
    private TextView errorView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        errorView = new TextView(this);
        errorView.setPadding(48, 120, 48, 48);
        errorView.setTextSize(16);
        errorView.setVisibility(View.GONE);
        errorView.setText("正在启动本地服务…");

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(errorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(progressBar, barParams);
        setContentView(root);

        // 初始状态：显示页面，出错时才切到错误提示
        errorView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setUserAgentString(settings.getUserAgentString() + " FlowerTenderApp/1.0");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                // 站外链接（招标原文、附件）交给系统浏览器，避免把外部页面塞进 App
                if (uri != null && !"127.0.0.1".equals(uri.getHost())) {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    return true;
                }
                return false;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                showError("本地服务连接失败：" + error.getDescription()
                        + "\n\n请退出应用后重新打开；若仍失败请反馈日志。");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (errorView.getVisibility() != View.VISIBLE) {
                    webView.setVisibility(View.VISIBLE);
                }
            }
        });

        webView.loadUrl(buildUrl(getIntent()));

        // 页面里点"打开电池优化设置"时，走这个极小的桥接调起系统界面
        webView.addJavascriptInterface(new AppJsBridge(), "AndroidApp");

        requestNotificationPermission();
    }

    /** 冷启动：把通知带来的视图参数拼进 URL，页面初始化时自己应用。 */
    private String buildUrl(Intent intent) {
        String base = "http://127.0.0.1:" + LocalApiServer.PORT + "/";
        String view = intent == null ? null : intent.getStringExtra(EXTRA_VIEW);
        return view == null || view.isEmpty() ? base : base + "?view=" + Uri.encode(view);
    }

    /**
     * 应用已经开着时点通知：不重新加载页面（会丢滚动位置和当前筛选），
     * 直接调页面里的 {@code window.applyView} 切视图。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String view = intent == null ? null : intent.getStringExtra(EXTRA_VIEW);
        if (view == null || view.isEmpty() || webView == null) {
            return;
        }
        String safe = view.replaceAll("[^a-zA-Z0-9_-]", "");
        webView.evaluateJavascript(
                "window.applyView && window.applyView('" + safe + "')", null);
    }

    /** 暴露给页面的原生能力（保持极小，避免扩大攻击面） */
    private class AppJsBridge {
        @android.webkit.JavascriptInterface
        public void openBatterySettings() {
            runOnUiThread(MainActivity.this::requestIgnoreBatteryOptimizations);
        }

        /** 把投标截止时间写进系统日历。deadlineIso 是 LocalDateTime.toString()。 */
        @android.webkit.JavascriptInterface
        public void addToCalendar(final String title, final String deadlineIso, final String url) {
            runOnUiThread(() -> MainActivity.this.addToCalendar(title, deadlineIso, url));
        }

        @android.webkit.JavascriptInterface
        public void share(final String title, final String url) {
            runOnUiThread(() -> MainActivity.this.shareTender(title, url));
        }

        @android.webkit.JavascriptInterface
        public void copyText(final String text) {
            runOnUiThread(() -> MainActivity.this.copyText(text));
        }
    }

    /**
     * 插入一条日历事件。
     *
     * <p>用 {@code ACTION_INSERT} 交给系统日历应用处理：不需要任何权限，
     * 也不替用户决定用哪个日历账号。<b>不硬塞提醒时间</b> ——
     * 每个日历应用的默认提醒策略不同，替用户决定反而和习惯打架。
     */
    private void addToCalendar(String title, String deadlineIso, String url) {
        try {
            LocalDateTime deadline = LocalDateTime.parse(deadlineIso);
            long begin = deadline.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            Intent intent = new Intent(Intent.ACTION_INSERT)
                    .setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, "投标截止：" + title)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 3600_000L)
                    .putExtra(CalendarContract.Events.DESCRIPTION,
                            "报名/投标截止时间（来自「花卉招标监控」）\n" + (url == null ? "" : url));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的日历应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareTender(String title, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, title)
                    .putExtra(Intent.EXTRA_TEXT, title + "\n" + (url == null ? "" : url));
            startActivity(Intent.createChooser(intent, "分享公告"));
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的分享目标", Toast.LENGTH_SHORT).show();
        }
    }

    /** 用原生剪贴板，不用 navigator.clipboard —— 页面失焦时 WebView 会被系统拒绝剪贴板访问。 */
    private void copyText(String text) {
        ClipboardManager manager = getSystemService(ClipboardManager.class);
        if (manager == null) {
            return;
        }
        manager.setPrimaryClip(ClipData.newPlainText("花卉招标监控", text));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }

    private void showError(String message) {
        errorView.setText(message);
        errorView.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
    }

    /** Android 13+ 需要显式申请通知权限，否则看不到采集进度。 */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        }
    }

    /** 建议用户把应用加入电池优化白名单（国产 ROM 还需手动允许自启动）。 */
    public void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {
            // 部分 ROM 不支持该意图，忽略即可
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
