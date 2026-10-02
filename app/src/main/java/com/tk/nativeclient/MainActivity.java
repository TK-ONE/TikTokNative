package com.tk.nativeclient;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.WebBackForwardList;
import android.webkit.WebHistoryItem;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * V45.3.16 TIKTOK ICON / STARTUP SPLASH LOCK
 *
 * User reference is the contract:
 * - Home: edge-to-edge video, top LIVE / 社区 / 好友 / 关注 / 推荐 / search,
 *   bottom 首页 / 商城 / + / 收件箱 / 主页.
 * - TikTok web remains the real engine for video, author text, live counts, action rail,
 *   comments, login, upload, inbox and profile.
 * - Android draws only the native top/bottom shell and maps those controls to TikTok's
 *   existing web entries. No fake functions or fake counters.
 * - Home visual contract is frozen from the user-confirmed V44.0.3 layout.
 * - All secondary pages use a real mobile WebView identity instead of squeezing desktop pages into phone width.
 * - LIVE is immersive/full-screen; TikTok's own mobile live UI is preserved and vertical swipe gets a non-invasive fallback.
 * - Home feed behavior keeps TikTok ownership; only an exact post ID seen recently can trigger a bounded one-card freshness skip.
 * - A one-time WebView HTTP cache reset removes stale V44.2-V45.1 feed/media cache without clearing TikTok cookies or login state.
 * - A guarded first-card recovery only advances when the current card is demonstrably stalled and the next card is already render-ready.
 * - No DOM cloning, no MutationObserver, no dual WebView, no recursive document-wide click fallback.
 */
public class MainActivity extends Activity {

    private static final String HOME = "https://www.tiktok.com/foryou";
    private static final String ROOT = "https://www.tiktok.com/";
    private static final int REQ_FILE = 1001;
    private static final int REQ_WEB_PERMISSION = 1002;
    private static final int REQ_GEO = 1003;
    private static final String FEED_PREFS = "tk_feed_freshness_v1";
    private static final String MIGRATION_PREFS = "tk_wrapper_migrations";
    private static final String MIGRATION_V4533_CACHE = "v45_3_3_webview_rebase_cache_reset";
    private static final int COMMENT_OPEN_MISS_LIMIT = 8;
    private static final int COMMENT_DETAIL_MISS_LIMIT = 36;
    private static final String RECENT_FEED_PREFS = "tk_recent_feed_posts_v1";
    private static final String RECENT_FEED_KEY = "recent_posts";
    private static final long RECENT_FEED_WINDOW_MS = 72L * 60L * 60L * 1000L;
    private static final long RECENT_FEED_SAME_SESSION_GRACE_MS = 15L * 60L * 1000L;
    private static final int RECENT_FEED_MAX_IDS = 500;

    /* Desktop identity keeps the known continuous desktop feed/data path; viewport and CSS provide phone presentation. */
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/140.0.0.0 Safari/537.36";

