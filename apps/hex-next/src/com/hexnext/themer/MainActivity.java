package com.hexnext.themer;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Iterator;

public class MainActivity extends Activity {

    private WebView webView;
    private Vibrator vibrator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Immersive One UI dark system bar integration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().setNavigationBarColor(0xFF080A0F);
            getWindow().setStatusBarColor(0xFF080A0F);
        }

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.setBackgroundColor(0xFF080A0F);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");
    }

    private class AndroidBridge {

        @JavascriptInterface
        public void vibrate() {
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(20);
                }
            }
        }

        @JavascriptInterface
        public void showToast(final String message) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public String executeShell(String cmd) {
            StringBuilder sb = new StringBuilder();
            try {
                Process p;
                try {
                    p = Runtime.getRuntime().exec(new String[]{"rish", "-c", cmd});
                } catch (Exception e) {
                    try {
                        p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                    } catch (Exception e2) {
                        p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                    }
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                p.waitFor();
            } catch (Exception e) {
                sb.append("Error: ").append(e.getMessage());
            }
            return sb.toString();
        }

        @JavascriptInterface
        public boolean applyTheme(String jsonConfig) {
            try {
                JSONObject obj = new JSONObject(jsonConfig);
                String accent = sanitizeHex(obj.optString("accent", "00E5FF"));
                String bg = sanitizeHex(obj.optString("bg", "000000"));
                String card = sanitizeHex(obj.optString("card", "10141E"));
                String secondary = sanitizeHex(obj.optString("secondary", "7C4DFF"));
                String qsInactive = sanitizeHex(obj.optString("qsInactive", "1C202F"));

                JSONObject apps = obj.optJSONObject("apps");

                // 1. Android Framework
                if (isAppSelected(apps, "android")) {
                    String cmd = "cmd overlay fabricate --target android --name hexnext_android " +
                            "color/background_dark 0x1c 0x" + bg + " " +
                            "color/primary_material_dark 0x1c 0x" + bg + " " +
                            "color/system_neutral1_900 0x1c 0x" + bg + " " +
                            "color/system_neutral1_1000 0x1c 0x" + bg + " " +
                            "color/system_accent1_600 0x1c 0x" + accent + " " +
                            "color/system_accent1_100 0x1c 0x" + card + " " +
                            "color/system_accent2_600 0x1c 0x" + secondary;
                    executeShell(cmd);
                    executeShell("cmd overlay enable --user current com.android.shell:hexnext_android");
                }

                // 2. SystemUI & Quick Settings
                if (isAppSelected(apps, "systemui")) {
                    String cmd = "cmd overlay fabricate --target com.android.systemui --name hexnext_systemui " +
                            "color/qs_background_dark 0x1c 0x" + bg + " " +
                            "color/quick_settings_panel_background_color 0x1c 0x" + bg + " " +
                            "color/notification_panel_background_color 0x1c 0x" + bg + " " +
                            "color/qs_tile_active_color 0x1c 0x" + accent + " " +
                            "color/qs_tile_inactive_color 0x1c 0x" + qsInactive;
                    executeShell(cmd);
                    executeShell("cmd overlay enable --user current com.android.shell:hexnext_systemui");
                }

                // 3. Settings
                if (isAppSelected(apps, "settings")) {
                    String cmd = "cmd overlay fabricate --target com.android.settings --name hexnext_settings " +
                            "color/sec_widget_round_and_bgcolor 0x1c 0x" + card + " " +
                            "color/sec_dashboard_tab_selected_color 0x1c 0x" + accent + " " +
                            "color/sec_dashboard_background_color 0x1c 0x" + bg;
                    executeShell(cmd);
                    executeShell("cmd overlay enable --user current com.android.shell:hexnext_settings");
                }

                // 4. Samsung Dialer & Phone
                if (isAppSelected(apps, "dialer")) {
                    String cmd = "cmd overlay fabricate --target com.samsung.android.dialer --name hexnext_dialer " +
                            "color/primary_color 0x1c 0x" + accent + " " +
                            "color/dialpad_background_color 0x1c 0x" + bg;
                    executeShell(cmd);
                    executeShell("cmd overlay enable --user current com.android.shell:hexnext_dialer");
                }

                // 5. Samsung Keyboard (HoneyBoard)
                if (isAppSelected(apps, "honeyboard")) {
                    String cmd = "cmd overlay fabricate --target com.samsung.android.honeyboard --name hexnext_keyboard " +
                            "color/theme_keyboard_bg_color 0x1c 0x" + bg + " " +
                            "color/theme_key_bg_color 0x1c 0x" + card + " " +
                            "color/theme_key_accent_color 0x1c 0x" + accent;
                    executeShell(cmd);
                    executeShell("cmd overlay enable --user current com.android.shell:hexnext_keyboard");
                }

                // 6. Samsung My Files
                if (isAppSelected(apps, "myfiles")) {
                    String cmd = "cmd overlay fabricate --target com.sec.android.app.myfiles --name hexnext_myfiles " +
                            "color/main_bg_color 0x1c 0x" + bg + " " +
                            "color/accent_color 0x1c 0x" + accent;
                    executeShell(cmd);
                    executeShell("cmd overlay enable --user current com.android.shell:hexnext_myfiles");
                }

                // 7. Samsung Messages
                if (isAppSelected(apps, "messages")) {
                    String cmd = "cmd overlay fabricate --target com.samsung.android.messaging --name hexnext_messages " +
                            "color/main_bg_color 0x1c 0x" + bg + " " +
                            "color/accent_color 0x1c 0x" + accent;
                    executeShell(cmd);
                    executeShell("cmd overlay enable --user current com.android.shell:hexnext_messages");
                }

                // 8. Samsung Contacts
                if (isAppSelected(apps, "contacts")) {
                    String cmd = "cmd overlay fabricate --target com.samsung.android.app.contacts --name hexnext_contacts " +
                            "color/main_bg_color 0x1c 0x" + bg + " " +
                            "color/accent_color 0x1c 0x" + accent;
                    executeShell(cmd);
                    executeShell("cmd overlay enable --user current com.android.shell:hexnext_contacts");
                }

                // 9. Monet Wizard & Samsung Theme Subsystem
                executeShell("cmd overlay enable --user current android:SemWT_android");
                executeShell("cmd overlay enable --user current android:SemWT_com.android.systemui");
                executeShell("cmd overlay enable --user current android:SemWT_MonetPalette");
                executeShell("cmd overlay enable --user current android:SemWT_G_MonetPalette");
                executeShell("settings put system current_sec_active_themepackage com.samsung.themedesigner.HexNext");

                return true;
            } catch (Exception e) {
                return false;
            }
        }

        private boolean isAppSelected(JSONObject apps, String key) {
            if (apps == null) return true;
            JSONObject item = apps.optJSONObject(key);
            if (item != null) {
                return item.optBoolean("selected", true);
            }
            return true;
        }

        private String sanitizeHex(String hex) {
            String clean = hex.replace("#", "").trim();
            if (clean.length() == 6) {
                return "ff" + clean;
            }
            if (clean.length() == 8) {
                return clean;
            }
            return "ffffffff";
        }

        @JavascriptInterface
        public void resetTheme() {
            String[] overlays = {
                    "hexnext_android",
                    "hexnext_systemui",
                    "hexnext_settings",
                    "hexnext_dialer",
                    "hexnext_keyboard",
                    "hexnext_myfiles",
                    "hexnext_messages",
                    "hexnext_contacts"
            };
            for (String ov : overlays) {
                executeShell("cmd overlay disable --user current com.android.shell:" + ov);
            }
            executeShell("settings delete system current_sec_active_themepackage");
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
