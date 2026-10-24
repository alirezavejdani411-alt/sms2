package com.seveneast.smscontroller;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 7East ESP32 + SIM800 SMS controller.
 * Names and settings are stored only on this phone. Live status changes only after an SMS response.
 */
public class MainActivity extends Activity {
    private static final int MAX_COILS = 40;
    private static final int MAX_REGS = 20;
    private static final int REQUEST_SMS_PERMISSIONS = 710;
    private static final long RESPONSE_TIMEOUT_MS = 90_000L;
    private static final String ACTION_INCOMING = SmsReceiver.ACTION_INCOMING;
    private static final String ACTION_SENT_RESULT = SmsSentReceiver.ACTION_SENT_RESULT;

    private static final int BG = Color.rgb(11, 17, 32);
    private static final int CARD = Color.rgb(24, 35, 56);
    private static final int CARD_DARK = Color.rgb(15, 25, 43);
    private static final int BORDER = Color.rgb(47, 65, 91);
    private static final int TEXT = Color.rgb(235, 242, 252);
    private static final int MUTED = Color.rgb(157, 174, 197);
    private static final int BLUE = Color.rgb(14, 165, 233);
    private static final int GREEN = Color.rgb(16, 185, 129);
    private static final int RED = Color.rgb(239, 68, 68);
    private static final int AMBER = Color.rgb(245, 158, 11);
    private static final int PURPLE = Color.rgb(139, 92, 246);

    private static final int TAB_RELAYS = 0;
    private static final int TAB_REGS = 1;
    private static final int TAB_ADMIN = 2;
    private static final int TAB_SETTINGS = 3;
    private static final int TAB_ABOUT = 4;

    // Command tracker phases
    private static final int PH_SENDING = 0;
    private static final int PH_WAITING = 1;
    private static final int PH_OK = 2;
    private static final int PH_ERR = 3;
    private static final int PH_TIMEOUT = 4;

    private SharedPreferences prefs;
    private SharedPreferences smsStore;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // -1 = unknown; 0 = OFF; 1 = ON; 2 = PULSE
    private final int[] coilState = new int[MAX_COILS];
    private final int[] regValue = new int[MAX_REGS];
    private final boolean[] regKnown = new boolean[MAX_REGS];
    private final String[] coilNames = new String[MAX_COILS];
    private final String[] regNames = new String[MAX_REGS];

    private int currentTab = TAB_RELAYS;
    private int lastBuiltTab = -1;
    private String stateMessage = "برای دریافت وضعیت، دکمهٔ رفرش را بزنید.";
    private String lastReply = "هنوز پاسخی دریافت نشده است.";
    private long lastReplyAt = 0L;

    // Transaction state
    private boolean waiting = false;
    private String expectedType = "";
    private int expectedIndex = -1;
    private String pendingCommand = "";
    private String pendingAdminPhone = "";
    private long activeToken = 0L;
    private long tokenCounter = System.currentTimeMillis();

    // Command tracker (status sheet) state
    private int cmdPhase = PH_OK;
    private String cmdTitle = "";
    private String cmdCode = "";
    private String cmdNote = "";
    private String cmdResult = "";
    private String cmdReply = "";
    private boolean cmdSmsSent = false;
    private boolean cmdGotReply = false;
    private long cmdPhaseStart = 0L;

    // Frame views
    private LinearLayout root;
    private TextView statusChip;
    private TextView stateView;
    private Button refreshBtn;
    private ScrollView scroll;
    private LinearLayout contentHost;
    private LinearLayout navBar;
    private EditText deviceNumberInput;
    private BroadcastReceiver internalReceiver;