    private static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 16; Mobile) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/140.0.0.0 Mobile Safari/537.36";

    private FrameLayout root;
    private WebView web;
    private ProgressBar progress;
    private FrameLayout topOverlay;
    private FrameLayout bottomOverlay;
    private LinearLayout topRow;
    private LinearLayout bottomRow;
    private ImageView secondaryBack;
    private FrameLayout startupSplash;
    private boolean startupSplashDismissed;

    private TabRef tabCommunity;
    private TabRef tabFriends;
    private TabRef tabFollowing;
    private TabRef tabForYou;
    private BottomRef bottomHome;
    private BottomRef bottomShop;
    private BottomRef bottomInbox;
    private BottomRef bottomProfile;

    private int insetTop;
    private int insetBottom;
    private String lastMainUrl = HOME;
    private String selectedTop = "foryou";
    private String selectedBottom = "home";
    private final Object recentFeedSeenLock = new Object();
    private final LinkedHashMap<String, Long> recentFeedSeen = new LinkedHashMap<>();
    private boolean recentFeedSeenLoaded;
    private int recentFeedSeenDirty;
    private volatile boolean ownProfileRoute;
    private String ownProfilePath;
    private volatile boolean commentOpen;
    private boolean commentSeen;
    private int commentMisses;
    private boolean rebuildingRenderer;
    private boolean searchTransient;
    private volatile boolean externalProfileRoute;
    private boolean mobileLiveMode;
    private boolean usingMobileUa;

    private boolean firstCardRecoveryScheduled;
    private boolean feedReturnWakePending;
    private int feedRecoveryGeneration;
    private int historyReturnGeneration;
    private String pendingHistoryTarget;

    private String shellCss = "";
    private String bridgeHook = "";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable commentPresenceCheck = this::checkCommentPresence;

    private ValueCallback<Uri[]> fileChooser;
    private PermissionRequest pendingWebPermission;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        configureWindow();
        shellCss = readAsset("mobile_shell.css");
        bridgeHook = readAsset("bridge_hook.js");

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = createWebView();
        resetLegacyFeedCacheOnce();
        root.addView(web, fullParams());

        buildReferenceShell();

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
        p.gravity = Gravity.TOP;
        root.addView(progress, p);

        buildStartupSplash();

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            insetTop = Math.max(0, insets.getSystemWindowInsetTop());
            insetBottom = Math.max(0, insets.getSystemWindowInsetBottom());
            applyInsets();
            return insets;
        });

        setContentView(root);
        root.requestApplyInsets();
        ui.postDelayed(this::forceDismissStartupSplash, 12000);

        if (state != null) {
            lastMainUrl = state.getString("last_url", HOME);
            ownProfileRoute = state.getBoolean("own_profile", false);
            ownProfilePath = state.getString("own_profile_path");
            searchTransient = state.getBoolean("search_transient", false);
            Bundle webState = state.getBundle("web_state");
            /* Do not restore a serialized desktop Feed DOM after process recreation: that
               can replay the exact same already-rendered cards. Secondary pages still
               restore under the same UA identity they originally used. */
            if (!isFeedUrl(lastMainUrl) && webState != null) {
                usingMobileUa = shouldUseMobileUa(lastMainUrl);
                mobileLiveMode = usingMobileUa && isLiveUrl(lastMainUrl);
                web.getSettings().setUserAgentString(usingMobileUa ? MOBILE_UA : DESKTOP_UA);
                if (web.restoreState(webState) != null) {
                    updateShellForUrl(lastMainUrl);
                    return;
                }
            }
            /* A serialized Feed DOM can replay the exact already-rendered cards after
               Activity/process recreation. This was already blocked for /foryou; apply
               the same freshness rule to /following and /friends while preserving the
               selected Feed route with a clean desktop request. */
            if (isFeedUrl(lastMainUrl)) {
                loadFreshFeedRoute(lastMainUrl);
                return;
            }
        }
        loadInitialHome();
    }

    private void buildStartupSplash() {
        startupSplash = new FrameLayout(this);
        startupSplash.setBackgroundColor(Color.BLACK);
        startupSplash.setClickable(true);
        startupSplash.setFocusable(true);

        ImageView brand = new ImageView(this);
        brand.setImageResource(com.tk.nativeclient.R.drawable.splash_brand);
        brand.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        FrameLayout.LayoutParams brandLp = new FrameLayout.LayoutParams(dp(130), dp(110));
        brandLp.gravity = Gravity.CENTER;
        startupSplash.addView(brand, brandLp);

        root.addView(startupSplash, fullParams());
    }

    private void dismissStartupSplashWhenReady(String url) {
        if (startupSplashDismissed || !isTrustedTikTokUrl(url)) return;
        ui.postDelayed(() -> {
            if (startupSplashDismissed || startupSplash == null) return;
            startupSplashDismissed = true;
            startupSplash.animate()
                    .alpha(0f)
                    .setDuration(120)
                    .withEndAction(() -> {
                        if (startupSplash != null) startupSplash.setVisibility(View.GONE);
                    })
                    .start();
        }, 180);
    }

    private void forceDismissStartupSplash() {
        if (startupSplashDismissed || startupSplash == null) return;
        startupSplashDismissed = true;
        startupSplash.setVisibility(View.GONE);
    }

    private void configureWindow() {
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.setNavigationBarDividerColor(Color.TRANSPARENT);
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void buildReferenceShell() {
        topOverlay = new FrameLayout(this);
        GradientDrawable topBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xA0000000, 0x4A000000, 0x00000000});
        topOverlay.setBackground(topBg);
        root.addView(topOverlay, overlayParams(Gravity.TOP, dp(66)));

        topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setPadding(dp(9), 0, dp(7), 0);
        FrameLayout.LayoutParams trp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        trp.gravity = Gravity.BOTTOM;
        topOverlay.addView(topRow, trp);

        FrameLayout live = makeReferenceLiveButton();
        live.setOnClickListener(v -> runNavAction("live", "https://www.tiktok.com/live"));
        LinearLayout.LayoutParams liveLp = new LinearLayout.LayoutParams(dp(46), dp(34));
        liveLp.setMargins(0, 0, dp(4), 0);
        topRow.addView(live, liveLp);

        LinearLayout centerTabs = new LinearLayout(this);
        centerTabs.setOrientation(LinearLayout.HORIZONTAL);
        centerTabs.setGravity(Gravity.CENTER);
        topRow.addView(centerTabs, new LinearLayout.LayoutParams(0, dp(44), 1f));

        /* The supplied original TikTok mobile recording shows the three visible
           center tabs 社区 / 关注 / 推荐. Keep the /friends route support in the
           navigation state machine, but do not add an extra visible shell tab. */
        centerTabs.setPadding(dp(28), 0, dp(28), 0);
        tabCommunity = makeTopTab(centerTabs, "社区", "community");
        tabFriends = null;
        tabFollowing = makeTopTab(centerTabs, "关注", "following");
        tabForYou = makeTopTab(centerTabs, "推荐", "foryou");

        ImageView search = new ImageView(this);
        search.setImageResource(com.tk.nativeclient.R.drawable.ic_search);
        search.setScaleType(ImageView.ScaleType.CENTER);
        search.setOnClickListener(v -> runNavAction("search", "https://www.tiktok.com/search"));
        topRow.addView(search, new LinearLayout.LayoutParams(dp(46), dp(44)));

        bottomOverlay = new FrameLayout(this);
        GradientDrawable bottomBg = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xDA000000, 0x85000000, 0x00000000});
        bottomOverlay.setBackground(bottomBg);
        root.addView(bottomOverlay, overlayParams(Gravity.BOTTOM, dp(76)));

        bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams brp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        brp.gravity = Gravity.TOP;
        bottomOverlay.addView(bottomRow, brp);

        bottomHome = makeBottomItem(com.tk.nativeclient.R.drawable.ic_home, "首页", "home", () -> runNavAction("foryou", HOME));
        bottomShop = makeBottomItem(com.tk.nativeclient.R.drawable.ic_shop, "商城", "shop", () -> runNavAction("shop", "https://www.tiktok.com/shop"));
        bottomRow.addView(makeCreateButton(), weighted());
        bottomInbox = makeBottomItem(com.tk.nativeclient.R.drawable.ic_inbox, "收件箱", "inbox", () -> runNavAction("inbox", "https://www.tiktok.com/messages"));
        bottomProfile = makeBottomItem(com.tk.nativeclient.R.drawable.ic_profile, "主页", "profile", () -> {
            ownProfileRoute = true;
            ownProfilePath = null;
            externalProfileRoute = false;
            runNavAction("profile", null);
        });

        secondaryBack = new ImageView(this);
        secondaryBack.setImageResource(com.tk.nativeclient.R.drawable.ic_back);
        secondaryBack.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        secondaryBack.setPadding(dp(11), dp(11), dp(11), dp(11));
        GradientDrawable backBg = new GradientDrawable();
        backBg.setColor(0x88000000);
        backBg.setShape(GradientDrawable.OVAL);
        secondaryBack.setBackground(backBg);
        secondaryBack.setVisibility(View.GONE);
        secondaryBack.setOnClickListener(v -> onBackPressed());
        FrameLayout.LayoutParams sbp = new FrameLayout.LayoutParams(dp(46), dp(46));
        sbp.gravity = Gravity.TOP | Gravity.START;
        sbp.leftMargin = dp(12);
        root.addView(secondaryBack, sbp);

        applyInsets();
        setTopSelected("foryou");
        setBottomSelected("home");
    }

    private FrameLayout makeReferenceLiveButton() {
        FrameLayout outer = new FrameLayout(this);

        TextView screen = new TextView(this);
        screen.setText("LIVE");
        screen.setTextColor(Color.WHITE);
        screen.setTextSize(8.2f);
        screen.setGravity(Gravity.CENTER);
        screen.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable tv = new GradientDrawable();
        tv.setColor(0x12000000);
        tv.setStroke(dp(1), 0xF0FFFFFF);
        tv.setCornerRadius(dp(3));
        screen.setBackground(tv);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(29), dp(22));
        sp.gravity = Gravity.CENTER;
        sp.topMargin = dp(3);
        outer.addView(screen, sp);

        View antennaL = new View(this);
        antennaL.setBackgroundColor(0xF0FFFFFF);
        antennaL.setRotation(-34f);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(dp(1), dp(7));
        alp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        alp.topMargin = dp(1);
        alp.leftMargin = -dp(4);
        outer.addView(antennaL, alp);

        View antennaR = new View(this);
        antennaR.setBackgroundColor(0xF0FFFFFF);
        antennaR.setRotation(34f);
        FrameLayout.LayoutParams arp = new FrameLayout.LayoutParams(dp(1), dp(7));
        arp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        arp.topMargin = dp(1);
        arp.leftMargin = dp(4);
        outer.addView(antennaR, arp);
        return outer;
    }

    private TabRef makeTopTab(LinearLayout parent, String label, String key) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(Color.WHITE);
        text.setTextSize(15.2f);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(3), 0, dp(3), 0);
        View line = new View(this);
        GradientDrawable lineBg = new GradientDrawable();
        lineBg.setColor(Color.WHITE);
        lineBg.setCornerRadius(dp(2));
        line.setBackground(lineBg);
        box.addView(text, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(24), dp(2));
        ll.setMargins(0, dp(2), 0, 0);
        box.addView(line, ll);
        box.setOnClickListener(v -> {
            if ("community".equals(key)) runNavAction("community", "https://www.tiktok.com/explore");
            else if ("friends".equals(key)) runNavAction("friends", "https://www.tiktok.com/friends");
            else if ("following".equals(key)) runNavAction("following", "https://www.tiktok.com/following");
            else runNavAction("foryou", HOME);
        });
        parent.addView(box, new LinearLayout.LayoutParams(0, dp(42), 1f));
        return new TabRef(box, text, line, key);
    }

    private BottomRef makeBottomItem(int iconRes, String label, String key, Runnable action) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(Color.WHITE);
        text.setTextSize(10.5f);
        text.setGravity(Gravity.CENTER);
        box.addView(icon, new LinearLayout.LayoutParams(dp(27), dp(30)));
        box.addView(text, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));
        box.setOnClickListener(v -> {
            selectedBottom = key;
            setBottomSelected(key);
            action.run();
        });
        bottomRow.addView(box, weighted());
        return new BottomRef(box, icon, text, key);
    }

    private View makeCreateButton() {
        FrameLayout outer = new FrameLayout(this);
        outer.setForegroundGravity(Gravity.CENTER);

        View cyan = roundedRect(0xFF25F4EE, 8);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(43), dp(31));
        cp.gravity = Gravity.CENTER;
        cp.leftMargin = -dp(4);
        outer.addView(cyan, cp);

        View pink = roundedRect(0xFFFE2C55, 8);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(dp(43), dp(31));
        pp.gravity = Gravity.CENTER;
        pp.leftMargin = dp(4);
        outer.addView(pink, pp);

        FrameLayout white = new FrameLayout(this);
        GradientDrawable wb = new GradientDrawable();
        wb.setColor(Color.WHITE);
        wb.setCornerRadius(dp(7));
        white.setBackground(wb);
        TextView plus = new TextView(this);
        plus.setText("+");
        plus.setTextColor(Color.BLACK);
        plus.setTextSize(27);
        plus.setGravity(Gravity.CENTER);
        white.addView(plus, fullParams());
        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(dp(39), dp(31));
        wp.gravity = Gravity.CENTER;
        outer.addView(white, wp);

        outer.setOnClickListener(v -> {
            ownProfileRoute = false;
            searchTransient = false;
            showTop(false);
            showBottom(false);
            openMobileSecondary("https://www.tiktok.com/upload");
        });
        return outer;
    }

    private View roundedRect(int color, int radiusDp) {
        View v = new View(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radiusDp));
        v.setBackground(bg);
        return v;
    }

    private void applyInsets() {
        if (topOverlay != null) {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) topOverlay.getLayoutParams();
            p.height = insetTop + dp(66);
            topOverlay.setLayoutParams(p);
            topOverlay.setPadding(0, insetTop, 0, 0);
        }
        if (bottomOverlay != null) {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) bottomOverlay.getLayoutParams();
            p.height = insetBottom + dp(76);
            bottomOverlay.setLayoutParams(p);
            bottomOverlay.setPadding(0, 0, 0, insetBottom);
        }
        if (secondaryBack != null) {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) secondaryBack.getLayoutParams();
            p.topMargin = insetTop + dp(10);
            secondaryBack.setLayoutParams(p);
        }
    }

    private void setTopSelected(String key) {
        selectedTop = key;
        setTabState(tabCommunity, key);
        setTabState(tabFriends, key);
        setTabState(tabFollowing, key);
        setTabState(tabForYou, key);
    }

    private void setTabState(TabRef ref, String key) {
        if (ref == null) return;
        boolean on = ref.key.equals(key);
        ref.text.setAlpha(on ? 1f : 0.78f);
        ref.text.setTypeface(Typeface.DEFAULT, on ? Typeface.BOLD : Typeface.NORMAL);
        ref.line.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
    }

    private void setBottomSelected(String key) {
        selectedBottom = key;
        setBottomState(bottomHome, key);
        setBottomState(bottomShop, key);
        setBottomState(bottomInbox, key);
        setBottomState(bottomProfile, key);
    }

    private void setBottomState(BottomRef ref, String key) {
        if (ref == null) return;
        boolean on = ref.key.equals(key);
        ref.icon.setAlpha(on ? 1f : 0.62f);
        ref.text.setAlpha(on ? 1f : 0.68f);
        ref.text.setTypeface(Typeface.DEFAULT, on ? Typeface.BOLD : Typeface.NORMAL);
    }

    private WebView createWebView() {
        WebView w = new WebView(this);
        w.setBackgroundColor(Color.BLACK);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);
        w.addJavascriptInterface(new ShellBridge(), "NativeShell");

        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(DESKTOP_UA);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, true);

        w.setWebViewClient(new StableClient());
        w.setWebChromeClient(new StableChrome());
        w.setDownloadListener(buildDownloadListener());
        return w;
    }

    private final class StableClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (request == null || !request.isForMainFrame()) return false;
            Uri uri = request.getUrl();
            if (uri == null) return false;
            String scheme = lower(uri.getScheme());
            if ("http".equals(scheme) || "https".equals(scheme)) {
                String target = uri.toString();
                lastMainUrl = target;
                /* A desktop Feed comment click can legitimately promote the current post
                   to /video or /photo while keeping TikTok's real comment DOM open. Do not
                   swap that one transition to the mobile UA or the sheet is destroyed. */
                boolean commentDetail = commentOpen && !usingMobileUa &&
                        isTrustedTikTokUrl(target) && isPostDetailUrl(target);
                if (commentDetail) {
                    updateShellForUrl(target);
                    return false;
                }
                boolean wantMobile = shouldUseMobileUa(target);
                if (wantMobile != usingMobileUa) {
                    openRouteWithIdentity(target, wantMobile);
                    return true;
                }
                if (!isSearchUrl(target)) searchTransient = false;
                mobileLiveMode = usingMobileUa && isLiveUrl(target);
                return false;
            }
            if ("intent".equals(scheme)) return handleIntentUrl(uri.toString());
            if ("mailto".equals(scheme) || "tel".equals(scheme) || "sms".equals(scheme)) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                catch (ActivityNotFoundException ignored) { }
                return true;
            }
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, uri);
                if (i.resolveActivity(getPackageManager()) != null) startActivity(i);
            } catch (Throwable ignored) { }
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            if (url != null && !url.isBlank()) {
                lastMainUrl = url;
                if (!isSearchUrl(url)) searchTransient = false;
                mobileLiveMode = usingMobileUa && isLiveUrl(url);
            }
            if (progress != null) progress.setVisibility(View.VISIBLE);
            updateShellForUrl(url);
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            super.onPageCommitVisible(view, url);
            if (url != null && !url.isBlank()) lastMainUrl = url;
            injectPresentation(view);
            updateShellForUrl(url);
            dismissStartupSplashWhenReady(url);
            wakeReturnedFeedIfNeeded(url);
            if (isForYouUrl(url)) scheduleFirstCardRecovery();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (url != null && !url.isBlank()) lastMainUrl = url;
            CookieManager.getInstance().flush();
            injectPresentation(view);
            updateShellForUrl(url);
            dismissStartupSplashWhenReady(url);
            wakeReturnedFeedIfNeeded(url);
            if (isForYouUrl(url)) scheduleFirstCardRecovery();
            if (progress != null && progress.getProgress() >= 95) progress.setVisibility(View.GONE);
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            super.doUpdateVisitedHistory(view, url, isReload);
            if (url != null && !url.isBlank()) {
                lastMainUrl = url;
                if (!isSearchUrl(url)) searchTransient = false;
                /* TikTok frequently changes secondary routes with history.pushState, so
                   shouldOverrideUrlLoading is never called. Reconcile only the browser
                   identity here; the one deliberate exception is a desktop Feed comment
                   that promoted the current post to /video or /photo while its sheet is open. */
                boolean commentDetail = commentOpen && !usingMobileUa && isPostDetailUrl(url);
                if (!commentDetail && isTrustedTikTokUrl(url)) {
                    boolean wantMobile = shouldUseMobileUa(url);
                    if (wantMobile != usingMobileUa) {
                        reloadCurrentRouteWithIdentity(url, wantMobile);
                        return;
                    }
                }
                mobileLiveMode = usingMobileUa && isLiveUrl(url);
            }
            injectPresentation(view);
            updateShellForUrl(url);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request != null && request.isForMainFrame() && progress != null) progress.setVisibility(View.GONE);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (rebuildingRenderer) return true;
            rebuildingRenderer = true;
            String restore = view == null ? lastMainUrl : view.getUrl();
            rebuildWebView(restore == null || restore.isBlank() ? HOME : restore);
            return true;
        }
    }

    private final class StableChrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (progress == null) return;
            progress.setProgress(newProgress);
            progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileChooser != null) fileChooser.onReceiveValue(null);
            fileChooser = callback;
            Intent intent;
            try { intent = params == null ? null : params.createIntent(); }
            catch (Throwable ignored) { intent = null; }
            if (intent == null) {
                intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            }
            try { startActivityForResult(intent, REQ_FILE); return true; }
            catch (ActivityNotFoundException e) {
                fileChooser.onReceiveValue(null);
                fileChooser = null;
                return false;
            }
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            if (request != null) runOnUiThread(() -> handleWebPermission(request));
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            if (request == pendingWebPermission) pendingWebPermission = null;
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            if (!isTrustedTikTokOrigin(origin)) {
                callback.invoke(origin, false, false);
                return;
            }
            if (hasLocationPermission()) {
                callback.invoke(origin, true, true);
                return;
            }
            pendingGeoOrigin = origin;
            pendingGeoCallback = callback;
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, REQ_GEO);
        }
    }

    private void injectPresentation(WebView view) {
        if (view == null || !isTrustedTikTokUrl(view.getUrl())) return;
        String css = shellCss == null ? "" : shellCss;
        String installCss = "(function(){" +
                "var id='tk-native-reference-css';var s=document.getElementById(id);" +
                "if(!s){s=document.createElement('style');s.id=id;(document.head||document.documentElement).appendChild(s);}" +
                "s.textContent=" + JSONObject.quote(css) + ";" +
                "var m=document.querySelector('meta[name=viewport]');" +
                "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}" +
                "m.setAttribute('content','width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover');" +
                "})()";
        view.evaluateJavascript(installCss, null);
        if (bridgeHook != null && !bridgeHook.isBlank()) view.evaluateJavascript(bridgeHook, null);
        applyWebMode(view, view.getUrl());
    }

    private void applyWebMode(WebView view, String url) {
        if (view == null || !isTrustedTikTokUrl(url)) return;
        String u = lower(url);
        boolean auth = isAuthUrl(u);
        boolean live = isLiveUrl(u) && !auth;
        boolean feed = isFeedUrl(u) && !auth && !live;
        boolean forYou = isForYouUrl(url) && !auth && !live;
        boolean commentFeedContext = commentOpen && !usingMobileUa && isPostDetailUrl(u) && !auth && !live;
        boolean feedPresentation = feed || commentFeedContext;
        boolean discover = isDiscoverUrl(u) && !auth;
        boolean primary = !feedPresentation && !discover && !live && !auth && (isShopUrl(u) || isInboxUrl(u) || (ownProfileRoute && isProfileUrl(u)));
        boolean mobileSecondary = usingMobileUa && !feed;
        String js = "(function(){var e=document.documentElement;" +
                "e.classList.remove('tk-native-app','tk-native-feed','tk-native-foryou','tk-native-primary','tk-native-discover','tk-native-live','tk-native-mobile-secondary','tk-native-comment-open');" +
                (auth ? "" : "e.classList.add('tk-native-app');") +
                (feedPresentation ? "e.classList.add('tk-native-feed');" : "") +
                (forYou ? "e.classList.add('tk-native-foryou');" : "") +
                (discover ? "e.classList.add('tk-native-discover');" : "") +
                (live ? "e.classList.add('tk-native-live');" : "") +
                (primary ? "e.classList.add('tk-native-primary');" : "") +
                (mobileSecondary ? "e.classList.add('tk-native-mobile-secondary');" : "") +
                (commentOpen ? "e.classList.add('tk-native-comment-open');" : "") +
                "})()";
        view.evaluateJavascript(js, null);
    }

    private String canonicalFeedUrl(String raw) {
        String path = tikTokPagePath(raw);
        if (pathIsOrUnder(path, "/following")) return "https://www.tiktok.com/following";
        if (pathIsOrUnder(path, "/friends")) return "https://www.tiktok.com/friends";
        return HOME;
    }

    private void loadFreshFeedRoute(String raw) {
        if (web == null) return;
        String target = canonicalFeedUrl(raw);
        ownProfileRoute = false;
        externalProfileRoute = false;
        mobileLiveMode = false;
        usingMobileUa = false;
        searchTransient = false;
        commentOpen = false;
        ui.removeCallbacks(commentPresenceCheck);
        commentSeen = false;
        commentMisses = 0;
        firstCardRecoveryScheduled = false;
        feedReturnWakePending = false;
        cancelPendingHistoryReturn();
        invalidateFeedRecoveryGeneration();
        lastMainUrl = target;
        web.getSettings().setUserAgentString(DESKTOP_UA);
        updateShellForUrl(target);
        web.loadUrl(target);
    }

    private void loadInitialHome() {
        if (web == null) return;
        ownProfileRoute = false;
        externalProfileRoute = false;
        mobileLiveMode = false;
        usingMobileUa = false;
        searchTransient = false;
        commentOpen = false;
        ui.removeCallbacks(commentPresenceCheck);
        commentSeen = false;
        commentMisses = 0;
        firstCardRecoveryScheduled = false;
        feedReturnWakePending = false;
        cancelPendingHistoryReturn();
        invalidateFeedRecoveryGeneration();
        lastMainUrl = HOME;
        web.getSettings().setUserAgentString(DESKTOP_UA);
        setTopSelected("foryou");
        setBottomSelected("home");
        web.loadUrl(HOME);
    }

    private void loadFreshForYou() {
        if (web == null) return;
        /* V45.2: when already on For You, do not stop/reload TikTok's player.
           The user-confirmed V44.0.3 home path let TikTok own playback and scrolling. */
        ownProfileRoute = false;
        externalProfileRoute = false;
        mobileLiveMode = false;
        usingMobileUa = false;
        searchTransient = false;
        commentOpen = false;
        ui.removeCallbacks(commentPresenceCheck);
        commentSeen = false;
        commentMisses = 0;
        feedReturnWakePending = false;
        cancelPendingHistoryReturn();
        lastMainUrl = HOME;
        web.getSettings().setUserAgentString(DESKTOP_UA);
        setTopSelected("foryou");
        setBottomSelected("home");
        updateShellForUrl(web.getUrl() == null ? HOME : web.getUrl());
    }

    private void openLiveMobile(String target) {
        String url = target == null || target.isBlank() ? "https://www.tiktok.com/live" : target;
        mobileLiveMode = true;
        openRouteWithIdentity(url, true);
    }

    private void openMobileSecondary(String target) {
        if (target == null || target.isBlank()) return;
        mobileLiveMode = isLiveUrl(target);
        openRouteWithIdentity(target, true);
    }

    private void openRouteWithIdentity(String target, boolean mobile) {
        if (web == null || target == null || target.isBlank()) return;
        boolean wasMobile = usingMobileUa;
        cancelPendingHistoryReturn();
        feedReturnWakePending = wasMobile && !mobile && isFeedUrl(target);
        usingMobileUa = mobile;
        mobileLiveMode = mobile && isLiveUrl(target);
        lastMainUrl = target;
        searchTransient = mobile && isSearchUrl(target);
        commentOpen = false;
        ui.removeCallbacks(commentPresenceCheck);
        commentSeen = false;
        commentMisses = 0;
        if (mobileLiveMode) {
            ownProfileRoute = false;
            externalProfileRoute = false;
        }
        web.stopLoading();
        web.getSettings().setUserAgentString(mobile ? MOBILE_UA : DESKTOP_UA);
        updateShellForUrl(target);
        web.loadUrl(target);
    }

    private void reloadCurrentRouteWithIdentity(String target, boolean mobile) {
        if (web == null || target == null || target.isBlank()) return;
        boolean wasMobile = usingMobileUa;
        cancelPendingHistoryReturn();
        feedReturnWakePending = wasMobile && !mobile && isFeedUrl(target);
        usingMobileUa = mobile;
        mobileLiveMode = mobile && isLiveUrl(target);
        lastMainUrl = target;
        searchTransient = mobile && isSearchUrl(target);
        commentOpen = false;
        ui.removeCallbacks(commentPresenceCheck);
        commentSeen = false;
        commentMisses = 0;
        if (mobileLiveMode) {
            ownProfileRoute = false;
            externalProfileRoute = false;
        }
        /* Same-document TikTok SPA routes have already occupied the correct history
           entry. Change only the browser identity and reload that entry instead of
           loadUrl(target), which can create a duplicate profile/detail history step.
           Android WebView restarts a page when UA changes during loading; if it is
           already settled, reload() explicitly re-requests the current route. */
        boolean wasLoading = web.getProgress() < 100;
        web.getSettings().setUserAgentString(mobile ? MOBILE_UA : DESKTOP_UA);
        updateShellForUrl(target);
        if (!wasLoading) web.reload();
    }

    private void returnToHomeClean() {
        if (web == null) return;
        mobileLiveMode = false;
        usingMobileUa = false;
        externalProfileRoute = false;
        ownProfileRoute = false;
        searchTransient = false;
        commentOpen = false;
        ui.removeCallbacks(commentPresenceCheck);
        commentSeen = false;
        commentMisses = 0;
        firstCardRecoveryScheduled = false;
        feedReturnWakePending = false;
        cancelPendingHistoryReturn();
        invalidateFeedRecoveryGeneration();
        lastMainUrl = HOME;
        web.stopLoading();
        web.clearHistory();
        web.getSettings().setUserAgentString(DESKTOP_UA);
        setTopSelected("foryou");
        setBottomSelected("home");
        showSecondaryBack(false);
        showTop(true);
        showBottom(true);
        web.loadUrl(HOME);
    }

    private void resetLegacyFeedCacheOnce() {
        if (web == null) return;
        try {
            SharedPreferences prefs = getSharedPreferences(MIGRATION_PREFS, MODE_PRIVATE);
            if (!prefs.getBoolean(MIGRATION_V4533_CACHE, false)) {
                /* One-time WebView HTTP/media cache reset after the V54 rebase. Cookies and WebStorage stay intact, so login is preserved. */
                web.clearCache(true);
                getSharedPreferences(FEED_PREFS, MODE_PRIVATE).edit().clear().apply();
                prefs.edit().putBoolean(MIGRATION_V4533_CACHE, true).apply();
            }
        } catch (Throwable ignored) { }
    }

    private void wakeReturnedFeedIfNeeded(String url) {
        if (!feedReturnWakePending || usingMobileUa || !isFeedUrl(url)) return;
        feedReturnWakePending = false;
        /* Returning from Search/profile/LIVE can restore the desktop Feed DOM while
           its visible player is still paused. Wake only the actually visible media;
           never advance the feed and never install a persistent scanner. */
        ui.postDelayed(this::kickVisibleFeedPlayback, 280);
        ui.postDelayed(this::kickVisibleFeedPlayback, 950);
    }

    private void invalidateFeedRecoveryGeneration() {
        feedRecoveryGeneration++;
    }

    private boolean isCurrentFeedRecovery(int generation) {
        return generation == feedRecoveryGeneration;
    }

    private void scheduleFirstCardRecovery() {
        if (web == null || firstCardRecoveryScheduled || !isForYouUrl(lastMainUrl)) return;
        firstCardRecoveryScheduled = true;
        final int generation = ++feedRecoveryGeneration;
        /* Cold-start only: wake the actually visible video a few times while TikTok
           finishes attaching its media source. Each delayed step belongs to one
           recovery generation so a clean reload/renderer rebuild cannot leave stale
           callbacks acting on the replacement page. */
        ui.postDelayed(() -> { if (isCurrentFeedRecovery(generation)) kickVisibleFeedPlayback(); }, 420);
        ui.postDelayed(() -> { if (isCurrentFeedRecovery(generation)) kickVisibleFeedPlayback(); }, 1200);
        ui.postDelayed(() -> { if (isCurrentFeedRecovery(generation)) kickVisibleFeedPlayback(); }, 2600);
        ui.postDelayed(() -> inspectFirstCardStall(false, generation), 5600);
    }

    private void kickVisibleFeedPlayback() {
        if (web == null || !isFeedUrl(lastMainUrl) || usingMobileUa) return;
        String js = "(function(){try{" +
                "var vs=document.querySelectorAll('video');if(!vs||!vs.length)return 'no-video';" +
                "var vw=Math.max(1,innerWidth||1),vh=Math.max(1,innerHeight||1),v=null,best=0;" +
                "for(var i=0;i<vs.length;i++){var x=vs[i],r=x.getBoundingClientRect(),st=getComputedStyle(x),op=parseFloat(st.opacity||'1');" +
                "if(st.display==='none'||st.visibility==='hidden'||(isFinite(op)&&op<=0.05))continue;" +
                "var w=Math.max(0,Math.min(r.right,vw)-Math.max(r.left,0));" +
                "var h=Math.max(0,Math.min(r.bottom,vh)-Math.max(r.top,0));var a=w*h;" +
                "if(a>best){best=a;v=x;}}" +
                "if(!v||best<vw*vh*0.08)return 'no-visible-video';" +
                "v.playsInline=true;v.setAttribute('playsinline','');v.setAttribute('webkit-playsinline','');" +
                "function go(){try{var p=v.play();if(p&&p.catch)p.catch(function(){});}catch(_){}}" +
                "if(v.readyState>=2){go();}" +
                "else if(!v.__tkNativeColdStartArmed){v.__tkNativeColdStartArmed=1;" +
                "v.addEventListener('loadeddata',go,{once:true});v.addEventListener('canplay',go,{once:true});}" +
                "return String(v.readyState||0)+'|'+String(v.currentTime||0)+'|'+String(v.paused)+'|'+String(best|0);" +
                "}catch(e){return 'err';}})()";
        web.evaluateJavascript(js, value -> {
            if (web != null) web.postInvalidateOnAnimation();
        });
    }

    private void inspectFirstCardStall(boolean finalPass, int generation) {
        if (!isCurrentFeedRecovery(generation) || web == null || !isForYouUrl(lastMainUrl)) return;
        String js = "(function(){try{" +
                "function visibleNode(n){if(!n)return false;var st=getComputedStyle(n),op=parseFloat(st.opacity||'1');return st.display!=='none'&&st.visibility!=='hidden'&&(!isFinite(op)||op>0.05);}" +
                "function playable(a){if(!a||!visibleNode(a))return false;var v=a.querySelector('video');if(!v||v.error||!visibleNode(v))return false;" +
                "var r=v.getBoundingClientRect();var area=Math.max(0,Math.min(r.right,innerWidth)-Math.max(r.left,0))*Math.max(0,Math.min(r.bottom,innerHeight)-Math.max(r.top,0));" +
                "return v.readyState>=2&&v.videoWidth>0&&v.videoHeight>0&&area>innerWidth*innerHeight*0.08&&((v.currentTime||0)>0.08||!v.paused);}" +
                "var items=Array.prototype.slice.call(document.querySelectorAll('article[data-e2e=\"recommend-list-item-container\"]'));" +
                "if(!items.length)return 'no-card';var best=-1,bestArea=0;" +
                "for(var i=0;i<items.length;i++){if(!visibleNode(items[i]))continue;var r=items[i].getBoundingClientRect();var w=Math.max(0,Math.min(r.right,innerWidth)-Math.max(r.left,0));var h=Math.max(0,Math.min(r.bottom,innerHeight)-Math.max(r.top,0));var a=w*h;if(a>bestArea){bestArea=a;best=i;}}" +
                "if(best<0)best=0;if(best!==0)return 'not-first-card';var cur=items[best];if(playable(cur))return 'healthy';" +
                "var cv=cur.querySelector('video');if(!cv)return 'healthy-nonvideo-card';" +
                "if(!cv.error){try{var p=cv.play();if(p&&p.catch)p.catch(function(){});}catch(_){}}" +
                "var decoded=cv.readyState>=2&&cv.videoWidth>0&&cv.videoHeight>0;" +
                (!finalPass ? "if(decoded)return 'video-ready-retry';" : "") +
                "var next=items[best+1];if(!next||!visibleNode(next))return 'stalled-no-next';var nv=next.querySelector('video');" +
                "if(!nv||nv.error||!visibleNode(nv)||nv.readyState<2||nv.videoWidth<=0||nv.videoHeight<=0)return 'stalled-next-not-ready';" +
                "next.scrollIntoView({behavior:'auto',block:'start',inline:'nearest'});return 'advanced-stalled-first';" +
                "}catch(e){return 'err';}})()";
        web.evaluateJavascript(js, value -> {
            if (!isCurrentFeedRecovery(generation) || value == null) return;
            if (value.contains("video-ready-retry") && !finalPass) {
                ui.postDelayed(() -> inspectFirstCardStall(true, generation), 900);
                return;
            }
            if (value.contains("advanced-stalled-first")) {
                ui.postDelayed(() -> { if (isCurrentFeedRecovery(generation)) kickVisibleFeedPlayback(); }, 650);
                ui.postDelayed(() -> { if (isCurrentFeedRecovery(generation)) kickVisibleFeedPlayback(); }, 1700);
            }
        });
    }

    private String tikTokPagePath(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            Uri uri = Uri.parse(raw);
            String scheme = lower(uri.getScheme());
            String host = lower(uri.getHost());
            if (!"https".equals(scheme) || !(host.equals("tiktok.com") || host.endsWith(".tiktok.com"))) return null;
            String path = uri.getPath();
            return path == null ? "" : lower(path);
        } catch (Throwable ignored) { return null; }
    }

    private boolean pathIsOrUnder(String path, String base) {
        return path != null && (path.equals(base) || path.equals(base + "/") || path.startsWith(base + "/"));
    }

    private boolean isForYouUrl(String raw) {
        String path = tikTokPagePath(raw);
        return path != null && (path.isEmpty() || "/".equals(path) || pathIsOrUnder(path, "/foryou"));
    }

    private void updateShellForUrl(String raw) {
        if (firstCardRecoveryScheduled && isTrustedTikTokUrl(raw) && !isForYouUrl(raw)) {
            /* The cold-start chain belongs only to the initial /foryou view. Once the
               user navigates elsewhere, invalidate its delayed callbacks without
               re-arming auto-advance when they later return. */
            invalidateFeedRecoveryGeneration();
        }
        String u = lower(raw);
        String path = tikTokPagePath(raw);
        String profilePath = canonicalProfilePath(raw);
        if (profilePath != null) {
            if (ownProfileRoute) {
                if (ownProfilePath == null || ownProfilePath.equals(profilePath)) {
                    /* The first real Profile-tab route teaches us the account path. After
                       that, own-profile identity is path-stable and cannot be overwritten by
                       a scripted/history transition to another creator. */
                    ownProfilePath = profilePath;
                    externalProfileRoute = false;
                } else {
                    ownProfileRoute = false;
                    externalProfileRoute = true;
                }
            } else if (ownProfilePath != null && ownProfilePath.equals(profilePath)) {
                /* Returning through WebView history from another creator must restore the
                   user's own Profile-tab identity instead of leaving a floating Back state. */
                ownProfileRoute = true;
                externalProfileRoute = false;
            } else {
                externalProfileRoute = true;
            }
        }
        boolean auth = isAuthUrl(u);
        boolean discover = isDiscoverUrl(u);
        boolean live = isLiveUrl(u);
        boolean detail = isPostDetailUrl(raw) || isSearchUrl(raw) || isUploadUrl(raw) || live;
        boolean feed = isFeedUrl(u) && !detail && !discover && !auth;
        boolean shop = isShopUrl(u) && !auth;
        boolean inbox = isInboxUrl(u) && !auth;
        boolean ownProfile = ownProfileRoute && isProfileUrl(u) && !detail && !auth;
        boolean externalProfile = !ownProfileRoute && isProfileUrl(u) && !detail && !auth;
        boolean mobileSecondary = usingMobileUa && !feed;
        mobileLiveMode = usingMobileUa && live;

        if (commentOpen && !usingMobileUa) {
            /* Original mobile reference keeps the feed shell visible behind the
               comment sheet. The native shell therefore stays in place while
               the desktop Feed/comment route is open; mobile-secondary comments
               still use TikTok's own page chrome. */
            showTop(true);
            showBottom(true);
        } else if (searchTransient || auth || live || detail) {
            showTop(false);
            showBottom(false);
        } else if (feed) {
            showTop(true);
            showBottom(true);
        } else if (mobileSecondary) {
            showTop(false);
            showBottom(shop || inbox || ownProfile || discover);
        } else {
            showTop(false);
            showBottom(shop || inbox || ownProfile);
        }

        boolean topLevelMobileDestination = shop || inbox || ownProfile || discover;
        boolean nestedMobileSecondary = mobileSecondary && !topLevelMobileDestination;
        if (live) setSecondaryBackMode(true, true);
        else setSecondaryBackMode(!auth && !commentOpen && (detail || externalProfile || nestedMobileSecondary), false);

        if (pathIsOrUnder(path, "/following")) setTopSelected("following");
        else if (pathIsOrUnder(path, "/friends")) setTopSelected("friends");
        else if (pathIsOrUnder(path, "/explore") || pathIsOrUnder(path, "/discover")) setTopSelected("community");
        else setTopSelected("foryou");

        if (shop) setBottomSelected("shop");
        else if (inbox) setBottomSelected("inbox");
        else if (ownProfile) setBottomSelected("profile");
        else if (feed || discover) setBottomSelected("home");

        if (!isProfileUrl(u) && !isPostDetailUrl(u)) ownProfileRoute = false;
        if (feed) externalProfileRoute = false;
        applyWebMode(web, raw);
    }

    private void runNavAction(String action, String fallbackUrl) {
        if (web == null) return;
        if ("live".equals(action)) {
            String jsLive = "(function(){var n=document.querySelector('[data-e2e=\"nav-live\"]');" +
                    "if(!n)return '';var a=(n.matches&&n.matches('a[href]'))?n:(n.querySelector?n.querySelector('a[href]'):null);" +
                    "if(!a)return '';return String(a.href||a.getAttribute('href')||'');})()";
            web.evaluateJavascript(jsLive, value -> {
                String candidate = value == null ? "" : value.replaceAll("^\"|\"$", "").replace("\\/", "/");
                if (candidate.startsWith("https://") && isLiveUrl(candidate)) openLiveMobile(candidate);
                else openLiveMobile(fallbackUrl);
            });
            return;
        }
        if ("search".equals(action) && fallbackUrl != null && !fallbackUrl.isBlank()) {
            searchTransient = true;
            openMobileSecondary(fallbackUrl);
            return;
        }
        if ("shop".equals(action) || "inbox".equals(action) || "community".equals(action)) {
            if (fallbackUrl != null && !fallbackUrl.isBlank()) {
                if ("community".equals(action)) setBottomSelected("home");
                else setBottomSelected(action);
                openMobileSecondary(fallbackUrl);
                return;
            }
        }
        if ("foryou".equals(action) && isForYouUrl(lastMainUrl)) {
            loadFreshForYou();
            return;
        }
        if ("foryou".equals(action)) {
            ownProfileRoute = false;
            setTopSelected("foryou");
            setBottomSelected("home");
            returnToHomeClean();
            return;
        } else if ("following".equals(action)) {
            ownProfileRoute = false;
            setTopSelected("following");
        } else if ("friends".equals(action)) {
            ownProfileRoute = false;
            setTopSelected("friends");
        }

        if ("search".equals(action)) {
            searchTransient = true;
            showTop(false);
            showBottom(false);
        }

        String selector;
        switch (action) {
            case "friends": selector = "a[data-e2e=\"nav-friends\"]"; break;
            case "following": selector = "a[data-e2e=\"nav-following\"]"; break;
            case "profile": selector = "a[data-e2e=\"nav-profile\"]"; break;
            case "search": selector = "button[data-e2e=\"search-button\"],[data-e2e=\"search-icon\"],button[aria-label*=\"Search\"],button[aria-label*=\"搜索\"]"; break;
            default: selector = "";
        }
        String js = "(function(){var e=document.querySelector(" + JSONObject.quote(selector) + ");" +
                "if(!e)return 'miss';e.click();return 'ok';})()";
        web.evaluateJavascript(js, value -> {
            if (!"\"ok\"".equals(value)) {
                if (fallbackUrl != null && !fallbackUrl.isBlank()) {
                    if (shouldUseMobileUa(fallbackUrl)) openMobileSecondary(fallbackUrl);
                    else web.loadUrl(fallbackUrl);
                } else {
                    if ("profile".equals(action)) ownProfileRoute = false;
                    if ("search".equals(action)) {
                        searchTransient = false;
                        updateShellForUrl(lastMainUrl);
                    }
                    Toast.makeText(this, "网页端当前没有这个入口", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private final class ShellBridge {
        @JavascriptInterface
        public void commentOpened() {
            if (!isTrustedTikTokUrl(lastMainUrl)) return;
            /* JavascriptInterface methods run off the UI thread. Publish the intent before
               TikTok's default click navigation can reach shouldOverrideUrlLoading; the UI
               work itself still stays on the main thread. */
            commentOpen = true;
            runOnUiThread(() -> {
                if (!commentOpen) return;
                invalidateFeedRecoveryGeneration();
                commentSeen = false;
                commentMisses = 0;
                updateShellForUrl(lastMainUrl);
                scheduleCommentCheck(450);
            });
        }

        @JavascriptInterface
        public void commentClosed() {
            if (!isTrustedTikTokUrl(lastMainUrl)) return;
            commentOpen = false;
            runOnUiThread(() -> {
                ui.removeCallbacks(commentPresenceCheck);
                commentSeen = false;
                commentMisses = 0;
                updateShellForUrl(lastMainUrl);
            });
        }

        @JavascriptInterface
        public void routeChanged(String url) {
            if (!isTrustedTikTokUrl(url)) return;
            runOnUiThread(() -> {
                lastMainUrl = url;
                if (commentOpen && !usingMobileUa && isPostDetailUrl(url)) {
                    commentSeen = false;
                    commentMisses = 0;
                    updateShellForUrl(url);
                    return;
                }
                boolean wantMobile = shouldUseMobileUa(url);
                if (wantMobile != usingMobileUa) {
                    reloadCurrentRouteWithIdentity(url, wantMobile);
                    return;
                }
                mobileLiveMode = usingMobileUa && isLiveUrl(url);
                if (!isSearchUrl(url)) searchTransient = false;
                updateShellForUrl(url);
            });
        }

        @JavascriptInterface
        public void searchOpened() {
            if (!isTrustedTikTokUrl(lastMainUrl)) return;
            runOnUiThread(() -> {
                searchTransient = true;
                invalidateFeedRecoveryGeneration();
                updateShellForUrl(lastMainUrl);
            });
        }

        @JavascriptInterface
        public void searchClosed() {
            if (!isTrustedTikTokUrl(lastMainUrl)) return;
            runOnUiThread(() -> {
                searchTransient = false;
                updateShellForUrl(lastMainUrl);
            });
        }

        @JavascriptInterface
        public boolean recentFeedPostRepeated(String postId) {
            if (!isTrustedTikTokUrl(lastMainUrl) || !isForYouUrl(lastMainUrl)) return false;
            return checkAndRememberRecentFeedPost(postId);
        }

        @JavascriptInterface
        public void externalProfileOpened() {
            if (!isTrustedTikTokUrl(lastMainUrl)) return;
            /* Publish profile ownership before the anchor's default navigation can race
               shouldOverrideUrlLoading/updateShellForUrl and overwrite ownProfilePath. */
            ownProfileRoute = false;
            externalProfileRoute = true;
            runOnUiThread(() -> {
                showBottom(false);
                showSecondaryBack(true);
            });
        }
    }

    private boolean checkAndRememberRecentFeedPost(String rawPostId) {
        String postId = rawPostId == null ? "" : rawPostId.trim();
        if (!postId.matches("[0-9]{8,24}")) return false;
        long now = System.currentTimeMillis();
        synchronized (recentFeedSeenLock) {
            ensureRecentFeedSeenLoadedLocked(now);
            Long previous = recentFeedSeen.remove(postId);
            long age = previous == null ? Long.MAX_VALUE : Math.max(0L, now - previous);
            boolean repeated = previous != null &&
                    age >= RECENT_FEED_SAME_SESSION_GRACE_MS && age <= RECENT_FEED_WINDOW_MS;
            recentFeedSeen.put(postId, now);
            pruneRecentFeedSeenLocked(now);
            recentFeedSeenDirty++;
            if (repeated || recentFeedSeenDirty >= 4) persistRecentFeedSeenLocked();
            return repeated;
        }
    }

    private void ensureRecentFeedSeenLoadedLocked(long now) {
        if (recentFeedSeenLoaded) return;
        recentFeedSeenLoaded = true;
        String raw = getSharedPreferences(RECENT_FEED_PREFS, MODE_PRIVATE).getString(RECENT_FEED_KEY, "");
        if (raw == null || raw.isBlank()) return;
        String[] rows = raw.split("\n");
        for (String row : rows) {
            int split = row.indexOf(',');
            if (split <= 0 || split >= row.length() - 1) continue;
            String id = row.substring(0, split).trim();
            if (!id.matches("[0-9]{8,24}")) continue;
            try {
                long when = Long.parseLong(row.substring(split + 1).trim());
                if (when > 0L && now - when >= 0L && now - when <= RECENT_FEED_WINDOW_MS) {
                    recentFeedSeen.remove(id);
                    recentFeedSeen.put(id, when);
                }
            } catch (Throwable ignored) { }
        }
        pruneRecentFeedSeenLocked(now);
    }

    private void pruneRecentFeedSeenLocked(long now) {
        ArrayList<String> remove = new ArrayList<>();
        for (Map.Entry<String, Long> entry : recentFeedSeen.entrySet()) {
            Long when = entry.getValue();
            if (when == null || now - when < 0L || now - when > RECENT_FEED_WINDOW_MS) remove.add(entry.getKey());
        }
        for (String id : remove) recentFeedSeen.remove(id);
        while (recentFeedSeen.size() > RECENT_FEED_MAX_IDS) {
            String first = recentFeedSeen.keySet().iterator().next();
            recentFeedSeen.remove(first);
        }
    }

    private void persistRecentFeedSeenLocked() {
        if (!recentFeedSeenLoaded) return;
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Long> entry : recentFeedSeen.entrySet()) {
            if (out.length() > 0) out.append('\n');
            out.append(entry.getKey()).append(',').append(entry.getValue());
        }
        getSharedPreferences(RECENT_FEED_PREFS, MODE_PRIVATE).edit()
                .putString(RECENT_FEED_KEY, out.toString()).apply();
        recentFeedSeenDirty = 0;
    }

    private void flushRecentFeedSeen() {
        synchronized (recentFeedSeenLock) {
            if (recentFeedSeenDirty > 0) persistRecentFeedSeenLocked();
        }
    }

    private void scheduleCommentCheck(long delayMs) {
        ui.removeCallbacks(commentPresenceCheck);
        if (commentOpen) ui.postDelayed(commentPresenceCheck, Math.max(0L, delayMs));
    }

    private void checkCommentPresence() {
        if (!commentOpen || web == null) return;
        String js = "(function(){var n=document.querySelectorAll('[data-e2e=\"search-comment-container\"],[data-e2e=\"comment-list\"]');" +
                "for(var i=0;i<n.length;i++){try{var c=n[i],r=c.getBoundingClientRect(),s=getComputedStyle(c),o=parseFloat(s.opacity||'1');" +
                "if(r.width>40&&r.height>40&&r.bottom>0&&r.top<innerHeight&&s.display!=='none'&&s.visibility!=='hidden'&&s.pointerEvents!=='none'&&(!isFinite(o)||o>0.05))return true;}catch(e){}}" +
                "return false;})()";
        web.evaluateJavascript(js, value -> {
            if (!commentOpen) return;
            boolean present = "true".equals(value);
            if (present) {
                commentSeen = true;
                commentMisses = 0;
            } else if (commentSeen) {
                commentMisses++;
                if (commentMisses >= 2) {
                    commentOpen = false;
                    commentSeen = false;
                    updateShellForUrl(lastMainUrl);
                    return;
                }
            } else {
                commentMisses++;
                /* TikTok can push a Feed comment into the current post's detail URL before
                   the comment DOM is mounted. Preserve that explicit comment intent while
                   the desktop-detail route is loading; Back still has a deterministic exit. */
                boolean detailIntent = !usingMobileUa && isPostDetailUrl(lastMainUrl);
                int missLimit = detailIntent ? COMMENT_DETAIL_MISS_LIMIT : COMMENT_OPEN_MISS_LIMIT;
                if (commentMisses >= missLimit) {
                    /* A detail-route transition can legitimately mount the real sheet late,
                       but a failed/login-blocked comment must not leave a permanent 450ms poll
                       and hidden native shell. The detail route gets a much longer grace window. */
                    commentOpen = false;
                    commentSeen = false;
                    commentMisses = 0;
                    updateShellForUrl(lastMainUrl);
                    return;
                }
            }
            scheduleCommentCheck(450);
        });
    }

    @Override
    public void onBackPressed() {
        /* Close the visible overlay before dismissing its underlying route. This matters
           when comments are opened from Search or after TikTok pushes the current post
           to a /video or /photo URL. */
        if (commentOpen && web != null) {
            String current = lower(lastMainUrl);
            boolean commentDetailRoute = isPostDetailUrl(current);
            if (commentDetailRoute && web.canGoBack()) {
                commentOpen = false;
                ui.removeCallbacks(commentPresenceCheck);
                commentSeen = false;
                commentMisses = 0;
                String expected = previousHistoryUrl();
                web.goBack();
                scheduleHistoryReturnReconcile(expected);
                return;
            }

            String js = "(function(){var n=document.querySelectorAll('[data-e2e=\"search-comment-container\"],[data-e2e=\"comment-list\"]'),c=null;" +
                    "for(var i=0;i<n.length;i++){try{var r=n[i].getBoundingClientRect(),s=getComputedStyle(n[i]);if(r.width>40&&r.height>40&&r.bottom>0&&r.top<innerHeight&&s.display!=='none'&&s.visibility!=='hidden'){c=n[i];break;}}catch(e){}}" +
                    "var p=c&&c.parentElement,pp=p&&p.parentElement,q='[data-e2e=\"browse-close\"],button[aria-label=\"exit\"],button[aria-label=\"Close\"],button[aria-label=\"关闭\"]';" +
                    "var e=(p&&p.querySelector(q))||(pp&&pp.querySelector(q));if(e){e.click();return 'ok';}return 'miss';})()";
            web.evaluateJavascript(js, value -> {
                if ("\"ok\"".equals(value)) {
                    commentOpen = false;
                    ui.removeCallbacks(commentPresenceCheck);
                    commentSeen = false;
                    commentMisses = 0;
                    updateShellForUrl(lastMainUrl);
                } else {
                    scheduleCommentCheck(120);
                }
            });
            return;
        }
        if (mobileLiveMode || isLiveUrl(lastMainUrl)) {
            exitLiveToPreviousNonLiveOrHome();
            return;
        }
        if (searchTransient) {
            searchTransient = false;
            goBackRestoringIdentityOrHome();
            return;
        }
        if (usingMobileUa) {
            goBackRestoringIdentityOrHome();
            return;
        }
        if (externalProfileRoute) {
            returnToHomeClean();
            return;
        }
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private String previousHistoryUrl() {
        if (web == null) return null;
        try {
            WebBackForwardList history = web.copyBackForwardList();
            int currentIndex = history == null ? -1 : history.getCurrentIndex();
            if (currentIndex <= 0) return null;
            WebHistoryItem item = history.getItemAtIndex(currentIndex - 1);
            return item == null ? null : item.getUrl();
        } catch (Throwable ignored) { return null; }
    }

    private void goBackRestoringIdentityOrHome() {
        if (web == null || !web.canGoBack()) {
            returnToHomeClean();
            return;
        }
        String expected = previousHistoryUrl();
        web.goBack();
        scheduleHistoryReturnReconcile(expected);
    }

    private void exitLiveToPreviousNonLiveOrHome() {
        if (web == null) {
            returnToHomeClean();
            return;
        }
        try {
            WebBackForwardList history = web.copyBackForwardList();
            int currentIndex = history == null ? -1 : history.getCurrentIndex();
            int targetIndex = -1;
            String targetUrl = null;
            for (int i = currentIndex - 1; i >= 0; i--) {
                WebHistoryItem item = history.getItemAtIndex(i);
                String candidate = item == null ? null : item.getUrl();
                if (isTrustedTikTokUrl(candidate) && !isLiveUrl(candidate)) {
                    targetIndex = i;
                    targetUrl = candidate;
                    break;
                }
            }
            if (targetIndex >= 0) {
                web.goBackOrForward(targetIndex - currentIndex);
                scheduleHistoryReturnReconcile(targetUrl);
                return;
            }
        } catch (Throwable ignored) { }
        returnToHomeClean();
    }

    private void cancelPendingHistoryReturn() {
        pendingHistoryTarget = null;
        historyReturnGeneration++;
    }

    private void scheduleHistoryReturnReconcile(String expected) {
        if (!isTrustedTikTokUrl(expected)) {
            cancelPendingHistoryReturn();
            return;
        }
        pendingHistoryTarget = expected;
        final int generation = ++historyReturnGeneration;
        ui.postDelayed(() -> reconcileHistoryDestination(generation, 0), 180);
    }

    private String searchHistoryKey(String raw) {
        try {
            Uri uri = Uri.parse(raw);
            String q = uri.getQueryParameter("q");
            if (q == null) q = uri.getQueryParameter("keyword");
            return q == null ? "" : q;
        } catch (Throwable ignored) { return ""; }
    }

    private boolean sameTikTokRoute(String a, String b) {
        if (a == null || b == null) return false;
        String ap = tikTokPagePath(a), bp = tikTokPagePath(b);
        if (ap == null || !ap.equals(bp)) return false;
        /* /search?q=A and /search?q=B occupy distinct history states even though the
           pathname is identical. Do not mistake the still-visible departure query for
           a completed Back navigation. Other TikTok routes are path-addressed here. */
        if (pathIsOrUnder(ap, "/search")) return searchHistoryKey(a).equals(searchHistoryKey(b));
        return true;
    }

    private void reconcileHistoryDestination(int generation, int attempt) {
        if (generation != historyReturnGeneration || web == null) return;
        String current = web.getUrl();
        String expected = pendingHistoryTarget;
        if (expected == null) return;

        /* WebView history restoration is asynchronous and can take far longer than the
           old fixed 260 ms delay. A transient blank URL or the still-visible departure
           route is not evidence that history failed, so never hard-reset Home here. */
        if (isTrustedTikTokUrl(current) && sameTikTokRoute(current, expected)) {
            cancelPendingHistoryReturn();
            reconcileHistoryDestinationNow(current);
            return;
        }

        if (attempt < 3) {
            long delay = attempt == 0 ? 320L : (attempt == 1 ? 650L : 1100L);
            ui.postDelayed(() -> reconcileHistoryDestination(generation, attempt + 1), delay);
        } else {
            cancelPendingHistoryReturn();
        }
        /* After the bounded fallback window, WebViewClient callbacks remain authoritative.
           Do not replace a slow legitimate history restore with /foryou. */
    }

    private void reconcileHistoryDestinationNow(String current) {
        if (current == null || current.isBlank() || !isTrustedTikTokUrl(current)) return;
        lastMainUrl = current;
        if (!isSearchUrl(current)) searchTransient = false;
        boolean wantMobile = shouldUseMobileUa(current);
        if (wantMobile != usingMobileUa) {
            reloadCurrentRouteWithIdentity(current, wantMobile);
            return;
        }
        mobileLiveMode = usingMobileUa && isLiveUrl(current);
        updateShellForUrl(current);
        wakeReturnedFeedIfNeeded(current);
    }

    private boolean shouldUseMobileUa(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String u = lower(raw);
        if (!isTrustedTikTokUrl(raw)) return false;
        if (isFeedUrl(u) && !isDiscoverUrl(u) && !isLiveUrl(u) && !isAuthUrl(u)) return false;
        return true;
    }

    private boolean isFeedUrl(String raw) {
        String path = tikTokPagePath(raw);
        if (path == null) return false;
        return path.isEmpty() || "/".equals(path) || pathIsOrUnder(path, "/foryou") ||
                pathIsOrUnder(path, "/following") || pathIsOrUnder(path, "/friends");
    }

    private boolean isDiscoverUrl(String raw) {
        String path = tikTokPagePath(raw);
        return pathIsOrUnder(path, "/explore") || pathIsOrUnder(path, "/discover");
    }

    private boolean isSearchUrl(String raw) {
        return pathIsOrUnder(tikTokPagePath(raw), "/search");
    }

    private boolean isUploadUrl(String raw) {
        return pathIsOrUnder(tikTokPagePath(raw), "/upload");
    }

    private boolean isPostDetailUrl(String raw) {
        String path = tikTokPagePath(raw);
        return path != null && (path.contains("/video/") || path.contains("/photo/"));
    }

    private boolean isLiveUrl(String u) {
        if (u == null || u.isBlank()) return false;
        try {
            Uri uri = Uri.parse(u);
            String host = lower(uri.getHost());
            String path = uri.getPath() == null ? "" : lower(uri.getPath());
            if (!(host.equals("tiktok.com") || host.endsWith(".tiktok.com"))) return false;
            if (path.equals("/live") || path.equals("/live/")) return true;
            if (path.matches("^/@[^/]+/live(?:/[0-9]+)?/?$")) return true;
            return path.matches("^/live/[0-9]+/?$");
        } catch (Throwable ignored) {
            String s = lower(u);
            return s.equals("https://www.tiktok.com/live") || s.equals("https://www.tiktok.com/live/");
        }
    }

    private boolean isShopUrl(String raw) { return pathIsOrUnder(tikTokPagePath(raw), "/shop"); }
    private boolean isInboxUrl(String raw) {
        String path = tikTokPagePath(raw);
        return pathIsOrUnder(path, "/messages") || pathIsOrUnder(path, "/inbox");
    }
    private String canonicalProfilePath(String raw) {
        String path = tikTokPagePath(raw);
        if (path == null || !path.matches("^/@[^/]+/?$")) return null;
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
    private boolean isProfileUrl(String raw) {
        return canonicalProfilePath(raw) != null;
    }
    private boolean isAuthUrl(String raw) {
        String s = lower(raw);
        String path = tikTokPagePath(raw);
        return pathIsOrUnder(path, "/login") || pathIsOrUnder(path, "/signup") ||
                s.contains("captcha") || s.contains("verify") || s.contains("challenge");
    }

    private void showTop(boolean show) { if (topOverlay != null) topOverlay.setVisibility(show ? View.VISIBLE : View.GONE); }
    private void showBottom(boolean show) { if (bottomOverlay != null) bottomOverlay.setVisibility(show ? View.VISIBLE : View.GONE); }
    private void showSecondaryBack(boolean show) { setSecondaryBackMode(show, false); }

    private void setSecondaryBackMode(boolean show, boolean liveClose) {
        if (secondaryBack == null) return;
        secondaryBack.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        secondaryBack.setImageResource(liveClose ? com.tk.nativeclient.R.drawable.ic_close : com.tk.nativeclient.R.drawable.ic_back);
        GradientDrawable modeBg = new GradientDrawable();
        modeBg.setShape(GradientDrawable.OVAL);
        modeBg.setColor(liveClose ? 0x26000000 : 0x88000000);
        secondaryBack.setBackground(modeBg);
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) secondaryBack.getLayoutParams();
        p.gravity = Gravity.TOP | (liveClose ? Gravity.RIGHT : Gravity.LEFT);
        p.topMargin = insetTop + dp(10);
        if (liveClose) { p.rightMargin = dp(12); p.leftMargin = 0; }
        else { p.leftMargin = dp(12); p.rightMargin = 0; }
        secondaryBack.setLayoutParams(p);
        secondaryBack.setOnClickListener(v -> onBackPressed());
    }

    private boolean isTrustedTikTokUrl(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            Uri uri = Uri.parse(raw);
            String scheme = lower(uri.getScheme());
            String host = lower(uri.getHost());
            return "https".equals(scheme) && (host.equals("tiktok.com") || host.endsWith(".tiktok.com") || host.equals("tiktokv.com") || host.endsWith(".tiktokv.com"));
        } catch (Throwable ignored) { return false; }
    }

    private boolean isTrustedTikTokOrigin(String raw) { return isTrustedTikTokUrl(raw); }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "";
        }
    }

    private void handleWebPermission(PermissionRequest request) {
        if (!isTrustedTikTokOrigin(request.getOrigin() == null ? null : request.getOrigin().toString())) {
            request.deny();
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && !missing.contains(Manifest.permission.CAMERA)) missing.add(Manifest.permission.CAMERA);
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED && !missing.contains(Manifest.permission.RECORD_AUDIO)) missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (missing.isEmpty()) grantTrustedResources(request);
        else {
            if (pendingWebPermission != null) pendingWebPermission.deny();
            pendingWebPermission = request;
            requestPermissions(missing.toArray(new String[0]), REQ_WEB_PERMISSION);
        }
    }

    private void grantTrustedResources(PermissionRequest request) {
        List<String> grant = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) grant.add(r);
            else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) grant.add(r);
        }
        if (grant.isEmpty()) request.deny(); else request.grant(grant.toArray(new String[0]));
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private DownloadListener buildDownloadListener() {
        return (url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url == null || (!url.startsWith("https://") && !url.startsWith("http://"))) return;
            try {
                String name = URLUtil.guessFileName(url, contentDisposition, mimetype);
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setTitle(name);
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                if (mimetype != null && !mimetype.isBlank()) req.setMimeType(mimetype);
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null && !cookie.isBlank()) req.addRequestHeader("Cookie", cookie);
                String requestUa = userAgent;
                if ((requestUa == null || requestUa.isBlank()) && web != null) requestUa = web.getSettings().getUserAgentString();
                if (requestUa == null || requestUa.isBlank()) requestUa = usingMobileUa ? MOBILE_UA : DESKTOP_UA;
                req.addRequestHeader("User-Agent", requestUa);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(req);
                    Toast.makeText(this, "开始下载", Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable e) {
                Toast.makeText(this, "无法开始下载", Toast.LENGTH_SHORT).show();
            }
        };
    }

    private boolean handleIntentUrl(String raw) {
        try {
            Intent intent = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME);
            String fallback = intent.getStringExtra("browser_fallback_url");
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
                return true;
            }
            if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) {
                if (isTrustedTikTokUrl(fallback)) openRouteWithIdentity(fallback, shouldUseMobileUa(fallback));
                else web.loadUrl(fallback);
                return true;
            }
        } catch (Throwable ignored) { }
        return true;
    }

    private void rebuildWebView(String restoreUrl) {
        runOnUiThread(() -> {
            try {
                if (web != null) {
                    root.removeView(web);
                    web.stopLoading();
                    web.removeJavascriptInterface("NativeShell");
                    web.setWebChromeClient(null);
                    web.setWebViewClient(null);
                    web.destroy();
                }
            } catch (Throwable ignored) { }
            web = createWebView();
            root.addView(web, 0, fullParams());
            rebuildingRenderer = false;
            String target = restoreUrl == null || restoreUrl.isBlank() ? HOME : restoreUrl;
            usingMobileUa = shouldUseMobileUa(target);
            mobileLiveMode = usingMobileUa && isLiveUrl(target);
            searchTransient = usingMobileUa && isSearchUrl(target);
            commentOpen = false;
            ui.removeCallbacks(commentPresenceCheck);
            commentSeen = false;
            commentMisses = 0;
            feedReturnWakePending = !usingMobileUa && isFeedUrl(target) && !isForYouUrl(target);
            cancelPendingHistoryReturn();
            invalidateFeedRecoveryGeneration();
            if (isForYouUrl(target)) firstCardRecoveryScheduled = false;
            web.getSettings().setUserAgentString(usingMobileUa ? MOBILE_UA : DESKTOP_UA);
            web.loadUrl(target);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) { web.onResume(); web.resumeTimers(); }
        if (!usingMobileUa && isFeedUrl(lastMainUrl)) ui.postDelayed(this::kickVisibleFeedPlayback, 320);
    }

    @Override
    protected void onPause() {
        if (web != null) { web.onPause(); web.pauseTimers(); }
        CookieManager.getInstance().flush();
        flushRecentFeedSeen();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) {
            Bundle state = new Bundle();
            web.saveState(state);
            outState.putBundle("web_state", state);
        }
        outState.putString("last_url", lastMainUrl);
        outState.putBoolean("own_profile", ownProfileRoute);
        outState.putString("own_profile_path", ownProfilePath);
        outState.putBoolean("search_transient", searchTransient);
    }

    @Override
    protected void onDestroy() {
        cancelPendingHistoryReturn();
        flushRecentFeedSeen();
        ui.removeCallbacksAndMessages(null);
        if (fileChooser != null) fileChooser.onReceiveValue(null);
        fileChooser = null;
        if (pendingWebPermission != null) pendingWebPermission.deny();
        pendingWebPermission = null;
        if (pendingGeoCallback != null && pendingGeoOrigin != null) pendingGeoCallback.invoke(pendingGeoOrigin, false, false);
        pendingGeoCallback = null;
        pendingGeoOrigin = null;
        if (web != null) {
            root.removeView(web);
            web.stopLoading();
            web.removeJavascriptInterface("NativeShell");
            web.setWebChromeClient(null);
            web.setWebViewClient(null);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || fileChooser == null) return;
        Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        fileChooser.onReceiveValue(result);
        fileChooser = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WEB_PERMISSION) {
            PermissionRequest req = pendingWebPermission;
            pendingWebPermission = null;
            if (req != null) grantTrustedResources(req);
            return;
        }
        if (requestCode == REQ_GEO) {
            GeolocationPermissions.Callback cb = pendingGeoCallback;
            String origin = pendingGeoOrigin;
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
            if (cb != null && origin != null) cb.invoke(origin, hasLocationPermission(), true);
        }
    }

    private FrameLayout.LayoutParams fullParams() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private FrameLayout.LayoutParams overlayParams(int gravity, int heightDp) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp));
        p.gravity = gravity;
        return p;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }

    private static final class TabRef {
        final LinearLayout box;
        final TextView text;
        final View line;
        final String key;
        TabRef(LinearLayout box, TextView text, View line, String key) {
            this.box = box; this.text = text; this.line = line; this.key = key;
        }
    }

    private static final class BottomRef {
        final LinearLayout box;
        final ImageView icon;
        final TextView text;
        final String key;
        BottomRef(LinearLayout box, ImageView icon, TextView text, String key) {
            this.box = box; this.icon = icon; this.text = text; this.key = key;
        }
    }
}
