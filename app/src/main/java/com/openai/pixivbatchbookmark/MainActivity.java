package com.openai.pixivbatchbookmark;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONArray;
import android.text.Editable;
import android.text.TextWatcher;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PIXIV_HOME = "https://www.pixiv.net/";
    private static final String PIXIV_LOGIN = "https://accounts.pixiv.net/login?lang=zh";
    private static final int MAX_BATCH_IDS = 10000;
    private static final int REQ_IMPORT_TXT = 1001;
    private static final int REQ_EXPORT_RESULTS = 1002;
    private static final int REQ_SAVE_LOG = 1003;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText input;
    private TextView detected;
    private TextView log;
    private TextView loginStatus;
    private TextView resultView;
    private ProgressBar progress;
    private RadioButton publicRadio;
    private RadioButton privateRadio;
    private Button startBtn;
    private Button pauseBtn;
    private Button stopBtn;
    private Button wallpaperToggle;
    private View wallpaperControls;
    private FrameLayout root;
    private View mainView;
    private ScrollView mainScroll;
    private ImageView wallpaperView;
    private WebView webView;
    private Button backFromWeb;

    private List<String> currentIds = new ArrayList<>();
    private int batchIndex = 0;
    private int ok = 0, converted = 0, skip = 0, invalid = 0, fail = 0, pendingCount = 0;
    private volatile boolean running = false;
    private volatile boolean batchQueueActive = false;
    private boolean checkingLogin = false;
    private boolean waitingForResult = false;
    private boolean paused = false;
    private boolean wallpaperOnly = false;
    private int wallpaperIndex = 1;
    private final BatchGate gate = new BatchGate(new java.security.SecureRandom().nextLong() & 0x3fffffffffffffffL);
    private final List<View> editControls = new ArrayList<>();
    private final StringBuilder resultRows = new StringBuilder();
    private volatile long taskToken;
    private String taskKind = "", taskId = "", taskScript = "";
    private boolean batchPrivate;
    private boolean batchRemove;
    private String batchCsrf = "";
    private Runnable taskTimeout;
    private final ExecutorService fileIo = Executors.newSingleThreadExecutor();
    private final ExecutorService stateIo = Executors.newSingleThreadExecutor();
    private final Object taskLock = new Object();
    private final Set<String> pendingMutations = new LinkedHashSet<>();
    private final Set<String> reportedIds = new LinkedHashSet<>();
    private volatile RecoveryStore.Snapshot latestSnapshot;
    private RecoveryStore.Owner storageOwner;
    private String fileLease = "", pendingExportId = "";
    private int pendingFileRequest;
    private long pageGeneration;
    private RecoveryStore recovery;
    private boolean restoringState = true, fileBusy = false;
    private volatile boolean activityAlive = true;
    private Runnable snapshotTask;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            pendingExportId = savedInstanceState.getString("export_operation", "");
            pendingFileRequest = savedInstanceState.getInt("file_request", 0);
        }
        // Remember the selected wallpaper; use the newly supplied image on first launch.
        wallpaperIndex = getPreferences(MODE_PRIVATE).getInt("wallpaper_index", 1) == 0 ? 0 : 1;
        root = new FrameLayout(this);
        setContentView(root);

        mainView = buildMainView();
        root.addView(mainView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        try {
            recovery = new RecoveryStore(new File(getFilesDir(), "task-recovery"));
            storageOwner = recovery.claimOwner(); // Take over before restoring; old commits are now stale.
        }
        catch (Exception e) { appendLog("恢复文件不可用：" + e.getMessage()); }
        try (InputStream stream = getAssets().open("pixiv-task.js")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer, 0, buffer.length)) != -1) bytes.write(buffer, 0, count);
            taskScript = bytes.toString("UTF-8");
        } catch (Exception e) {
            appendLog("无法加载收藏程序：" + e.getMessage());
        }

        webView = createWebView(0);

        FrameLayout webLayer = new FrameLayout(this);
        webLayer.setBackgroundColor(Color.WHITE);
        webLayer.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        backFromWeb = new Button(this);
        backFromWeb.setText("返回工具");
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(120), dp(48));
        bp.gravity = Gravity.TOP | Gravity.END;
        bp.topMargin = dp(8);
        bp.rightMargin = dp(8);
        webLayer.addView(backFromWeb, bp);
        backFromWeb.setOnClickListener(v -> showMain());
        webLayer.setVisibility(View.GONE);
        webLayer.setTag("webLayer");
        root.addView(webLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        appendLog("安卓版 v1.0 快速队列·双壁纸版已启动。");
        setEditingEnabled(false);
        startBtn.setEnabled(false);
        restoreState();
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable s) { scheduleSnapshot(); }
        });
    }

    private boolean ownsPage(WebView view, long generation) {
        return activityAlive && view == webView && pageGeneration == generation
                && (recovery == null || recovery.isOwner(storageOwner));
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private WebView createWebView(final long generation) {
        WebView created = new WebView(this);
        webView = created;
        pageGeneration = generation;
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (!ownsPage(view, generation) || !operationActive() || batchQueueActive || !gate.isActive(generation)) return;
                if (url != null && url.equals(view.getUrl()) && gate.pageReady(generation, url)) {
                    armTimeout(taskToken);
                    String script = taskScript + "\nPixivBatchTask.run('" + taskKind + "','"
                            + taskToken + "','" + taskId + "'," + batchPrivate + ","
                            + JSONObject.quote(batchCsrf) + ");";
                    if (paused) script += "PixivBatchTask.pause();";
                    webView.evaluateJavascript(script, null);
                }
            }

            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (!ownsPage(view, generation)) return;
                if (batchQueueActive) { stopBatch("任务页面发生跳转，已停止；已提交但未核验的作品需要核对。"); return; }
                if (!operationActive() || !gate.isActive(generation)) return;
                try {
                    String host = new java.net.URI(url).getHost();
                    if ("accounts.pixiv.net".equals(host)) {
                        if (checkingLogin) stopLoginCheck("网页跳转到了登录页：当前会话未登录或已失效。");
                        else stopBatch("网页跳转到了登录页，请先登录 Pixiv。");
                    }
                } catch (Exception ignored) {}
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (ownsPage(view, generation) && operationActive() && request.isForMainFrame()
                        && (batchQueueActive || gate.acceptsPageError(generation, request.getUrl().toString()))
                        && request.getUrl().toString().equals(view.getUrl())) {
                    String reason = "网页加载失败：" + error.getDescription() + "。请检查 NekoBox/VPN 和网络后重试。";
                    if (checkingLogin) stopLoginCheck(reason); else stopBatch(reason);
                }
            }
        });
        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");

        return created;
    }

    private void replaceWebView(long generation) {
        WebView previous = webView;
        FrameLayout layer = findWebLayer();
        layer.removeView(previous);
        WebView current = createWebView(generation);
        layer.addView(current, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // CookieManager/profile storage is shared; only this navigation/document instance retires.
        previous.stopLoading(); previous.removeJavascriptInterface("AndroidBridge"); previous.destroy();
    }

    private View buildMainView() {
        FrameLayout themed = new FrameLayout(this);
        themed.setBackgroundColor(Color.rgb(255, 239, 246));

        wallpaperView = new ImageView(this);
        wallpaperView.setImageResource(wallpaperIndex == 0 ? R.drawable.wallpaper : R.drawable.wallpaper_pink);
        wallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        themed.addView(wallpaperView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mainScroll = new ScrollView(this);
        mainScroll.setFillViewport(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(84));
        mainScroll.addView(box, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        themed.addView(mainScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = panel(box);
        LinearLayout titleRow = row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.icon_source);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        titleRow.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text("Pixiv 批量收藏工具", 22, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(12);
        titleRow.addView(title, titleParams);
        header.addView(titleRow);

        TextView subtitle = text("v1.0 快速队列·双壁纸版 · 自动提取作品 ID；画师/用户 ID 会尽量自动排除。账号密码只输入 Pixiv 官方网页。", 13, false);
        subtitle.setTextColor(Color.DKGRAY);
        add(header, subtitle, 0, 10, 0, 10);

        loginStatus = text("● 登录状态：未检查", 14, true);
        add(header, loginStatus, 0, 0, 0, 10);

        LinearLayout loginRow = row();
        Button login = button("登录 Pixiv");
        Button checkLogin = button("检查登录");
        Button clearLogin = button("清除登录");
        loginRow.addView(login, weight());
        loginRow.addView(checkLogin, weightWithLeft());
        loginRow.addView(clearLogin, weightWithLeft());
        header.addView(loginRow);

        LinearLayout webRow = row();
        Button home = button("打开 Pixiv");
        Button proxy = button("代理设置");
        webRow.addView(home, weight());
        webRow.addView(proxy, weightWithLeft());
        add(header, webRow, 0, 6, 0, 0);

        login.setOnClickListener(v -> showWeb(PIXIV_LOGIN));
        home.setOnClickListener(v -> showWeb(PIXIV_HOME));
        checkLogin.setOnClickListener(v -> startLoginCheck());
        clearLogin.setOnClickListener(v -> clearLoginSession());
        proxy.setOnClickListener(v -> openProxySettings());
        editControls.add(login);
        editControls.add(home);
        editControls.add(checkLogin);
        editControls.add(clearLogin);
        editControls.add(proxy);

        LinearLayout inputPanel = panel(box);
        inputPanel.addView(text("作品 ID", 16, true));
        TextView inputTip = text("支持作品链接、纯 ID、pixiv id: 123 的混合文本。含“画师/作者/用户”的 Pixiv ID 会自动忽略。", 12, false);
        inputTip.setTextColor(Color.DKGRAY);
        add(inputPanel, inputTip, 0, 5, 0, 8);

        input = new EditText(this);
        input.setHint("例如：\n画师鉴赏：桃豆こまもち🍡\npixiv id：42658805   ← 画师 ID，会忽略\n💗-pixiv id：97003966\n今日は冷えますねっ…-pixiv id：103816477\n\n也支持 Pixiv 作品链接。\n");
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(9);
        input.setTextSize(15);
        input.setTextColor(Color.rgb(48, 37, 27));
        input.setHintTextColor(Color.rgb(103, 91, 77));
        input.setPadding(dp(12), dp(12), dp(12), dp(12));
        input.setBackground(surface(Color.argb(215, 255, 253, 248), 10));
        add(inputPanel, input, 0, 4, 0, 8);
        editControls.add(input);

        LinearLayout parseRow1 = row();
        Button paste = button("粘贴剪贴板");
        Button parse = button("解析 ID");
        parseRow1.addView(paste, weight());
        parseRow1.addView(parse, weightWithLeft());
        inputPanel.addView(parseRow1);

        LinearLayout parseRow2 = row();
        Button importTxt = button("导入 TXT");
        Button clear = button("清空");
        parseRow2.addView(importTxt, weight());
        parseRow2.addView(clear, weightWithLeft());
        add(inputPanel, parseRow2, 0, 6, 0, 0);
        editControls.add(paste);
        editControls.add(parse);
        editControls.add(importTxt);
        editControls.add(clear);

        LinearLayout actionPanel = panel(box);
        detected = text("已识别：0 个作品", 15, true);
        add(actionPanel, detected, 0, 0, 0, 6);

        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(LinearLayout.HORIZONTAL);
        publicRadio = new RadioButton(this);
        publicRadio.setId(View.generateViewId());
        publicRadio.setText("公开收藏");
        publicRadio.setTextColor(Color.rgb(48, 37, 27));
        privateRadio = new RadioButton(this);
        privateRadio.setId(View.generateViewId());
        privateRadio.setText("私密收藏");
        privateRadio.setTextColor(Color.rgb(48, 37, 27));
        rg.addView(publicRadio);
        rg.addView(privateRadio);
        rg.check(publicRadio.getId());
        rg.setOnCheckedChangeListener((group, checkedId) -> scheduleSnapshot());
        actionPanel.addView(rg);
        editControls.add(publicRadio);
        editControls.add(privateRadio);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        add(actionPanel, progress, 0, 8, 0, 8);

        LinearLayout actionRow = row();
        startBtn = button("开始批量收藏");
        startBtn.setBackgroundTintList(new android.content.res.ColorStateList(
                new int[][] { new int[] { android.R.attr.state_enabled }, new int[] {} },
                new int[] { Color.rgb(139, 85, 43), Color.rgb(216, 207, 194) }));
        startBtn.setTextColor(Color.WHITE);
        pauseBtn = button("暂停");
        pauseBtn.setEnabled(false);
        stopBtn = button("停止");
        stopBtn.setEnabled(false);
        actionRow.addView(startBtn, weight());
        actionRow.addView(pauseBtn, weightWithLeft());
        actionRow.addView(stopBtn, weightWithLeft());
        actionPanel.addView(actionRow);

        Button removeBtn = button("批量取消收藏");
        add(actionPanel, removeBtn, 0, 6, 0, 0);
        editControls.add(removeBtn);

        TextView tip = text("复用同一个 Pixiv 页面：最多 2 个查询并行，修改请求串行并适度间隔；取消每组最多 5 条，逐个核验。收藏模式相同跳过；模式不同直接调用 Pixiv 当前的可见性修改接口，不再先取消再重新收藏。批量取消会处理公开及私密收藏。", 13, false);
        tip.setTextColor(Color.DKGRAY);
        add(actionPanel, tip, 0, 10, 0, 0);

        LinearLayout resultPanel = panel(box);
        add(resultPanel, text("任务进度与结果", 16, true), 0, 0, 0, 8);
        resultView = text("暂无结果", 13, false);
        resultView.setTextIsSelectable(true);
        resultView.setPadding(dp(10), dp(10), dp(10), dp(10));
        resultView.setBackground(surface(Color.argb(215, 255, 253, 248), 10));
        resultView.setMovementMethod(new ScrollingMovementMethod());
        resultPanel.addView(resultView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));
        LinearLayout resultButtons = row();
        Button exportResults = button("导出结果");
        Button saveLog = button("保存日志");
        resultButtons.addView(exportResults, weight());
        resultButtons.addView(saveLog, weightWithLeft());
        add(resultPanel, resultButtons, 0, 6, 0, 0);

        LinearLayout logPanel = panel(box);
        add(logPanel, text("处理日志", 16, true), 0, 0, 0, 8);
        log = text("", 13, false);
        log.setTextIsSelectable(true);
        log.setMovementMethod(new ScrollingMovementMethod());
        log.setPadding(dp(10), dp(10), dp(10), dp(10));
        log.setBackground(surface(Color.argb(215, 255, 253, 248), 10));
        logPanel.addView(log, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)));

        wallpaperToggle = button("看看兽娘麻麻˃ 𖥦 ˂");
        wallpaperToggle.setTextSize(12);
        wallpaperToggle.setBackground(surface(Color.argb(225, 255, 249, 240), 18));
        // Keep both controls visible in either mode, without overlapping on narrow screens.
        LinearLayout wallpaperRow = row();
        Button changeWallpaper = button("切换壁纸");
        changeWallpaper.setTextSize(12);
        changeWallpaper.setBackground(surface(Color.argb(225, 255, 249, 240), 18));
        wallpaperRow.addView(changeWallpaper, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f);
        toggleParams.leftMargin = dp(8);
        wallpaperRow.addView(wallpaperToggle, toggleParams);
        wallpaperControls = wallpaperRow;
        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        wp.gravity = Gravity.BOTTOM;
        wp.leftMargin = dp(12);
        wp.rightMargin = dp(12);
        wp.bottomMargin = dp(14);
        themed.addView(wallpaperControls, wp);
        changeWallpaper.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("选择壁纸")
                .setSingleChoiceItems(new String[]{"原壁纸（秋叶）", "新壁纸（粉色兔耳）"}, wallpaperIndex, (dialog, which) -> {
                    wallpaperIndex = which;
                    wallpaperView.setImageResource(which == 0 ? R.drawable.wallpaper : R.drawable.wallpaper_pink);
                    getPreferences(MODE_PRIVATE).edit().putInt("wallpaper_index", which).apply();
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show());
        wallpaperToggle.setOnClickListener(v -> toggleWallpaperMode());

        paste.setOnClickListener(v -> pasteClipboard());
        parse.setOnClickListener(v -> parseIds(true));
        importTxt.setOnClickListener(v -> openTextImport());
        clear.setOnClickListener(v -> {
            input.setText("");
            currentIds.clear();
            detected.setText("已识别：0 个作品");
            log.setText("");
            resultRows.setLength(0);
            resultView.setText("暂无结果");
            progress.setProgress(0);
            scheduleSnapshot();
        });
        startBtn.setOnClickListener(v -> startBatch(false));
        removeBtn.setOnClickListener(v -> confirmRemoveBatch());
        pauseBtn.setOnClickListener(v -> togglePause());
        stopBtn.setOnClickListener(v -> stopBatch(userStopReason()));
        exportResults.setOnClickListener(v -> exportResults());
        saveLog.setOnClickListener(v -> saveLog());
        return themed;
    }

    private boolean operationActive() {
        return running || checkingLogin || restoringState;
    }

    private void pasteClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            toast("剪贴板没有文字");
            return;
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence s = clip.getItemAt(0).coerceToText(this);
        input.setText(s == null ? "" : s.toString());
        input.setSelection(input.length());
        parseIds(false);
    }

    private void parseIds(boolean showToast) {
        currentIds = PixivIdParser.extract(input.getText().toString());
        detected.setText(String.format(Locale.CHINA, "已识别：%d 个作品%s", currentIds.size(), currentIds.isEmpty() ? "" : "（已自动去重）"));
        if (!currentIds.isEmpty()) {
            appendLog("解析完成：" + currentIds.size() + " 个作品 ID。\n" + joinPreview(currentIds));
        } else if (showToast) {
            toast("没有识别到作品 ID；画师/用户 ID 不会作为作品收藏");
        }
        hideKeyboard();
        scheduleSnapshot();
    }

    private String joinPreview(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(ids.size(), 20);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append(ids.get(i));
        }
        if (ids.size() > n) sb.append(" …");
        return sb.toString();
    }

    private void confirmRemoveBatch() {
        if (operationActive()) return;
        parseIds(false);
        if (currentIds.isEmpty()) {
            toast("请先输入作品 ID 或包含作品 ID 的文字");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("批量取消收藏")
                .setMessage("将检查列表中的 " + currentIds.size()
                        + " 个作品，并取消已有的公开或私密收藏，未收藏的作品会跳过。\n\n取消收藏也会删除其收藏标签和备注。")
                .setNegativeButton("返回", null)
                .setPositiveButton("确认取消收藏", (dialog, which) -> startBatch(true))
                .show();
    }

    private void startBatch(boolean remove) {
        if (operationActive() || fileBusy) return;
        if (recovery == null) { toast("恢复文件不可用，无法安全开始任务"); return; }
        if (taskScript.isEmpty()) {
            toast("收藏程序未加载，请重新安装");
            return;
        }
        parseIds(false);
        if (currentIds.isEmpty()) {
            toast("请先输入作品 ID 或包含作品 ID 的文字");
            return;
        }
        if (currentIds.size() > MAX_BATCH_IDS) { toast("单次最多 10000 个作品，请拆分列表"); return; }
        running = true;
        reportedIds.clear();
        pendingCount = 0;
        synchronized (taskLock) { pendingMutations.clear(); }
        checkingLogin = false;
        batchRemove = remove;
        batchPrivate = privateRadio.isChecked();
        batchCsrf = "";
        paused = false;
        waitingForResult = false;
        setEditingEnabled(false);
        batchIndex = 0;
        ok = 0;
        converted = 0;
        skip = 0;
        invalid = 0;
        fail = 0;
        resultRows.setLength(0);
        resultRows.append("作品 ID\t结果\t说明\n");
        resultView.setText(resultRows.toString());
        startBtn.setEnabled(false);
        pauseBtn.setText("暂停");
        pauseBtn.setEnabled(true);
        stopBtn.setEnabled(true);
        progress.setProgress(0);
        loginStatus.setText("● 登录状态：正在检查…");
        appendLog("正在检查 Pixiv 登录会话并取得收藏安全令牌…");
        showWebHidden();
        beginTask("login", currentIds.get(0), PIXIV_HOME);
        persistState();
    }

    private void startLoginCheck() {
        if (operationActive() || fileBusy) return;
        if (taskScript.isEmpty()) {
            toast("登录检查程序未加载，请重新安装");
            return;
        }
        checkingLogin = true;
        setEditingEnabled(false);
        loginStatus.setText("● 登录状态：检查中…");
        appendLog("正在检查 Pixiv 登录会话…");
        showWebHidden();
        beginTask("check", "0", PIXIV_HOME);
    }

    private void stopLoginCheck(String reason) {
        checkingLogin = false;
        waitingForResult = false;
        cancelPending();
        setEditingEnabled(true);
        loginStatus.setText("● 登录状态：未登录或检查失败");
        appendLog(reason);
        showMain();
        persistState();
    }

    private void processNext() {
        if (!running || paused || batchQueueActive || batchCsrf.isEmpty()) return;
        taskKind = batchRemove ? "unbookmark" : "bookmark";
        taskId = "*";
        taskToken = gate.begin(taskKind, taskId, PIXIV_HOME);
        gate.pageReady(taskToken, PIXIV_HOME);
        batchQueueActive = true;
        waitingForResult = true;
        persistState();
        armTimeout(taskToken);
        // Login has already verified this top-level page; no artwork navigation or reinjection.
        String script = "PixivBatchTask.startBatch(" + JSONObject.quote(taskKind) + ","
                + JSONObject.quote(Long.toString(taskToken)) + "," + new JSONArray(currentIds).toString()
                + "," + batchPrivate + "," + JSONObject.quote(batchCsrf) + ");";
        final long queueToken = taskToken;
        webView.evaluateJavascript(script, accepted -> {
            if (taskToken == queueToken && gate.shouldRejectQueueStart(queueToken, running, batchQueueActive, accepted))
                stopBatch("网页拒绝启动新队列，当前任务未替换已有任务。");
        });
    }

    private void beginTask(String kind, String id, String url) {
        taskKind = kind;
        taskId = id;
        waitingForResult = true;
        taskToken = gate.begin(kind, id, url);
        armTimeout(taskToken);
        replaceWebView(taskToken);
        webView.loadUrl(url);
    }

    private void armTimeout(long token) {
        if (taskTimeout != null) handler.removeCallbacks(taskTimeout);
        if (paused) { taskTimeout = null; return; }
        taskTimeout = () -> {
            if (!operationActive() || paused || !gate.isActive(token)) return;
            waitingForResult = false;
            if (checkingLogin || "check".equals(taskKind)) {
                stopLoginCheck("登录检查超过 60 秒，已停止。请检查网络或在应用内打开 Pixiv。");
            } else if ("bookmark".equals(taskKind) || "unbookmark".equals(taskKind)) {
                stopBatch("队列超过 60 秒没有进展，已停止。已提交但未核验的作品标为待核验，不会自动重试。");
            } else {
                stopBatch("登录和令牌检查超过 60 秒，已停止，当前批次尚未提交。请检查网络后重试。");
            }
        };
        handler.postDelayed(taskTimeout, 60000L);
    }

    private void handleBookmarkResult(String id, String json) {
        if (!reportedIds.add(id)) return;
        String state = "fail", detail = "返回值无效";
        try {
            JSONObject value = new JSONObject(json);
            state = value.optString("state", "fail");
            detail = value.optString("detail", "");
        } catch (Exception ignored) {}
        String label;
        if ("ok".equals(state) || "removed".equals(state) || "changed".equals(state)) {
            ok++;
            if ("changed".equals(state)) converted++;
            label = "removed".equals(state) ? "已取消" : "changed".equals(state) ? "已转换" : "成功";
        } else if ("skip".equals(state)) {
            skip++; label = "跳过";
            detail = (batchRemove ? "未收藏，跳过" : "收藏模式相同，跳过") + suffix(detail);
        } else if ("wrongid".equals(state) || "missing".equals(state)) {
            invalid++; label = "无效/跳过";
        } else if ("pending".equals(state)) {
            pendingCount++; label = "待核验";
        } else if ("stopped".equals(state)) {
            label = "未提交";
        } else {
            fail++;
            label = "auth".equals(state) ? "登录/权限受限" : "blocked".equals(state) ? "访问受限" : "失败";
        }
        recordResult(id, label, detail);
        appendLog("[" + reportedIds.size() + "/" + currentIds.size() + "] " + id + " " + label + suffix(detail));
        if (!"pending".equals(state)) {
            final boolean journaled;
            synchronized (taskLock) { journaled = pendingMutations.remove(id); }
            if (journaled) {
                final String token = Long.toString(taskToken);
                stateIo.execute(() -> {
                    try { recovery.markVerified(token, id); }
                    catch (Exception e) { postFileMessage("保存核验状态失败；恢复时将保守显示待核验：" + e.getMessage()); }
                });
            }
        }
        batchIndex = reportedIds.size();
        progress.setProgress((int) Math.round(batchIndex * 100.0 / currentIds.size()));
        if (!paused) armTimeout(taskToken);
        persistState();
    }

    private void togglePause() {
        if (!running) return;
        paused = !paused;
        pauseBtn.setText(paused ? "继续" : "暂停");
        if (paused) {
            if (taskTimeout != null) handler.removeCallbacks(taskTimeout);
            taskTimeout = null;
            webView.evaluateJavascript("if(window.PixivBatchTask)PixivBatchTask.pause();", null);
            appendLog("已请求暂停：已发出的请求会完成，不再发出新请求；继续后核验并处理队列。");
        } else {
            webView.evaluateJavascript("if(window.PixivBatchTask)PixivBatchTask.resume();", null);
            appendLog("继续任务。");
            if (batchQueueActive) armTimeout(taskToken); else if (!batchCsrf.isEmpty()) processNext(); else armTimeout(taskToken);
        }
        persistState();
    }

    private String suffix(String s) {
        return (s == null || s.isEmpty()) ? "" : "（" + s + "）";
    }

    private void finishBatch() {
        running = false;
        paused = false;
        waitingForResult = false;
        cancelPending();
        setEditingEnabled(true);
        startBtn.setEnabled(true);
        pauseBtn.setEnabled(false);
        pauseBtn.setText("暂停");
        stopBtn.setEnabled(false);
        progress.setProgress(100);
        if (batchRemove) {
            appendLog(String.format(Locale.CHINA, "完成：已取消 %d，未收藏跳过 %d，无效/不存在 %d，失败 %d。", ok, skip, invalid, fail));
        } else {
            appendLog(String.format(Locale.CHINA, "完成：成功 %d（其中转换 %d），同模式跳过 %d，无效/不存在 %d，失败 %d。", ok, converted, skip, invalid, fail));
        }
        showMain();
        persistState();
    }

    private void stopBatch(String reason) {
        if (!running) return;
        synchronized (taskLock) {
            batchQueueActive = false;
            for (String id : pendingMutations) {
                if (reportedIds.add(id)) {
                    pendingCount++;
                    recordResult(id, "待核验", "操作曾提交但尚未核验；请在 Pixiv 核对，不会自动重试");
                }
            }
            running = false;
        }
        paused = false;
        waitingForResult = false;
        cancelPending();
        setEditingEnabled(true);
        startBtn.setEnabled(true);
        pauseBtn.setEnabled(false);
        pauseBtn.setText("暂停");
        stopBtn.setEnabled(false);
        appendLog(reason + (pendingCount == 0 ? "" : " 待核验 " + pendingCount + " 个，请核对后再手动处理。"));
        showMain();
        persistState();
    }

    private String userStopReason() {
        if (batchQueueActive) return "用户已停止。未提交的作品保留在输入中，已提交但未核验的作品标为待核验。";
        return "用户已停止。";
    }

    private void setEditingEnabled(boolean enabled) {
        for (View control : editControls) control.setEnabled(enabled);
    }

    private void cancelPending() {
        batchQueueActive = false;
        gate.cancel();
        batchCsrf = "";
        if (taskTimeout != null) handler.removeCallbacks(taskTimeout);
        taskTimeout = null;
        if (webView != null) {
            webView.evaluateJavascript("if(window.PixivBatchTask)PixivBatchTask.cancel();", null);
            webView.stopLoading();
        }
    }

    private void clearLoginSession() {
        if (operationActive()) return;
        new AlertDialog.Builder(this)
                .setTitle("清除 Pixiv 登录")
                .setMessage("将清除本应用 WebView 中的 Pixiv Cookie 和网页缓存。不会影响你其他浏览器里的 Pixiv 登录。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", (dialog, which) -> {
                    CookieManager cm = CookieManager.getInstance();
                    cm.removeAllCookies(value -> runOnUiThread(() -> {
                        cm.flush();
                        webView.clearCache(true);
                        webView.clearHistory();
                        webView.loadUrl("about:blank");
                        loginStatus.setText("● 登录状态：已清除");
                        appendLog("已清除本应用内 Pixiv 登录 Cookie 与网页缓存。");
                        persistState();
                    }));
                })
                .show();
    }

    private void openProxySettings() {
        new AlertDialog.Builder(this)
                .setTitle("代理设置")
                .setMessage("安卓版本使用系统网络/VPN。使用 NekoBox 时，请开启 VPN/TUN 并确保本应用未被分应用规则排除。这里可打开 Android 的 VPN 设置。")
                .setNegativeButton("取消", null)
                .setPositiveButton("打开 VPN 设置", (dialog, which) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                    }
                })
                .show();
    }

    private void toggleWallpaperMode() {
        wallpaperOnly = !wallpaperOnly;
        // Show the complete picture in display mode, and fill the background in tool mode.
        wallpaperView.setScaleType(wallpaperOnly ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        if (wallpaperOnly) {
            mainScroll.setVisibility(View.GONE);
            wallpaperView.bringToFront();
            wallpaperControls.bringToFront();
            wallpaperToggle.setText("再见兽娘麻麻⊙﹏⊙");
        } else {
            mainScroll.setVisibility(View.VISIBLE);
            mainScroll.bringToFront();
            wallpaperControls.bringToFront();
            wallpaperToggle.setText("看看兽娘麻麻˃ 𖥦 ˂");
        }
        scheduleSnapshot();
    }

    private void recordResult(String id, String result, String detail) {
        if (resultRows.length() == 0) resultRows.append("作品 ID\t结果\t说明\n");
        resultRows.append(id).append('\t').append(result == null ? "" : result).append('\t')
                .append(detail == null ? "" : detail.replace('\n', ' ')).append('\n');
        if (resultView != null) resultView.setText(resultRows.toString());
    }

    private void exportResults() {
        if (resultRows.length() == 0 || resultRows.toString().trim().equals("作品 ID\t结果\t说明")) {
            toast("还没有可导出的任务结果");
            return;
        }
        createTextFile("Pixiv收藏结果_" + timestamp() + ".txt", resultRows.toString(), REQ_EXPORT_RESULTS);
    }

    private void saveLog() {
        if (log == null || log.length() == 0) {
            toast("当前没有日志");
            return;
        }
        createTextFile("Pixiv处理日志_" + timestamp() + ".txt", log.getText().toString(), REQ_SAVE_LOG);
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date());
    }

    private boolean beginFileSelection(int requestCode) {
        if (restoringState || fileBusy || recovery == null) { toast("正在处理文件，请稍后再试"); return false; }
        try { fileLease = recovery.claimFileOperation(storageOwner); }
        catch (Exception e) { toast(e.getMessage()); return false; }
        fileBusy = true; pendingFileRequest = requestCode;
        return true;
    }

    private void finishFileLease(String lease, String operationId) {
        recovery.releaseFileOperation(lease);
        runOnUiThread(() -> {
            if (!activityAlive || !fileLease.equals(lease)) return;
            fileLease = ""; fileBusy = false;
            if (pendingExportId.equals(operationId)) pendingExportId = "";
            pendingFileRequest = 0;
        });
    }

    private void openTextImport() {
        if (operationActive() || !beginFileSelection(REQ_IMPORT_TXT)) return;
        pendingExportId = "";
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("text/plain");
        try { startActivityForResult(intent, REQ_IMPORT_TXT); }
        catch (Exception e) { finishFileLease(fileLease, ""); toast("无法打开文件选择器：" + e.getMessage()); }
    }

    private void createTextFile(String name, String content, int requestCode) {
        if (!beginFileSelection(requestCode)) return;
        final String lease = fileLease;
        fileIo.execute(() -> {
            try {
                RecoveryStore.Export operation = recovery.prepareOwnedExport(lease, requestCode, content);
                runOnUiThread(() -> {
                    if (!activityAlive || !recovery.isOwner(storageOwner)) { finishFileLease(lease, operation.getId()); return; }
                    launchExportPicker(name, operation);
                });
            } catch (Exception e) {
                recoverExport(name, requestCode, lease, e.getMessage());
            }
        });
    }

    private void recoverExport(String name, int requestCode, String lease, String detail) {
        try {
            final RecoveryStore.Export operation = recovery.pendingExport(lease, requestCode);
            runOnUiThread(() -> {
                if (!activityAlive || !recovery.isOwner(storageOwner)) { finishFileLease(lease, operation.getId()); return; }
                final boolean[] leaseRetained = { false };
                AlertDialog dialog = new AlertDialog.Builder(this).setTitle("上次保存尚未完成")
                        .setMessage("可继续保存这次操作的完整快照，或取消后重新导出。")
                        .setPositiveButton("继续上次保存", (d, w) -> { leaseRetained[0] = true; launchExportPicker(name, operation); })
                        .setNegativeButton("取消上次保存", (d, w) -> {
                            leaseRetained[0] = true; // onDismiss must not release queued/admitted cancellation.
                            try {
                                fileIo.execute(() -> {
                                    try {
                                        recovery.beginProviderIo(lease);
                                        recovery.clearOwnedExport(lease, operation.getId(), requestCode);
                                    } catch (Exception e) { postFileMessage("无法取消保存：" + e.getMessage()); }
                                    finally { finishFileLease(lease, operation.getId()); }
                                });
                            } catch (RuntimeException rejected) {
                                finishFileLease(lease, operation.getId());
                                postFileMessage("取消保存未排入队列：" + rejected.getMessage());
                            }
                        }).create();
                dialog.setOnDismissListener(d -> { if (!leaseRetained[0]) finishFileLease(lease, operation.getId()); });
                dialog.show();
            });
        } catch (Exception e) { finishFileLease(lease, ""); postFileMessage("文件保存失败：" + detail); }
    }

    private void launchExportPicker(String name, RecoveryStore.Export operation) {
        pendingExportId = operation.getId(); pendingFileRequest = operation.getRequestCode();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, name);
        try { startActivityForResult(intent, operation.getRequestCode()); }
        catch (Exception e) { finishFileLease(fileLease, operation.getId()); toast("无法打开保存选择器；快照已保留：" + e.getMessage()); }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        handleFileResult(requestCode, resultCode, data);
    }

    private void handleFileResult(int requestCode, int resultCode, Intent data) {
        if (!activityAlive || recovery == null || requestCode != pendingFileRequest) return;
        if (requestCode != REQ_IMPORT_TXT && requestCode != REQ_EXPORT_RESULTS && requestCode != REQ_SAVE_LOG) return;
        if (!recovery.isOwner(storageOwner)) return;
        if (restoringState) {
            handler.postDelayed(() -> handleFileResult(requestCode, resultCode, data), 50);
            return;
        }
        final String operationId = pendingExportId; // Never read a later picker operation's identity inside the worker.
        if (requestCode != REQ_IMPORT_TXT && operationId.isEmpty()) { toast("保存操作标识缺失，未写入文件"); return; }
        try {
            if (fileLease.isEmpty()) fileLease = recovery.claimFileOperation(storageOwner); // Recreated picker/process: new runtime lease, same durable UUID.
        } catch (Exception e) { toast("另一个文件读写仍在进行，未接管此回调：" + e.getMessage()); return; }
        final String lease = fileLease;
        fileBusy = true;
        final Uri uri = data == null ? null : data.getData();
        fileIo.execute(() -> {
            try {
                recovery.beginProviderIo(lease); // Includes cancellation ownership; a retired picker may not clear a resumed operation.
                if (resultCode != RESULT_OK || uri == null) {
                    if (requestCode != REQ_IMPORT_TXT) recovery.clearOwnedExport(lease, operationId, requestCode);
                    return;
                }
                if (requestCode == REQ_IMPORT_TXT) {
                    final String text;
                    try (InputStream in = getContentResolver().openInputStream(uri)) { text = RecoveryStore.readText(in); }
                    runOnUiThread(() -> {
                        if (!activityAlive || !recovery.isOwner(storageOwner)) return;
                        input.setText(text); input.setSelection(input.length()); parseIds(false);
                        appendLog("已导入 TXT 并完成解析。"); persistState();
                    });
                } else {
                    // Validate the exact complete operation BEFORE opening/truncating provider output.
                    String content = recovery.readExport(operationId, requestCode);
                    try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                        if (out == null) throw new IllegalStateException("无法打开输出文件");
                        out.write(content.getBytes(StandardCharsets.UTF_8)); out.flush();
                    }
                    recovery.clearOwnedExport(lease, operationId, requestCode); // Compare-and-clear exact UUID, never request category alone.
                    postFileMessage(requestCode == REQ_EXPORT_RESULTS ? "结果已导出" : "日志已保存");
                }
            } catch (Exception e) {
                postFileMessage("文件操作失败" + (requestCode == REQ_IMPORT_TXT ? "：" : "，保存快照仍保留：") + e.getMessage());
            } finally { finishFileLease(lease, operationId); }
        });
    }

    private void postFileMessage(String message) {
        runOnUiThread(() -> { if (activityAlive) { toast(message); appendLog(message); } });
    }

    private void scheduleSnapshot() {
        if (restoringState || recovery == null || !activityAlive) return;
        if (snapshotTask != null) handler.removeCallbacks(snapshotTask);
        snapshotTask = this::persistState;
        handler.postDelayed(snapshotTask, 500);
    }

    private void persistState() {
        if (restoringState || recovery == null || !activityAlive) return;
        if (snapshotTask != null) handler.removeCallbacks(snapshotTask);
        snapshotTask = null;
        Map<String, String> values = new HashMap<>();
        values.put("input", input.getText().toString());
        values.put("results", resultRows.toString());
        values.put("log", log.getText().toString());
        values.put("ids", joinIds(currentIds));
        values.put("private", Boolean.toString(privateRadio.isChecked()));
        values.put("remove", Boolean.toString(batchRemove));
        values.put("index", Integer.toString(batchIndex));
        values.put("progress", Integer.toString(progress.getProgress()));
        values.put("active", Boolean.toString(running || checkingLogin));
        values.put("wallpaperOnly", Boolean.toString(wallpaperOnly));
        values.put("loginStatus", loginStatus.getText().toString());
        values.put("ok", Integer.toString(ok)); values.put("converted", Integer.toString(converted));
        values.put("skip", Integer.toString(skip)); values.put("invalid", Integer.toString(invalid)); values.put("fail", Integer.toString(fail));
        latestSnapshot = recovery.captureSnapshot(storageOwner, values);
        final RecoveryStore.Snapshot captured = latestSnapshot;
        stateIo.execute(() -> {
            try { recovery.commitSnapshot(captured); }
            catch (Exception e) { postFileMessage("任务恢复数据未保存：" + e.getMessage()); }
        });
    }

    private String joinIds(List<String> ids) {
        StringBuilder value = new StringBuilder();
        for (String id : ids) { if (value.length() > 0) value.append(','); value.append(id); }
        return value.toString();
    }

    private void restoreState() {
        stateIo.execute(() -> {
            Map<String, String> values = new HashMap<>();
            List<String> pending = new ArrayList<>();
            String error = "";
            if (recovery != null) {
                RecoveryStore.Restored restored;
                try { restored = recovery.restoreStopped(storageOwner); }
                catch (Exception retired) { return; }
                values = restored.getSnapshot();
                pending = restored.getPendingIds();
                StringBuilder errors = new StringBuilder();
                for (String message : restored.getErrors()) { if (errors.length() > 0) errors.append('\n'); errors.append(message); }
                error = errors.toString();
            }
            final Map<String, String> saved = values;
            final List<String> uncertain = pending;
            final String failure = error;
            runOnUiThread(() -> {
                if (!activityAlive) return;
                input.setText(saved.get("input"));
                resultRows.setLength(0); resultRows.append(saved.containsKey("results") ? saved.get("results") : "");
                resultView.setText(resultRows.length() == 0 ? "暂无结果" : resultRows.toString());
                if (saved.containsKey("log")) log.setText(saved.get("log"));
                currentIds = saved.containsKey("ids") && !saved.get("ids").isEmpty() ?
                        new ArrayList<>(Arrays.asList(saved.get("ids").split(","))) : PixivIdParser.extract(input.getText().toString());
                batchIndex = number(saved, "index"); ok = number(saved, "ok"); converted = number(saved, "converted");
                skip = number(saved, "skip"); invalid = number(saved, "invalid"); fail = number(saved, "fail");
                batchRemove = Boolean.parseBoolean(saved.get("remove"));
                privateRadio.setChecked(Boolean.parseBoolean(saved.get("private")));
                publicRadio.setChecked(!privateRadio.isChecked());
                progress.setProgress(Math.max(0, Math.min(100, number(saved, "progress"))));
                detected.setText("已识别：" + currentIds.size() + " 个作品（恢复）");
                // Session secrets never enter the snapshot; always re-check login for a new task.
                loginStatus.setText("● 登录状态：需重新检查");
                if (Boolean.parseBoolean(saved.get("wallpaperOnly"))) toggleWallpaperMode();
                if (Boolean.parseBoolean(saved.get("interrupted")))
                    appendLog("上次任务因页面重建或进程退出而中断，已恢复为停止状态；不会自动重试。未报告的作品仍在输入中。");
                pendingCount = uncertain.size();
                for (String id : uncertain) {
                    if (!resultRows.toString().contains("\n" + id + "\t待核验"))
                        recordResult(id, "待核验（恢复）", "上次操作可能已提交；请在 Pixiv 核对，不会自动重试");
                }
                if (!uncertain.isEmpty()) appendLog("恢复了 " + uncertain.size() + " 个待核验作品。请先核对实际收藏状态。");
                if (!failure.isEmpty()) appendLog("恢复文件读取失败：" + failure);
                restoringState = false;
                setEditingEnabled(true); startBtn.setEnabled(true);
                persistState();
            });
        });
    }

    private int number(Map<String, String> values, String key) {
        try { return Integer.parseInt(values.get(key)); } catch (Exception e) { return 0; }
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        persistState(); // Files carry text; only the small picker identity enters Bundle.
        outState.putString("export_operation", pendingExportId);
        outState.putInt("file_request", pendingFileRequest);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onPause() {
        persistState();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    private void showWeb(String url) {
        FrameLayout webLayer = findWebLayer();
        mainView.setVisibility(View.GONE);
        webLayer.setVisibility(View.VISIBLE);
        backFromWeb.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
    }

    private void showWebHidden() {
        FrameLayout webLayer = findWebLayer();
        webLayer.setVisibility(View.INVISIBLE);
        mainView.setVisibility(View.VISIBLE);
        backFromWeb.setVisibility(View.GONE);
    }

    private void showMain() {
        findWebLayer().setVisibility(View.GONE);
        mainView.setVisibility(View.VISIBLE);
    }

    private FrameLayout findWebLayer() {
        for (int i = 0; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if ("webLayer".equals(v.getTag())) return (FrameLayout) v;
        }
        throw new IllegalStateException("web layer missing");
    }

    @Override public void onBackPressed() {
        if (running) {
            stopBatch(userStopReason());
            return;
        }
        if (checkingLogin) {
            stopLoginCheck("登录检查已取消。");
            return;
        }
        FrameLayout wl = findWebLayer();
        if (wl.getVisibility() == View.VISIBLE) {
            if (webView.canGoBack()) webView.goBack(); else showMain();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (running) stopBatch("页面已关闭；已提交但未核验的作品需要手动核对。");
        persistState();
        activityAlive = false;
        if (recovery != null && storageOwner != null) recovery.retireOwner(storageOwner);
        checkingLogin = false;
        batchCsrf = "";
        gate.cancel();
        handler.removeCallbacksAndMessages(null);
        fileIo.shutdown();
        stateIo.shutdown();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    private class JsBridge {
        @JavascriptInterface public boolean submitted(String token, String idsJson) {
            // Bridge private-file I/O runs outside taskLock; the directory monitor never covers provider I/O.
            List<String> ids = new ArrayList<>();
            final RecoveryStore.Snapshot snapshot;
            synchronized (taskLock) {
                if (!running || !batchQueueActive || !Long.toString(taskToken).equals(token) || recovery == null) return false;
                try {
                    JSONArray values = new JSONArray(idsJson);
                    if (values.length() < 1 || values.length() > 5) return false;
                    for (int i = 0; i < values.length(); i++) {
                        String id = values.getString(i);
                        if (!currentIds.contains(id)) return false;
                        ids.add(id);
                    }
                } catch (Exception e) { return false; }
                pendingMutations.addAll(ids);
                snapshot = latestSnapshot;
            }
            try {
                if (!recovery.commitSnapshot(snapshot) && !recovery.commitCurrentSnapshot(storageOwner)) return false;
                recovery.markSubmitted(token, ids);
                synchronized (taskLock) {
                    if (!running || !batchQueueActive || !Long.toString(taskToken).equals(token) || !recovery.isOwner(storageOwner)) return false;
                }
                runOnUiThread(() -> {
                    if (running && batchQueueActive && !paused && Long.toString(taskToken).equals(token)) armTimeout(taskToken);
                });
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface public void event(String token, String state, String detail) {
            runOnUiThread(() -> {
                if (!running || !batchQueueActive || !Long.toString(taskToken).equals(token)) return;
                if ("done".equals(state)) finishBatch();
                else if ("stopped".equals(state)) stopBatch(detail);
            });
        }

        @JavascriptInterface public void result(String token, String kind, String id, String json) {
            runOnUiThread(() -> {
                if (!operationActive()) return;
                long received;
                try { received = Long.parseLong(token); } catch (Exception e) { return; }
                if (batchQueueActive) {
                    if (!running || received != taskToken || !taskKind.equals(kind) || !currentIds.contains(id)) return;
                    handleBookmarkResult(id, json);
                    return;
                }
                if (!gate.complete(received, kind, id)) return;
                waitingForResult = false;
                if (taskTimeout != null) handler.removeCallbacks(taskTimeout);

                if ("check".equals(kind)) {
                    checkingLogin = false;
                    setEditingEnabled(true);
                    try {
                        JSONObject value = new JSONObject(json);
                        if ("ok".equals(value.optString("state"))) {
                            String uid = value.optString("userId", "");
                            loginStatus.setText(uid.isEmpty() ? "● 登录状态：已登录" : "● 已登录：Pixiv 用户 " + uid);
                            appendLog(uid.isEmpty() ? "登录检查成功：会话有效。" : "登录检查成功：Pixiv 用户 " + uid + "。");
                        } else {
                            loginStatus.setText("● 登录状态：未登录或检查失败");
                            appendLog("登录检查失败：" + value.optString("detail", "请先登录 Pixiv"));
                        }
                    } catch (Exception e) {
                        loginStatus.setText("● 登录状态：检查失败");
                        appendLog("登录检查返回值无效。");
                    }
                    cancelPending();
                    showMain();
                    persistState();
                    return;
                }

                if ("login".equals(kind)) {
                    try {
                        JSONObject value = new JSONObject(json);
                        if ("ok".equals(value.optString("state"))) {
                            String csrf = value.optString("csrf", "");
                            if (!csrf.matches("^[A-Za-z0-9_-]{20,128}$")) {
                                loginStatus.setText("● 登录状态：会话有效，但令牌失败");
                                stopBatch("登录已确认，但未取得有效收藏安全令牌，当前批次尚未提交。");
                                return;
                            }
                            String uid = value.optString("userId", "");
                            loginStatus.setText(uid.isEmpty() ? "● 登录状态：已登录" : "● 已登录：Pixiv 用户 " + uid);
                            batchCsrf = csrf;
                            appendLog("登录会话与收藏安全令牌均已取得，"
                                    + (batchRemove ? "开始每组最多 5 条取消并核验。" : "开始快速队列并逐个核验。"));
                            if (!paused) processNext();
                        } else {
                            loginStatus.setText("● 登录状态：未登录或检查失败");
                            stopBatch(value.optString("detail", "请先登录 Pixiv"));
                        }
                    } catch (Exception e) {
                        stopBatch("登录和令牌检查返回值无效，当前批次尚未提交。");
                    }
                } else {
                    handleBookmarkResult(id, json);
                }
            });
        }
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(Color.rgb(48, 37, 27));
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD);
        return v;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setPadding(dp(8), 0, dp(8), 0);
        return b;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout panel(LinearLayout parent) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(14), dp(14), dp(14));
        panel.setBackground(surface(Color.argb(205, 255, 249, 240), 16));
        add(parent, panel, 0, 0, 0, 14);
        return panel;
    }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        background.setStroke(dp(1), Color.argb(180, 243, 224, 197));
        return background;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, dp(48), 1f);
    }

    private LinearLayout.LayoutParams weightWithLeft() {
        LinearLayout.LayoutParams p = weight();
        p.leftMargin = dp(6);
        return p;
    }

    private void add(LinearLayout box, View v, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        box.addView(v, p);
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void appendLog(String s) {
        if (log == null) return;
        if (log.length() > 0) log.append("\n");
        log.append(s);
        log.post(() -> {
            if (log.getLayout() != null) {
                int bottom = log.getLayout().getHeight() - log.getHeight() + log.getPaddingTop() + log.getPaddingBottom();
                log.scrollTo(0, Math.max(0, bottom));
            }
        });
    }

    private void hideKeyboard() {
        View v = getCurrentFocus();
        if (v != null) {
            InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    }
}