    // Tracker sheet views
    private Dialog sheet;
    private TextView shTitle;
    private TextView shCode;
    private TextView shDetail;
    private TextView shReply;
    private TextView shTimer;
    private ProgressBar shBar;
    private Button shClose;
    private Button shAction;
    private final TextView[] shIcon = new TextView[3];
    private final TextView[] shLabel = new TextView[3];

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateSheet();
            if (waiting) handler.postDelayed(this, 1000L);
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(Color.rgb(7, 12, 24));
        prefs = getSharedPreferences("local_settings", MODE_PRIVATE);
        smsStore = getSharedPreferences(SmsReceiver.STORE, MODE_PRIVATE);
        Arrays.fill(coilState, -1);
        loadNames();
        lastReply = prefs.getString("last_reply", lastReply);
        lastReplyAt = prefs.getLong("last_reply_at", 0L);
        if (normalizePhone(deviceNumber()).length() < 10) {
            currentTab = TAB_SETTINGS;
            stateMessage = "ابتدا شمارهٔ سیم‌کارت کنترلر را در تنظیمات وارد کنید.";
        }
        buildFrame();
        render(true);
        ensureSmsPermissions();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_INCOMING);
        filter.addAction(ACTION_SENT_RESULT);
        internalReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                if (ACTION_INCOMING.equals(intent.getAction())) {
                    handleIncomingOnce(intent.getStringExtra("sender"), intent.getStringExtra("body"), intent.getLongExtra("id", 0L));
                } else if (ACTION_SENT_RESULT.equals(intent.getAction())) {
                    long token = intent.getLongExtra("token", -1L);
                    boolean success = intent.getBooleanExtra("success", false);
                    if (waiting && token == activeToken) {
                        if (success) {
                            cmdSmsSent = true;
                            if (cmdPhase == PH_SENDING) cmdPhase = PH_WAITING;
                            updateSheet();
                        } else {
                            endTransaction(PH_ERR, "ارسال پیامک توسط اندروید تأیید نشد؛ شبکه، اعتبار یا سیم‌کارت را بررسی کنید.");
                        }
                    }
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(internalReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(internalReceiver, filter);
        processStoredSmsIfNew();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render(true);
    }

    @Override
    protected void onStop() {
        if (internalReceiver != null) {
            try { unregisterReceiver(internalReceiver); } catch (IllegalArgumentException ignored) { }
            internalReceiver = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(ticker);
        if (sheet != null && sheet.isShowing()) {
            try { sheet.dismiss(); } catch (RuntimeException ignored) { }
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- permissions & settings values

    private void ensureSmsPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        boolean send = checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
        boolean receive = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
        if (!send || !receive) {
            requestPermissions(new String[]{Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS}, REQUEST_SMS_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_SMS_PERMISSIONS) {
            boolean ok = grantResults.length > 0;
            for (int result : grantResults) if (result != PackageManager.PERMISSION_GRANTED) ok = false;
            stateMessage = ok ? "مجوز پیامک فعال شد." : "برای ارسال و دریافت پیامک، مجوزهای SMS را فعال کنید (تنظیمات ← وضعیت مجوز).";
            render(true);
        }
    }

    private boolean hasSmsPermissions() {
        return Build.VERSION.SDK_INT < 23 ||
                (checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
                 checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED);
    }

    private void loadNames() {
        for (int i = 0; i < MAX_COILS; i++) coilNames[i] = prefs.getString("coil_name_" + i, "رله " + (i + 1));
        for (int i = 0; i < MAX_REGS; i++) regNames[i] = prefs.getString("reg_name_" + i, "رجیستر " + (i + 1));
    }

    private String deviceNumber() {
        return prefs.getString("device_number", "").trim();
    }

    private int activeCoils() {
        int n = prefs.getInt("active_coils", MAX_COILS);
        return Math.max(1, Math.min(MAX_COILS, n));
    }

    private void setActiveCoils(int n) {
        n = Math.max(1, Math.min(MAX_COILS, n));
        prefs.edit().putInt("active_coils", n).apply();
        render(true);
    }

    private boolean regsEnabled() {
        return prefs.getBoolean("regs_enabled", true);
    }

    private boolean pulseEnabled() {
        return prefs.getBoolean("pulse_enabled", true);
    }

    // ---------------------------------------------------------------- frame (app bar, content, bottom navigation)

    private void buildFrame() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(10), dp(14), dp(8));
        bar.addView(new BoltView(this, true), new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("7East", 20, TEXT, true));
        titles.addView(text("کنترل پیامکی · ESP32 + SIM800", 11, MUTED, false));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tp.setMargins(dp(10), 0, dp(10), 0);
        bar.addView(titles, tp);

        statusChip = text("", 12, GREEN, true);
        statusChip.setPadding(dp(10), dp(6), dp(10), dp(6));
        statusChip.setOnClickListener(v -> { if (waiting) showSheet(); });
        bar.addView(statusChip);

        refreshBtn = button("↻", BLUE, Color.WHITE);
        refreshBtn.setTextSize(18);
        refreshBtn.setOnClickListener(v -> refreshAll());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(44), dp(40));
        rp.setMargins(dp(8), 0, 0, 0);
        bar.addView(refreshBtn, rp);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        stateView = text("", 12, MUTED, false);
        stateView.setPadding(dp(16), 0, dp(16), dp(8));
        root.addView(stateView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        contentHost = new LinearLayout(this);
        contentHost.setOrientation(LinearLayout.VERTICAL);
        contentHost.setPadding(dp(14), dp(4), dp(14), dp(16));
        scroll.addView(contentHost, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setBackgroundColor(CARD_DARK);
        navBar.setPadding(dp(6), dp(5), dp(6), dp(5));
        root.addView(navBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Android 15+ draws edge-to-edge: keep the content clear of system bars and the keyboard.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                l = bars.left; t = bars.top; r = bars.right; b = Math.max(bars.bottom, ime.bottom);
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return insets;
        });
        setContentView(root);
    }

    private void render(boolean force) {
        if (contentHost == null) return;
        if (currentTab == TAB_REGS && !regsEnabled()) currentTab = TAB_RELAYS;
        updateChrome();
        View focus = getCurrentFocus();
        boolean typing = !force && currentTab == TAB_SETTINGS && focus instanceof EditText;
        if (!typing) rebuildContent();
    }

    private void updateChrome() {
        statusChip.setText(waiting ? "⏳ در حال اجرا" : "● آماده");
        statusChip.setTextColor(waiting ? AMBER : GREEN);
        statusChip.setBackground(round(waiting ? Color.rgb(68, 48, 22) : Color.rgb(17, 58, 54), 16, waiting ? AMBER : GREEN));
        setEnabledLook(refreshBtn, !waiting);
        stateView.setText(stateMessage);

        navBar.removeAllViews();
        addNavItem(TAB_RELAYS, "🎛", "رله‌ها");
        if (regsEnabled()) addNavItem(TAB_REGS, "📊", "رجیسترها");
        addNavItem(TAB_ADMIN, "🛡", "مدیریت");
        addNavItem(TAB_SETTINGS, "⚙", "تنظیمات");
        addNavItem(TAB_ABOUT, "ℹ", "درباره");
    }

    private void addNavItem(int tab, String icon, String label) {
        boolean selected = currentTab == tab;
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(2), dp(6), dp(2), dp(6));
        item.setBackground(round(selected ? Color.rgb(16, 42, 70) : CARD_DARK, 12, selected ? BLUE : CARD_DARK));
        TextView i = text(icon, 17, selected ? BLUE : MUTED, false);
        i.setGravity(Gravity.CENTER);
        TextView l = text(label, 10, selected ? BLUE : MUTED, selected);
        l.setGravity(Gravity.CENTER);
        item.addView(i);
        item.addView(l);
        item.setOnClickListener(v -> {
            if (currentTab != tab) { currentTab = tab; render(true); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(dp(2), 0, dp(2), 0);
        navBar.addView(item, lp);
    }

    private void rebuildContent() {
        int keepY = (lastBuiltTab == currentTab) ? scroll.getScrollY() : 0;
        lastBuiltTab = currentTab;
        contentHost.removeAllViews();
        switch (currentTab) {
            case TAB_RELAYS: renderRelays(); break;
            case TAB_REGS: renderRegisters(); break;
            case TAB_ADMIN: renderAdmin(); break;
            case TAB_SETTINGS: renderSettings(); break;
            default: renderAbout(); break;
        }
        final int y = keepY;
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    // ---------------------------------------------------------------- relays

    private void renderRelays() {
        int n = activeCoils();
        addSectionTitle("رله‌ها", n + " کانال فعال");

        boolean anyKnown = false;
        for (int i = 0; i < n; i++) if (coilState[i] != -1) anyKnown = true;
        if (!anyKnown) {
            LinearLayout info = card();
            info.addView(text("وضعیت رله‌ها هنوز دریافت نشده است. برای ایمنی، تا قبل از دریافت وضعیت، دکمهٔ هر رله فقط رفرش می‌کند و رله را تغییر نمی‌دهد.", 12, MUTED, false));
            Button b = button("↻ دریافت وضعیت رله‌ها", BLUE, Color.WHITE);
            setEnabledLook(b, !waiting);
            b.setOnClickListener(v -> refreshAll());
            info.addView(b, lp(44, 0, 10, 0, 0));
            contentHost.addView(info, lpMatchWrap(0, 0, 0, 10));
        }

        for (int start = 0; start < n; start += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            row.addView(relayCard(start), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
            if (start + 1 < n) {
                row.addView(relayCard(start + 1), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            } else {
                row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            }
            contentHost.addView(row, lpMatchWrap(0, 0, 0, 8));
        }
        addLastReplyCard();
    }

    private View relayCard(final int index) {
        LinearLayout c = card();
        int state = coilState[index];
        int stateColor = state == 1 ? GREEN : (state == 0 ? MUTED : AMBER);
        int tileColor = state == 1 ? Color.rgb(17, 58, 54) : (state == 0 ? CARD : (state == 2 ? Color.rgb(68, 48, 22) : CARD_DARK));
        c.setBackground(round(tileColor, 15, state == 1 ? GREEN : (state == 2 ? AMBER : BORDER)));
        c.addView(text("RELAY " + (index + 1), 10, MUTED, true));

        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(coilNames[index], 14, TEXT, true);
        name.setMaxLines(2);
        nameRow.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button rename = button("✎", CARD_DARK, MUTED);
        rename.setTextSize(12);
        nameRow.addView(rename, new LinearLayout.LayoutParams(dp(36), dp(34)));
        rename.setOnClickListener(v -> editNameDialog(true, index));
        c.addView(nameRow, lpMatchWrap(0, 5, 0, 6));

        String label = state == 1 ? "● روشن" : state == 0 ? "○ خاموش" : state == 2 ? "⚡ پالس" : "? نامشخص";
        TextView status = text(label, 13, stateColor, true);
        status.setPadding(0, dp(4), 0, dp(9));
        c.addView(status);

        Button main;
        if (state == -1) main = button("↻ رفرش", CARD_DARK, TEXT);
        else if (state == 1) main = button("خاموش کن", RED, Color.WHITE);
        else if (state == 2) main = button("اجرا", AMBER, Color.WHITE);
        else main = button("روشن کن", GREEN, Color.WHITE);
        setEnabledLook(main, !waiting);
        main.setOnClickListener(v -> {
            if (waiting) return;
            if (coilState[index] == -1) refreshAll();
            else sendCommand(String.valueOf(index + 1), "RELAY", index, "رلهٔ " + (index + 1) + " · " + coilNames[index]);
        });
        c.addView(main, lp(42, 0, 0, 0, 0));

        if (pulseEnabled()) {
            Button pulse = button("⚡ لحظه‌ای", CARD_DARK, AMBER);
            setEnabledLook(pulse, !waiting);
            pulse.setOnClickListener(v -> {
                if (waiting) return;
                sendCommand("PULSE " + (index + 1), "PULSE_CMD", index, "لحظه‌ای · " + coilNames[index]);
            });
            c.addView(pulse, lp(40, 0, 7, 0, 0));
        }
        c.setOnLongClickListener(v -> { editNameDialog(true, index); return true; });
        return c;
    }

    // ---------------------------------------------------------------- registers

    private void renderRegisters() {
        addSectionTitle("رجیسترها", MAX_REGS + " مقدار");
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = button("↻ دریافت VARS", BLUE, Color.WHITE);
        setEnabledLook(refresh, !waiting);
        refresh.setOnClickListener(v -> sendCommand("VARS", "VARS", -1, "دریافت مقدار رجیسترها"));
        Button all = button("↻ رفرش همه", CARD_DARK, TEXT);
        setEnabledLook(all, !waiting);
        all.setOnClickListener(v -> refreshAll());
        actions.addView(refresh, new LinearLayout.LayoutParams(0, dp(45), 1));
        actions.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        actions.addView(all, new LinearLayout.LayoutParams(0, dp(45), 1));
        contentHost.addView(actions, lpMatchWrap(0, 0, 0, 10));

        for (int start = 0; start < MAX_REGS; start += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(registerCard(start), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
            if (start + 1 < MAX_REGS) row.addView(registerCard(start + 1), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            else row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            contentHost.addView(row, lpMatchWrap(0, 0, 0, 8));
        }
        addLastReplyCard();
    }

    private View registerCard(final int index) {
        LinearLayout c = card();
        c.addView(text("REGISTER " + (index + 1), 10, MUTED, true));
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(regNames[index], 13, TEXT, true);
        title.setMaxLines(2);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button rename = button("✎", CARD_DARK, MUTED);
        rename.setTextSize(12);
        titleRow.addView(rename, new LinearLayout.LayoutParams(dp(34), dp(32)));
        rename.setOnClickListener(v -> editNameDialog(false, index));
        c.addView(titleRow, lpMatchWrap(0, 5, 0, 6));
        TextView val = text(regKnown[index] ? String.valueOf(regValue[index]) : "—", 21, regKnown[index] ? BLUE : AMBER, true);
        val.setPadding(0, dp(2), 0, dp(8));
        c.addView(val);
        Button edit = button("✎ نوشتن مقدار", PURPLE, Color.WHITE);
        setEnabledLook(edit, !waiting);
        edit.setOnClickListener(v -> editRegisterValue(index));
        c.addView(edit, lp(42, 0, 0, 0, 0));
        return c;
    }

    // ---------------------------------------------------------------- admin (users / admins only)

    private void renderAdmin() {
        addSectionTitle("کاربران و مدیران", "فقط توسط مدیر");
        LinearLayout caution = card();
        caution.addView(text("این فرمان‌ها فقط وقتی اجرا می‌شوند که شمارهٔ همین گوشی در کنترلر به‌عنوان مدیر (ADMIN) ثبت شده باشد. شمارهٔ موبایل فرد را وارد می‌کنید؛ برنامه خودش فرمان را می‌فرستد، منتظر درخواست شمارهٔ کنترلر می‌ماند و شماره را در قالب *شماره* ارسال می‌کند.", 12, MUTED, false));
        contentHost.addView(caution, lpMatchWrap(0, 0, 0, 10));

        addAdminButton("➕  افزودن کاربر", "ADUS", "افزودن کاربر", GREEN);
        addAdminButton("➖  حذف کاربر", "DELUS", "حذف کاربر", RED);
        addAdminButton("➕  افزودن مدیر", "ADAD", "افزودن مدیر", GREEN);
        addAdminButton("➖  حذف مدیر", "DELAD", "حذف مدیر", RED);
        addLastReplyCard();
    }

    private void addAdminButton(String label, final String command, final String title, int color) {
        Button b = button(label, color, Color.WHITE);
        b.setTextSize(14);
        setEnabledLook(b, !waiting);
        b.setOnClickListener(v -> showAdminDialog(command, title));
        contentHost.addView(b, lp(50, 0, 0, 0, 9));
    }

    private void showAdminDialog(final String command, final String title) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        input.setHint("مثلاً 0912xxxxxxx");
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setBackground(round(CARD_DARK, 10, BORDER));
        input.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(4), dp(20), dp(4));
        wrap.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("شمارهٔ موبایل را وارد کنید.")
                .setView(wrap)
                .setNegativeButton("انصراف", null)
                .setPositiveButton("ارسال", (dialog, which) -> {
                    String phone = normalizePhone(input.getText().toString());
                    if (phone.length() < 10 || phone.length() > 15) {
                        toast("شماره را کامل و صحیح وارد کنید.");
                        return;
                    }
                    pendingAdminPhone = phone;
                    sendCommand(command, "ADMIN", -1, title);
                }).show();
    }

    // ---------------------------------------------------------------- settings

    private void renderSettings() {
        addSectionTitle("تنظیمات", "فقط روی همین گوشی ذخیره می‌شود");

        // Controller SIM number
        LinearLayout simCard = card();
        simCard.addView(text("شمارهٔ سیم‌کارت داخل کنترلر", 14, TEXT, true));
        simCard.addView(text("فرمان‌ها به این شماره پیامک می‌شوند و فقط پاسخ‌های همین شماره پذیرفته می‌شوند. باید شمارهٔ سیم‌کارت SIM800 باشد، نه شمارهٔ این گوشی.", 11, MUTED, false));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        deviceNumberInput = new EditText(this);
        deviceNumberInput.setSingleLine(true);
        deviceNumberInput.setTextSize(15);
        deviceNumberInput.setTextColor(TEXT);
        deviceNumberInput.setHintTextColor(MUTED);
        deviceNumberInput.setHint("مثلاً 0912xxxxxxx");
        deviceNumberInput.setInputType(InputType.TYPE_CLASS_PHONE);
        deviceNumberInput.setText(deviceNumber());
        deviceNumberInput.setPadding(dp(10), dp(6), dp(10), dp(6));
        deviceNumberInput.setBackground(round(CARD_DARK, 10, BORDER));
        row.addView(deviceNumberInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button save = button("ذخیره", GREEN, Color.WHITE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(80), dp(48));
        sp.setMargins(dp(7), 0, 0, 0);
        row.addView(save, sp);
        save.setOnClickListener(v -> {
            String raw = deviceNumberInput.getText().toString().trim();
            if (normalizePhone(raw).length() < 10) {
                toast("شمارهٔ سیم‌کارت کنترلر را کامل وارد کنید.");
                return;
            }
            prefs.edit().putString("device_number", raw).apply();
            hideKeyboard();
            stateMessage = "شمارهٔ کنترلر ذخیره شد.";
            toast("شماره ذخیره شد.");
            render(true);
        });
        simCard.addView(row, lpMatchWrap(0, 8, 0, 0));
        contentHost.addView(simCard, lpMatchWrap(0, 0, 0, 10));

        // Active relay count
        int n = activeCoils();
        LinearLayout relayCard = card();
        relayCard.addView(text("تعداد رله‌های فعال", 14, TEXT, true));
        relayCard.addView(text("فقط همین تعداد رله در صفحهٔ رله‌ها نشان داده می‌شود (از ۱ تا ۴۰). رله‌های بالاتر پنهان می‌شوند.", 11, MUTED, false));
        LinearLayout stepper = new LinearLayout(this);
        stepper.setOrientation(LinearLayout.HORIZONTAL);
        stepper.setGravity(Gravity.CENTER_VERTICAL);
        Button minus = button("−", CARD_DARK, TEXT);
        minus.setTextSize(20);
        minus.setOnClickListener(v -> setActiveCoils(activeCoils() - 1));
        TextView value = text(n + " از " + MAX_COILS, 17, BLUE, true);
        value.setGravity(Gravity.CENTER);
        Button plus = button("+", CARD_DARK, TEXT);
        plus.setTextSize(20);
        plus.setOnClickListener(v -> setActiveCoils(activeCoils() + 1));
        stepper.addView(minus, new LinearLayout.LayoutParams(dp(52), dp(46)));
        stepper.addView(value, new LinearLayout.LayoutParams(0, dp(46), 1));
        stepper.addView(plus, new LinearLayout.LayoutParams(dp(52), dp(46)));
        relayCard.addView(stepper, lpMatchWrap(0, 8, 0, 6));
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        int[] presets = {4, 8, 10, 16, 24, 40};
        for (final int p : presets) {
            Button chip = button(String.valueOf(p), p == n ? BLUE : CARD_DARK, p == n ? Color.WHITE : MUTED);
            chip.setTextSize(12);
            chip.setOnClickListener(v -> setActiveCoils(p));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(38), 1);
            cp.setMargins(dp(2), 0, dp(2), 0);
            chips.addView(chip, cp);
        }
        relayCard.addView(chips, lpMatchWrap(0, 0, 0, 0));
        contentHost.addView(relayCard, lpMatchWrap(0, 0, 0, 10));

        // Switches
        LinearLayout optCard = card();
        optCard.addView(text("نمایش بخش‌ها", 14, TEXT, true));
        optCard.addView(makeSwitch("تب رجیسترها", "اگر از رجیستر استفاده نمی‌کنید خاموشش کنید. دیگر VARS هم ارسال نمی‌شود.", regsEnabled(), on -> {
            prefs.edit().putBoolean("regs_enabled", on).apply();
            handler.post(() -> render(true));
        }), lpMatchWrap(0, 6, 0, 0));
        optCard.addView(makeSwitch("دکمهٔ «لحظه‌ای» روی هر رله", "با خاموش کردن، فقط دکمهٔ روشن/خاموش باقی می‌ماند.", pulseEnabled(), on -> {
            prefs.edit().putBoolean("pulse_enabled", on).apply();
            handler.post(() -> render(true));
        }), lpMatchWrap(0, 10, 0, 0));
        contentHost.addView(optCard, lpMatchWrap(0, 0, 0, 10));

        // SMS permission status
        LinearLayout permCard = card();
        boolean ok = hasSmsPermissions();
        permCard.addView(text("وضعیت مجوز پیامک", 14, TEXT, true));
        TextView permState = text(ok ? "✓ مجوز ارسال و دریافت پیامک فعال است." : "✕ مجوز پیامک فعال نیست؛ برنامه نمی‌تواند فرمان بفرستد.", 12, ok ? GREEN : RED, true);
        permState.setPadding(0, dp(6), 0, dp(4));
        permCard.addView(permState);
        if (!ok) {
            permCard.addView(text("اگر اندروید اجازه نمی‌دهد: وارد «اطلاعات برنامه» شوید، از منوی ⋮ بالا گزینهٔ «Allow restricted settings» را بزنید و بعد مجوز SMS را بدهید.", 11, MUTED, false));
            Button ask = button("درخواست مجوز", BLUE, Color.WHITE);
            ask.setOnClickListener(v -> ensureSmsPermissions());
            permCard.addView(ask, lp(42, 0, 8, 0, 0));
        }
        Button openSettings = button("باز کردن اطلاعات برنامه", CARD_DARK, TEXT);
        openSettings.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null));
                startActivity(i);
            } catch (RuntimeException ex) {
                toast("باز کردن تنظیمات ممکن نشد.");
            }
        });
        permCard.addView(openSettings, lp(42, 0, 8, 0, 0));
        contentHost.addView(permCard, lpMatchWrap(0, 0, 0, 10));
    }

    private interface OnToggle { void changed(boolean on); }

    private View makeSwitch(String title, String detail, boolean checked, final OnToggle listener) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        Switch sw = new Switch(this);
        sw.setText(title);
        sw.setTextColor(TEXT);
        sw.setTextSize(13);
        sw.setChecked(checked);
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states, new int[]{BLUE, MUTED}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{Color.argb(120, 14, 165, 233), Color.argb(90, 157, 174, 197)}));
        sw.setOnCheckedChangeListener((b, on) -> listener.changed(on));
        box.addView(sw, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        box.addView(text(detail, 11, MUTED, false));
        return box;
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && root != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
            View f = getCurrentFocus();
            if (f != null) f.clearFocus();
        } catch (RuntimeException ignored) { }
    }

    // ---------------------------------------------------------------- about

    private void renderAbout() {
        LinearLayout head = card();
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        BoltView logo = new BoltView(this, true);
        head.addView(logo, new LinearLayout.LayoutParams(dp(84), dp(84)));
        TextView name = text("7East SMS Controller", 20, TEXT, true);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(10), 0, 0);
        head.addView(name);
        TextView ver = text("نسخهٔ " + versionName() + " · کنترل پیامکی ESP32 + SIM800", 12, MUTED, false);
        ver.setGravity(Gravity.CENTER);
        head.addView(ver);
        contentHost.addView(head, lpMatchWrap(0, 4, 0, 10));

        addAbout("این برنامه چیست؟",
                "یک ریموت پیامکی برای کنترلر ESP32 + SIM800 است. برنامه به اینترنت یا بلوتوث نیاز ندارد؛ تمام ارتباط فقط با پیامک متنی بین این گوشی و سیم‌کارتِ داخل کنترلر انجام می‌شود. بنابراین تا وقتی آنتن موبایل دارید، از هر جایی کار می‌کند.");

        addAbout("طرز کار در یک نگاه",
                "۱) روی یک دکمه می‌زنید.\n"
              + "۲) برنامه یک پیامک کوتاه (مثلاً 5 یا STATUS) به شمارهٔ سیم‌کارت کنترلر می‌فرستد.\n"
              + "۳) فریمور ESP32 پیامک را می‌خواند، مجاز بودن شمارهٔ شما را بررسی می‌کند، فرمان را روی رله‌ها (Coil) اجرا می‌کند و با یک پیامک پاسخ می‌دهد؛ مثلاً Coil 5 = ON.\n"
              + "۴) برنامه پاسخ را می‌خواند، وضعیت را روی صفحه به‌روز می‌کند و در پنجرهٔ پیگیری نتیجه را نشان می‌دهد.\n\n"
              + "پاسخ‌ها حتی وقتی صفحهٔ برنامه باز نیست دریافت و ذخیره می‌شوند و با باز کردن برنامه اعمال می‌شوند.");

        addAbout("راه‌اندازی اولیه",
                "۱) در اولین اجرا مجوز ارسال و دریافت پیامک را بدهید (اگر اندروید اجازه نداد، بخش «تنظیمات» راهنما دارد).\n"
              + "۲) به تب «تنظیمات» بروید و شمارهٔ سیم‌کارت داخل کنترلر را وارد و ذخیره کنید.\n"
              + "۳) شمارهٔ همین گوشی باید در فریمور ثبت شده باشد؛ برای افزودن/حذف کاربر باید سطح ADMIN داشته باشد.\n"
              + "۴) در تب «رله‌ها» دکمهٔ «دریافت وضعیت رله‌ها» (یا ↻ بالای صفحه) را بزنید تا وضعیت واقعی بیاید.");

        addAbout("رله‌ها: روشن/خاموش و لحظه‌ای",
                "هر رله دو دکمه دارد:\n"
              + "• «روشن کن / خاموش کن»: شمارهٔ رله را می‌فرستد. در فریمور فعلی این فرمان وضعیت را Toggle (برعکس) می‌کند؛ به همین دلیل برنامه فقط وقتی اجازهٔ تغییر می‌دهد که وضعیت فعلی رله را از پیامک دریافت کرده باشد. اگر وضعیت «نامشخص» باشد، اولین لمس فقط رفرش می‌کند.\n"
              + "• «⚡ لحظه‌ای»: فرمان PULSE N را می‌فرستد و رله را برای مدتی کوتاه فعال می‌کند (مناسب درب‌بازکن، زنگ و مشابه آن). این فرمان فقط برای کانال‌هایی پذیرفته می‌شود که در فریمور Latch نباشند.\n\n"
              + "اسم هر رله را با دکمهٔ ✎ یا نگه‌داشتن کارت می‌توانید عوض کنید؛ اسم‌ها فقط روی همین گوشی ذخیره می‌شوند و به کنترلر فرستاده نمی‌شوند.");

        addAbout("پنجرهٔ پیگیری فرمان",
                "بعد از هر فرمان یک پنجرهٔ پیگیری باز می‌شود که سه مرحله را نشان می‌دهد:\n"
              + "۱) ارسال پیامک: برنامه پیامک را به اندروید می‌دهد و اندروید تأیید می‌کند که به شبکه رفته است.\n"
              + "۲) پاسخ کنترلر: منتظر پیامک برگشتی از کنترلر می‌مانیم (حداکثر ۹۰ ثانیه، با شمارندهٔ زمان).\n"
              + "۳) نتیجه: «فرمان تأیید شد» (سبز)، «فرمان انجام نشد» (قرمز) یا «پاسخی دریافت نشد» (نارنجی).\n\n"
              + "متن پاسخ کنترلر هم همان‌جا نمایش داده می‌شود. می‌توانید پنجره را ببندید؛ فرمان در پس‌زمینه ادامه دارد و با لمس نشان «در حال اجرا» بالای صفحه دوباره باز می‌شود. تا پایان هر فرمان، فرمان بعدی ارسال نمی‌شود.");

        addAbout("رجیسترها",
                "رجیسترها مقادیر عددی کنترلر هستند (۱ تا ۲۰، مقدار بین 0 و 65535). با «دریافت VARS» مقدار همه خوانده می‌شود و با «نوشتن مقدار» فرمان SET N VALUE فرستاده می‌شود. اگر به رجیستر نیازی ندارید، از تنظیمات تبش را خاموش کنید.");

        addAbout("کاربران و مدیران",
                "در تب «مدیریت» چهار کار هست: افزودن کاربر (ADUS)، حذف کاربر (DELUS)، افزودن مدیر (ADAD) و حذف مدیر (DELAD). شمارهٔ موبایل را وارد می‌کنید؛ برنامه فرمان را می‌فرستد، وقتی کنترلر شماره را خواست، آن را در قالب *شماره* خودکار می‌فرستد و نتیجه را در پنجرهٔ پیگیری نشان می‌دهد. این کارها فقط با شمارهٔ مدیر ممکن است.");

        addAbout("نکات ایمنی و محدودیت‌ها",
                "• اگر پاسخی نرسد، برنامه فرمان را خودکار دوباره نمی‌فرستد؛ چون ممکن است فرمان اول اجرا شده باشد و تکرارش رله را برعکس کند. اول وضعیت را رفرش کنید.\n"
              + "• وضعیت رله‌ها فقط از پیامک‌های کنترلر به‌روز می‌شود. اگر رله‌ای از راه دیگری تغییر کند، تا پیامک یا رفرش بعدی دیده نمی‌شود.\n"
              + "• هر فرمان یک پیامک است و هزینه و تأخیر اپراتور دارد؛ پاسخ ممکن است چند ثانیه تا یک دقیقه طول بکشد.\n"
              + "• برای بارهای حساس، قبل از اتکا به وضعیت، یک بار رفرش کنید.");

        addAboutMono("فرمان‌های پیامکی",
                "N          (1..40)  Toggle رله\n"
              + "PULSE N             پالس رله\n"
              + "STATUS              وضعیت رله‌ها\n"
              + "VARS                مقدار رجیسترها\n"
              + "SET N VALUE         نوشتن رجیستر\n"
              + "ADUS / DELUS        افزودن/حذف کاربر\n"
              + "ADAD / DELAD        افزودن/حذف مدیر\n"
              + "*شماره*             تکمیل فرمان بالا");
    }

    private void addAbout(String title, String body) {
        LinearLayout c = card();
        c.addView(text(title, 15, BLUE, true));
        TextView t = text(body, 13, TEXT, false);
        t.setLineSpacing(dp(3), 1.0f);
        t.setPadding(0, dp(6), 0, 0);
        c.addView(t);
        contentHost.addView(c, lpMatchWrap(0, 0, 0, 10));
    }

    private void addAboutMono(String title, String body) {
        LinearLayout c = card();
        c.addView(text(title, 15, BLUE, true));
        TextView t = text(body, 12, TEXT, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextDirection(View.TEXT_DIRECTION_LTR);
        t.setGravity(Gravity.START);
        t.setPadding(0, dp(6), 0, 0);
        c.addView(t);
        contentHost.addView(c, lpMatchWrap(0, 0, 0, 10));
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ex) {
            return "1.1";
        }
    }

    private void addLastReplyCard() {
        LinearLayout responseCard = card();
        responseCard.addView(text("آخرین پیام برگشتی از کنترلر", 14, TEXT, true));
        TextView response = text(lastReply, 13, TEXT, false);
        response.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_LTR);
        response.setGravity(Gravity.START);
        response.setPadding(0, dp(9), 0, 0);
        responseCard.addView(response);
        if (lastReplyAt > 0) {
            TextView at = text("دریافت: " + new SimpleDateFormat("HH:mm:ss  yyyy/MM/dd", Locale.getDefault()).format(new Date(lastReplyAt)), 11, MUTED, false);
            at.setPadding(0, dp(6), 0, 0);
            responseCard.addView(at);
        }
        contentHost.addView(responseCard, lpMatchWrap(0, 4, 0, 8));
    }

    // ---------------------------------------------------------------- dialogs

    private void refreshAll() {
        if (waiting) return;
        Arrays.fill(coilState, -1);
        for (int i = 0; i < MAX_REGS; i++) regKnown[i] = false;
        sendCommand("STATUS", "STATUS", -1, regsEnabled() ? "دریافت وضعیت رله‌ها و رجیسترها" : "دریافت وضعیت رله‌ها");
    }

    private void editNameDialog(boolean coil, int index) {
        EditText input = new EditText(this);
        input.setText(coil ? coilNames[index] : regNames[index]);
        input.setSingleLine(true);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setPadding(dp(12), dp(7), dp(12), dp(7));
        input.setBackground(round(CARD_DARK, 10, BORDER));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(4), dp(20), dp(4));
        wrap.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        new AlertDialog.Builder(this)
                .setTitle(coil ? "نام رلهٔ " + (index + 1) : "نام رجیستر " + (index + 1))
                .setView(wrap)
                .setNegativeButton("انصراف", null)
                .setPositiveButton("ذخیره", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) value = coil ? "رله " + (index + 1) : "رجیستر " + (index + 1);
                    if (coil) {
                        coilNames[index] = value;
                        prefs.edit().putString("coil_name_" + index, value).apply();
                    } else {
                        regNames[index] = value;
                        prefs.edit().putString("reg_name_" + index, value).apply();
                    }
                    render(true);
                }).show();
    }

    private void editRegisterValue(int index) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(regKnown[index] ? String.valueOf(regValue[index]) : "0");
        input.setTextColor(TEXT);
        input.setPadding(dp(12), dp(6), dp(12), dp(6));
        input.setBackground(round(CARD_DARK, 10, BORDER));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(4), dp(20), dp(4));
        wrap.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        new AlertDialog.Builder(this)
                .setTitle("ثبت مقدار برای " + regNames[index])
                .setMessage("مقدار عدد صحیح بین 0 و 65535")
                .setView(wrap)
                .setNegativeButton("انصراف", null)
                .setPositiveButton("ارسال SET", (dialog, which) -> {
                    String raw = input.getText().toString().trim();
                    int value;
                    try { value = Integer.parseInt(raw); } catch (NumberFormatException ex) { value = -1; }
                    if (value < 0 || value > 65535) {
                        toast("مقدار رجیستر باید بین 0 و 65535 باشد.");
                        return;
                    }
                    sendCommand("SET " + (index + 1) + " " + value, "REGWRITE", index,
                            "نوشتن «" + regNames[index] + "» = " + value);
                }).show();
    }

    // ---------------------------------------------------------------- command tracker (status sheet)

    private void showSheet() {
        if (sheet != null && sheet.isShowing()) { updateSheet(); return; }
        sheet = new Dialog(this);
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        box.setPadding(dp(20), dp(10), dp(20), dp(22));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        float r = dp(24);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setStroke(dp(1), BORDER);
        box.setBackground(bg);

        View handle = new View(this);
        handle.setBackground(round(BORDER, 3, BORDER));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(44), dp(5));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.setMargins(0, 0, 0, dp(14));
        box.addView(handle, hp);

        shTitle = text("", 19, AMBER, true);
        box.addView(shTitle);
        shCode = text("", 13, MUTED, false);
        shCode.setPadding(0, dp(3), 0, dp(10));
        box.addView(shCode);

        shBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        shBar.setIndeterminate(true);
        shBar.setIndeterminateTintList(ColorStateList.valueOf(BLUE));
        box.addView(shBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        String[] labels = {"ارسال پیامک به کنترلر", "پاسخ کنترلر", "نتیجه"};
        for (int i = 0; i < 3; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            shIcon[i] = text("○", 18, MUTED, true);
            shIcon[i].setGravity(Gravity.CENTER);
            row.addView(shIcon[i], new LinearLayout.LayoutParams(dp(34), dp(34)));
            shLabel[i] = text(labels[i], 14, MUTED, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(dp(8), 0, 0, 0);
            row.addView(shLabel[i], lp);
            box.addView(row, i == 0 ? lp(40, 0, 12, 0, 0) : lp(40, 0, 0, 0, 0));
        }

        shDetail = text("", 13, TEXT, false);
        shDetail.setPadding(0, dp(10), 0, 0);
        box.addView(shDetail);

        shReply = text("", 12, TEXT, false);
        shReply.setTypeface(Typeface.MONOSPACE);
        shReply.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_LTR);
        shReply.setGravity(Gravity.START);
        shReply.setPadding(dp(10), dp(8), dp(10), dp(8));
        shReply.setBackground(round(CARD_DARK, 10, BORDER));
        box.addView(shReply, lpMatchWrap(0, 10, 0, 0));

        shTimer = text("", 12, MUTED, false);
        shTimer.setPadding(0, dp(8), 0, 0);
        box.addView(shTimer);

        shAction = button("↻ رفرش وضعیت", BLUE, Color.WHITE);
        shAction.setOnClickListener(v -> {
            sheet.dismiss();
            refreshAll();
        });
        box.addView(shAction, lp(44, 0, 12, 0, 0));

        shClose = button("بستن", CARD_DARK, TEXT);
        shClose.setOnClickListener(v -> sheet.dismiss());
        box.addView(shClose, lp(44, 0, 8, 0, 0));

        sheet.setContentView(box);
        Window w = sheet.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
        }
        updateSheet();
        sheet.show();
        handler.removeCallbacks(ticker);
        if (waiting) handler.postDelayed(ticker, 1000L);
    }

    private void updateSheet() {
        if (sheet == null || !sheet.isShowing() || shTitle == null) return;
        boolean running = cmdPhase == PH_SENDING || cmdPhase == PH_WAITING;
        String head;
        int color;
        switch (cmdPhase) {
            case PH_SENDING: head = "در حال ارسال فرمان…"; color = AMBER; break;
            case PH_WAITING: head = "منتظر پاسخ کنترلر…"; color = AMBER; break;
            case PH_OK: head = "فرمان تأیید شد ✓"; color = GREEN; break;
            case PH_ERR: head = "فرمان انجام نشد ✕"; color = RED; break;
            default: head = "پاسخی دریافت نشد"; color = AMBER; break;
        }
        shTitle.setText(head);
        shTitle.setTextColor(color);
        shCode.setText(cmdTitle + (cmdCode.isEmpty() ? "" : "   ·   فرمان: " + cmdCode));
        shBar.setVisibility(running ? View.VISIBLE : View.INVISIBLE);

        // 0 = pending, 1 = active, 2 = done, 3 = error
        int s1, s2, s3;
        switch (cmdPhase) {
            case PH_SENDING: s1 = 1; s2 = 0; s3 = 0; break;
            case PH_WAITING: s1 = 2; s2 = 1; s3 = 0; break;
            case PH_OK: s1 = 2; s2 = 2; s3 = 2; break;
            case PH_ERR:
                s1 = cmdSmsSent ? 2 : 3;
                s2 = cmdGotReply ? 2 : 0;
                s3 = 3;
                break;
            default: s1 = cmdSmsSent ? 2 : 3; s2 = 3; s3 = 0; break;
        }
        applyStep(0, s1);
        applyStep(1, s2);
        applyStep(2, s3);

        String detail;
        if (cmdPhase == PH_SENDING) detail = "پیامک «" + cmdCode + "» در حال تحویل به شبکه است.";
        else if (cmdPhase == PH_WAITING) detail = "پیامک ارسال شد. پاسخ کنترلر معمولاً چند ثانیه تا یک دقیقه طول می‌کشد.";
        else detail = cmdResult;
        if (running && !cmdNote.isEmpty()) detail = cmdNote + "\n" + detail;
        shDetail.setText(detail);
        shDetail.setTextColor(cmdPhase == PH_ERR ? RED : TEXT);

        if (cmdReply.isEmpty()) {
            shReply.setVisibility(View.GONE);
        } else {
            shReply.setVisibility(View.VISIBLE);
            shReply.setText(cmdReply);
        }
        if (running) {
            long sec = Math.max(0L, (System.currentTimeMillis() - cmdPhaseStart) / 1000L);
            shTimer.setText("⏱ " + sec + " ثانیه از " + (RESPONSE_TIMEOUT_MS / 1000L));
            shTimer.setVisibility(View.VISIBLE);
        } else {
            shTimer.setVisibility(View.GONE);
        }
        shAction.setVisibility(cmdPhase == PH_TIMEOUT ? View.VISIBLE : View.GONE);
        shClose.setText(running ? "بستن (فرمان در پس‌زمینه ادامه دارد)" : "تأیید");
    }

    private void applyStep(int i, int state) {
        String icon;
        int color;
        switch (state) {
            case 1: icon = "◌"; color = AMBER; break;
            case 2: icon = "✓"; color = GREEN; break;
            case 3: icon = "✕"; color = RED; break;
            default: icon = "○"; color = MUTED; break;
        }
        shIcon[i].setText(icon);
        shIcon[i].setTextColor(color);
        shLabel[i].setTextColor(state == 0 ? MUTED : TEXT);
        shLabel[i].setTypeface(Typeface.DEFAULT, state == 1 ? Typeface.BOLD : Typeface.NORMAL);
    }

    // ---------------------------------------------------------------- sending and transactions

    private void sendCommand(String command, String expected, int index, String title) {
        if (waiting) return;
        if (!"ADMIN".equals(expected)) pendingAdminPhone = "";
        if (!hasSmsPermissions()) {
            pendingAdminPhone = "";
            ensureSmsPermissions();
            stateMessage = "ابتدا اجازهٔ ارسال و دریافت پیامک را فعال کنید.";
            render(true);
            return;
        }
        String dest = normalizePhone(deviceNumber());
        if (dest.length() < 10) {
            pendingAdminPhone = "";
            stateMessage = "شمارهٔ سیم‌کارت داخل کنترلر وارد نشده است.";
            toast("ابتدا در تنظیمات شمارهٔ سیم‌کارت کنترلر را وارد و ذخیره کنید.");
            currentTab = TAB_SETTINGS;
            render(true);
            return;
        }
        waiting = true;
        expectedType = expected;
        expectedIndex = index;
        pendingCommand = command;
        activeToken = nextToken();
        final long token = activeToken;

        cmdTitle = title;
        cmdCode = command;
        cmdNote = "";
        cmdResult = "";
        cmdReply = "";
        cmdSmsSent = false;
        cmdGotReply = false;
        cmdPhase = PH_SENDING;
        cmdPhaseStart = System.currentTimeMillis();
        stateMessage = "فرمان «" + command + "» ارسال شد؛ منتظر پیام برگشتی هستیم.";
        armTimeout(token);
        render(true);
        showSheet();
        transmitSms(dest, command, token);
    }

    private void transmitSms(String dest, String command, long token) {
        try {
            SmsManager manager = Build.VERSION.SDK_INT >= 31
                    ? getSystemService(SmsManager.class) : SmsManager.getDefault();
            Intent sent = new Intent(this, SmsSentReceiver.class);
            sent.setAction(SmsSentReceiver.ACTION_SENT);
            sent.putExtra("token", token);
            int req = (int) (token & 0x7fffffff);
            PendingIntent sentPI = PendingIntent.getBroadcast(this, req, sent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            manager.sendTextMessage(dest, null, command, sentPI, null);
        } catch (Exception ex) {
            endTransaction(PH_ERR, "ارسال پیامک شروع نشد: " + safeMessage(ex));
        }
    }

    private void armTimeout(long token) {
        handler.postDelayed(() -> {
            if (!waiting || activeToken != token) return;
            String oldType = expectedType;
            int oldIndex = expectedIndex;
            if ("RELAY".equals(oldType) && oldIndex >= 0 && oldIndex < MAX_COILS) coilState[oldIndex] = -1;
            if ("STATUS".equals(oldType)) Arrays.fill(coilState, -1);
            if ("VARS".equals(oldType)) for (int i = 0; i < MAX_REGS; i++) regKnown[i] = false;
            endTransaction(PH_TIMEOUT, "تا ۹۰ ثانیه پاسخی نرسید. برای جلوگیری از اجرای تکراری، فرمان خودکار دوباره ارسال نشد؛ وضعیت را رفرش کنید.");
        }, RESPONSE_TIMEOUT_MS);
    }

    private void queueVarsAfterStatus() {
        // Keep the UI locked across both phases of a refresh transaction.
        expectedType = "VARS";
        expectedIndex = -1;
        pendingCommand = "VARS";
        activeToken = nextToken();
        final long token = activeToken;
        cmdCode = "STATUS ← VARS";
        cmdNote = "✓ وضعیت رله‌ها دریافت شد؛ در حال درخواست رجیسترها (VARS)…";
        cmdSmsSent = false;
        cmdGotReply = false;
        cmdPhase = PH_SENDING;
        cmdPhaseStart = System.currentTimeMillis();
        stateMessage = "وضعیت رله‌ها دریافت شد؛ در حال درخواست مقدار رجیسترها...";
        armTimeout(token);
        render(false);
        updateSheet();
        handler.postDelayed(() -> {
            if (waiting && activeToken == token) transmitSms(normalizePhone(deviceNumber()), "VARS", token);
        }, 650L);
    }

    private void queueAdminNumber() {
        final String phone = pendingAdminPhone;
        final String body = "*" + phone + "*";
        expectedType = "ADMIN_NUM";
        expectedIndex = -1;
        pendingCommand = body;
        activeToken = nextToken();
        final long token = activeToken;
        cmdCode = pendingCommand;
        cmdNote = "✓ کنترلر شمارهٔ مقصد را خواست؛ در حال ارسال شماره…";
        cmdSmsSent = false;
        cmdGotReply = false;
        cmdPhase = PH_SENDING;
        cmdPhaseStart = System.currentTimeMillis();
        stateMessage = "کنترلر شمارهٔ مقصد را خواست؛ شماره در حال ارسال است...";
        armTimeout(token);
        render(false);
        updateSheet();
        handler.postDelayed(() -> {
            if (waiting && activeToken == token) transmitSms(normalizePhone(deviceNumber()), body, token);
        }, 650L);
    }

    private void endTransaction(int phase, String message) {
        waiting = false;
        expectedType = "";
        expectedIndex = -1;
        pendingCommand = "";
        pendingAdminPhone = "";
        activeToken = nextToken(); // invalidates old timeout and send callbacks
        cmdPhase = phase;
        cmdResult = message;
        cmdNote = "";
        stateMessage = message;
        handler.removeCallbacks(ticker);
        boolean sheetOpen = sheet != null && sheet.isShowing();
        updateSheet();
        if (!sheetOpen) toast(message);
        if (root != null) {
            int haptic;
            if (phase == PH_OK) haptic = Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY;
            else haptic = Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.LONG_PRESS;
            root.performHapticFeedback(haptic);
        }
        render(false);
    }

    private long nextToken() {
        tokenCounter = Math.max(System.currentTimeMillis(), tokenCounter + 1L);
        return tokenCounter;
    }

    // ---------------------------------------------------------------- incoming SMS

    private void processStoredSmsIfNew() {
        long id = smsStore.getLong("last_id", 0L);
        if (id <= prefs.getLong("last_processed_sms_id", 0L)) return;
        handleIncomingOnce(smsStore.getString("last_sender", ""), smsStore.getString("last_body", ""), id);
    }

    private void handleIncomingOnce(String sender, String body, long id) {
        if (id <= 0L || id <= prefs.getLong("last_processed_sms_id", 0L)) return;
        prefs.edit().putLong("last_processed_sms_id", id).apply();
        if (sender == null || body == null) return;
        String configured = normalizePhone(deviceNumber());
        String incoming = normalizePhone(sender);
        if (configured.isEmpty() || !configured.equals(incoming)) {
            // Incoming messages from other senders are intentionally not shown as controller feedback.
            return;
        }

        String cleanBody = body.trim();
        lastReply = cleanBody;
        lastReplyAt = System.currentTimeMillis();
        prefs.edit().putString("last_reply", lastReply).putLong("last_reply_at", lastReplyAt).apply();

        int coilUpdates = parseCoilStates(cleanBody);
        int regUpdates = parseRegisterValues(cleanBody);
        String low = cleanBody.toLowerCase(Locale.ROOT);
        if (low.contains("power restored")) {
            stateMessage = "پیام راه‌اندازی کنترلر دریافت شد.";
            render(false);
            return;
        }

        if (waiting) {
            cmdGotReply = true;
            cmdReply = cleanBody;
            if ("STATUS".equals(expectedType)) {
                if (coilUpdates > 0 && (low.contains("coils:") ||
                        Pattern.compile("(?i)\\b\\d+\\s*:\\s*(ON|OFF|PULSE)").matcher(cleanBody).find())) {
                    if (regsEnabled()) queueVarsAfterStatus();
                    else endTransaction(PH_OK, "وضعیت " + activeCoils() + " رله دریافت و به‌روز شد.");
                    return;
                } else if (isControllerError(cleanBody)) {
                    endTransaction(PH_ERR, "کنترلر پاسخ خطا داد؛ متن پاسخ را بررسی کنید.");
                    return;
                }
            } else if ("VARS".equals(expectedType)) {
                if (regUpdates > 0) {
                    endTransaction(PH_OK, "وضعیت رله‌ها و مقدار رجیسترها به‌روز شد.");
                    return;
                } else if (isControllerError(cleanBody)) {
                    endTransaction(PH_ERR, "کنترلر پاسخ خطا داد؛ متن پاسخ را بررسی کنید.");
                    return;
                }
            } else if ("RELAY".equals(expectedType)) {
                boolean ok = containsSingleCoilResponse(cleanBody, expectedIndex + 1);
                if (ok) {
                    int st = coilState[expectedIndex];
                    String what = st == 1 ? "روشن شد" : st == 0 ? "خاموش شد" : "فرمان اجرا شد (پالس)";
                    endTransaction(PH_OK, "«" + coilNames[expectedIndex] + "» " + what + ".");
                    return;
                } else if (isControllerError(cleanBody)) {
                    endTransaction(PH_ERR, "کنترلر فرمان رله را نپذیرفت؛ پاسخ را بررسی کنید.");
                    return;
                }
            } else if ("REGWRITE".equals(expectedType)) {
                boolean ok = containsSingleRegResponse(cleanBody, expectedIndex + 1);
                if (ok) {
                    endTransaction(PH_OK, "مقدار «" + regNames[expectedIndex] + "» ثبت شد: " + regValue[expectedIndex]);
                    return;
                } else if (isControllerError(cleanBody)) {
                    endTransaction(PH_ERR, "کنترلر مقدار رجیستر را نپذیرفت؛ پاسخ را بررسی کنید.");
                    return;
                }
            } else if ("PULSE_CMD".equals(expectedType)) {
                Pattern pulseReply = Pattern.compile("(?i)\\bPulse\\s+on\\s+coil\\s+" + (expectedIndex + 1) + "\\b");
                if (pulseReply.matcher(cleanBody).find()) {
                    endTransaction(PH_OK, "پالس «" + coilNames[expectedIndex] + "» اجرا شد.");
                    return;
                } else if (isControllerError(cleanBody)) {
                    endTransaction(PH_ERR, "کنترلر فرمان لحظه‌ای را نپذیرفت (شاید این کانال Latch است)؛ پاسخ را بررسی کنید.");
                    return;
                }
            } else if ("ADMIN".equals(expectedType)) {
                if (low.contains("enter number with *")) {
                    if (!pendingAdminPhone.isEmpty()) {
                        queueAdminNumber();
                    } else {
                        endTransaction(PH_ERR, "کنترلر شماره خواست ولی شمارهٔ مقصدی ثبت نشده بود؛ دوباره تلاش کنید.");
                    }
                    return;
                }
                if (isControllerError(cleanBody)) endTransaction(PH_ERR, "کنترلر فرمان را نپذیرفت؛ پاسخ را بررسی کنید.");
                else endTransaction(PH_OK, "پاسخ فرمان دریافت شد.");
                return;
            } else if ("ADMIN_NUM".equals(expectedType)) {
                if (isControllerError(cleanBody)) endTransaction(PH_ERR, "کنترلر شماره را نپذیرفت؛ پاسخ را بررسی کنید.");
                else endTransaction(PH_OK, "عملیات انجام و پاسخ کنترلر دریافت شد.");
                return;
            }
        }

        if (coilUpdates > 0 || regUpdates > 0) stateMessage = "فیدبک پیامکی دریافت شد؛ مقادیر متناظر به‌روزرسانی شدند.";
        else stateMessage = "پیام از سیم‌کارت کنترلر دریافت شد.";
        updateSheet();
        render(false);
    }

    private int parseCoilStates(String body) {
        int updates = 0;
        Pattern statusList = Pattern.compile("(?i)\\b(\\d{1,2})\\s*:\\s*(ON|OFF|PULSE)\\b");
        Matcher listMatcher = statusList.matcher(body);
        while (listMatcher.find()) {
            int number = parseInt(listMatcher.group(1), -1);
            int state = coilStateFrom(listMatcher.group(2));
            if (number >= 1 && number <= MAX_COILS && state >= 0) {
                coilState[number - 1] = state;
                updates++;
            }
        }
        Pattern single = Pattern.compile("(?i)\\bCoil\\s+(\\d{1,2})\\s*=\\s*(ON|OFF|PULSE)\\b");
        Matcher singleMatcher = single.matcher(body);
        while (singleMatcher.find()) {
            int number = parseInt(singleMatcher.group(1), -1);
            int state = coilStateFrom(singleMatcher.group(2));
            if (number >= 1 && number <= MAX_COILS && state >= 0) {
                coilState[number - 1] = state;
                updates++;
            }
        }
        return updates;
    }

    private int parseRegisterValues(String body) {
        int updates = 0;
        Pattern list = Pattern.compile("(?i)\\bR\\s*(\\d{1,2})\\s*:\\s*(-?\\d+)");
        Matcher m = list.matcher(body);
        while (m.find()) {
            int number = parseInt(m.group(1), -1);
            int value = parseInt(m.group(2), -1);
            if (number >= 1 && number <= MAX_REGS && value >= 0 && value <= 65535) {
                regValue[number - 1] = value;
                regKnown[number - 1] = true;
                updates++;
            }
        }
        Pattern single = Pattern.compile("(?i)\\bReg\\s*(\\d{1,2})\\s*=\\s*(-?\\d+)");
        Matcher sm = single.matcher(body);
        while (sm.find()) {
            int number = parseInt(sm.group(1), -1);
            int value = parseInt(sm.group(2), -1);
            if (number >= 1 && number <= MAX_REGS && value >= 0 && value <= 65535) {
                regValue[number - 1] = value;
                regKnown[number - 1] = true;
                updates++;
            }
        }
        return updates;
    }

    private boolean containsSingleCoilResponse(String body, int number) {
        Pattern p = Pattern.compile("(?i)\\bCoil\\s+" + number + "\\s*=\\s*(ON|OFF|PULSE)\\b");
        return p.matcher(body).find();
    }

    private boolean containsSingleRegResponse(String body, int number) {
        Pattern p = Pattern.compile("(?i)\\bReg\\s*" + number + "\\s*=\\s*-?\\d+");
        return p.matcher(body).find();
    }

    private boolean isControllerError(String body) {
        String low = body.toLowerCase(Locale.ROOT);
        return low.contains("access denied") || low.contains("admin only") || low.contains("invalid") ||
                low.contains("usage:") || low.contains("user not found") || low.contains("not registered") ||
                low.contains("failed") || low.contains("all invite slots full") || low.contains("user exists or full");
    }

    private int coilStateFrom(String state) {
        if (state == null) return -1;
        if (state.equalsIgnoreCase("ON")) return 1;
        if (state.equalsIgnoreCase("OFF")) return 0;
        if (state.equalsIgnoreCase("PULSE")) return 2;
        return -1;
    }

    private int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); } catch (Exception ex) { return fallback; }
    }

    private String normalizePhone(String raw) {
        if (raw == null) return "";
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            int d = Character.digit(c, 10);
            if (d >= 0) digits.append((char) ('0' + d));
        }
        String value = digits.toString();
        if (value.startsWith("0098")) value = value.substring(2);
        else if (value.startsWith("0")) value = "98" + value.substring(1);
        else if (value.startsWith("9") && value.length() == 10) value = "98" + value;
        return value;
    }

    // ---------------------------------------------------------------- small UI helpers

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.trim().isEmpty()) return ex.getClass().getSimpleName();
        return message;
    }

    private void setEnabledLook(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.45f);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(12), dp(12), dp(12));
        c.setBackground(round(CARD, 15, BORDER));
        return c;
    }

    private LinearLayout sectionTitle(String title, String detail) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.addView(text(title, 17, TEXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        wrap.addView(text(detail, 11, MUTED, true));
        wrap.setPadding(dp(2), dp(4), dp(2), dp(12));
        return wrap;
    }

    private void addSectionTitle(String title, String detail) {
        contentHost.addView(sectionTitle(title, detail));
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setLineSpacing(dp(2), 1.0f);
        return v;
    }

    private Button button(String label, int background, int foreground) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setTextColor(foreground);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), dp(5), dp(10), dp(5));
        b.setBackground(round(background, 11, background == CARD_DARK ? BORDER : background));
        if (Build.VERSION.SDK_INT >= 21) b.setStateListAnimator(null);
        return b;
    }

    private GradientDrawable round(int color, int radiusDp, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(radiusDp));
        gd.setStroke(dp(1), strokeColor);
        return gd;
    }

    private LinearLayout.LayoutParams lpMatchWrap(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return lp;
    }

    /** Full-width, fixed-height layout params (height in dp). */
    private LinearLayout.LayoutParams lp(int heightDp, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp));
        lp.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return lp;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
