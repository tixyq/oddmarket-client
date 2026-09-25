package com.oddmarket;
// Settings screen.

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;
import java.io.File;

public class SettingsActivity extends Activity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Utils.applyLocale(newBase));
    }

    private final int[] iconContainers = { R.id.icon_1_container, R.id.icon_2_container, R.id.icon_3_container, R.id.icon_4_container };
    private final int[] iconChecks = { R.id.icon_1_check, R.id.icon_2_check, R.id.icon_3_check, R.id.icon_4_check };

    private final int[] langContainers = { R.id.lang_ru_container, R.id.lang_en_container };
    private final int[] langChecks = { R.id.lang_ru_check, R.id.lang_en_check };
    private final String[] langValues = { "ru", "en" };

    private final int[] themeContainers = { R.id.theme_light_container, R.id.theme_dark_container };
    private final int[] themeChecks = { R.id.theme_light_check, R.id.theme_dark_check };
    private final int[] themeValues = { Theme.LIGHT, Theme.DARK };

    private final String[] aliasNames = {
            "com.oddmarket.Launcher1",
            "com.oddmarket.Launcher2",
            "com.oddmarket.Launcher3",
            "com.oddmarket.Launcher4"
    };

    private RadioButton[] radioButtons;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FileLogger.init(this);
        setTitle(R.string.title_preferences);
        setContentView(R.layout.settings);
        Theme.applyFonts(findViewById(R.id.settings_root));
        applyTheme();

        Utils.enableActionBarUpButton(this);

        View topBlock = findViewById(R.id.settings_top_block);
        TextView versionText = (TextView) findViewById(R.id.settings_version_text);
        final CheckBox cbRusFix = (CheckBox) findViewById(R.id.settings_cb_rus_fix);
        final CheckBox cbLegacyDl = (CheckBox) findViewById(R.id.settings_cb_legacy_dl);

        findViewById(R.id.link_telegram).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBrowserLink("https://t.me/daom38");
            }
        });

        findViewById(R.id.link_owner).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBrowserLink("https://t.me/tixyq");
            }
        });

        String versionName = getString(R.string.unknown);
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionName = pInfo.versionName;
        } catch (Exception e) {
            FileLogger.w(Utils.TAG, "Could not read own package version", e);
        }
        versionText.setText(versionName);

        topBlock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAppSystemSettings();
            }
        });

        final SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        cbRusFix.setChecked(prefs.getBoolean("rus_url_fix", false));
        cbLegacyDl.setChecked(prefs.getBoolean("legacy_download", false));

        cbRusFix.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Utils.savePrefs(prefs.edit().putBoolean("rus_url_fix", isChecked));
            }
        });

        cbLegacyDl.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Utils.savePrefs(prefs.edit().putBoolean("legacy_download", isChecked));
            }
        });

        TextView btnSendLog = (TextView) findViewById(R.id.settings_btn_send_log);
        btnSendLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendLogFile();
            }
        });

        setupRootRequestButton();

        View iconSelectorScroll = findViewById(R.id.icon_selector_scroll);
        if (android.os.Build.VERSION.SDK_INT < 16) {
            if (iconSelectorScroll != null) {
                iconSelectorScroll.setVisibility(View.GONE);
            }
            View iconCaption = findViewById(R.id.settings_caption_icon);
            if (iconCaption != null) {
                iconCaption.setVisibility(View.GONE);
            }
        } else {
            radioButtons = new RadioButton[4];
            for (int i = 0; i < 4; i++) {
                radioButtons[i] = (RadioButton) findViewById(iconChecks[i]);
            }

            int currentIconIndex = prefs.getInt("selected_icon", 0);
            if (currentIconIndex < 0 || currentIconIndex > 3) currentIconIndex = 0;

            if (radioButtons[currentIconIndex] != null) {
                radioButtons[currentIconIndex].setChecked(true);
            }

            for (int i = 0; i < 4; i++) {
                updateIconContentDescription(findViewById(iconContainers[i]), i, i == currentIconIndex);
            }

            for (int i = 0; i < 4; i++) {
                final int index = i;
                View container = findViewById(iconContainers[i]);
                if (container != null) {
                    container.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            int oldIndex = prefs.getInt("selected_icon", 0);
                            if (oldIndex != index) {
                                if (radioButtons[oldIndex] != null) {
                                    radioButtons[oldIndex].setChecked(false);
                                }
                                if (radioButtons[index] != null) {
                                    radioButtons[index].setChecked(true);
                                }
                                updateIconContentDescription(findViewById(iconContainers[oldIndex]), oldIndex, false);
                                updateIconContentDescription(findViewById(iconContainers[index]), index, true);

                                Utils.savePrefs(prefs.edit().putInt("selected_icon", index));

                                applyLauncherIcon(index);

                                Toast.makeText(SettingsActivity.this, R.string.toast_restart_to_apply, Toast.LENGTH_SHORT).show();

                                new Handler().postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        finish();
                                        android.os.Process.killProcess(android.os.Process.myPid());
                                        System.exit(0);
                                    }
                                }, 2500);
                            }
                        }
                    });
                }
            }
        }

        setupLanguageSelector(prefs);
        setupThemeSelector(prefs);
    }

    private void updateIconContentDescription(View container, int index, boolean selected) {
        if (container == null) return;
        String desc = getString(R.string.desc_icon_option_format, index + 1);
        if (selected) {
            desc = desc + getString(R.string.desc_selected_suffix);
        }
        container.setContentDescription(desc);
    }

    private void updateSelectionContentDescription(View container, String label, boolean selected) {
        if (container == null) return;
        container.setContentDescription(selected ? label + getString(R.string.desc_selected_suffix) : label);
    }

    private void setupLanguageSelector(final SharedPreferences prefs) {
        final RadioButton[] langRadios = new RadioButton[langChecks.length];
        for (int i = 0; i < langChecks.length; i++) {
            langRadios[i] = (RadioButton) findViewById(langChecks[i]);
        }

        String currentLang = prefs.getString("app_language", "en");
        int currentIndex = 1;
        for (int i = 0; i < langValues.length; i++) {
            if (langValues[i].equals(currentLang)) {
                currentIndex = i;
                break;
            }
        }
        if (langRadios[currentIndex] != null) {
            langRadios[currentIndex].setChecked(true);
        }

        String[] langLabels = { getString(R.string.settings_lang_ru), getString(R.string.settings_lang_en) };
        for (int i = 0; i < langContainers.length; i++) {
            updateSelectionContentDescription(findViewById(langContainers[i]), langLabels[i], i == currentIndex);
        }

        for (int i = 0; i < langContainers.length; i++) {
            final int index = i;
            View container = findViewById(langContainers[i]);
            if (container == null) continue;
            container.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String oldLang = prefs.getString("app_language", "en");
                    if (!langValues[index].equals(oldLang)) {
                        for (RadioButton rb : langRadios) {
                            if (rb != null) rb.setChecked(false);
                        }
                        if (langRadios[index] != null) {
                            langRadios[index].setChecked(true);
                        }

                        Utils.savePrefs(prefs.edit().putString("app_language", langValues[index]));
                        restartApp();
                    }
                }
            });
        }
    }

    private void setupThemeSelector(final SharedPreferences prefs) {
        final RadioButton[] themeRadios = new RadioButton[themeChecks.length];
        for (int i = 0; i < themeChecks.length; i++) {
            themeRadios[i] = (RadioButton) findViewById(themeChecks[i]);
        }

        int currentTheme = prefs.getInt("app_theme", Theme.LIGHT);
        int currentIndex = 0;
        for (int i = 0; i < themeValues.length; i++) {
            if (themeValues[i] == currentTheme) {
                currentIndex = i;
                break;
            }
        }
        if (themeRadios[currentIndex] != null) {
            themeRadios[currentIndex].setChecked(true);
        }

        String[] themeLabels = { getString(R.string.settings_theme_light), getString(R.string.settings_theme_dark) };
        for (int i = 0; i < themeContainers.length; i++) {
            updateSelectionContentDescription(findViewById(themeContainers[i]), themeLabels[i], i == currentIndex);
        }

        for (int i = 0; i < themeContainers.length; i++) {
            final int index = i;
            View container = findViewById(themeContainers[i]);
            if (container == null) continue;
            container.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int oldTheme = prefs.getInt("app_theme", Theme.LIGHT);
                    if (themeValues[index] != oldTheme) {
                        for (RadioButton rb : themeRadios) {
                            if (rb != null) rb.setChecked(false);
                        }
                        if (themeRadios[index] != null) {
                            themeRadios[index].setChecked(true);
                        }

                        Utils.savePrefs(prefs.edit().putInt("app_theme", themeValues[index]));
                        restartApp();
                    }
                }
            });
        }
    }

    private void restartApp() {
        Toast.makeText(this, R.string.toast_restart_to_apply, Toast.LENGTH_SHORT).show();
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(0);
            }
        }, 2500);
    }

    private void applyTheme() {
        View scrollRoot = findViewById(R.id.settings_scroll_root);
        View innerRoot = findViewById(R.id.settings_root);
        if (scrollRoot != null) scrollRoot.setBackgroundColor(Theme.windowBackground());
        if (innerRoot != null) innerRoot.setBackgroundColor(Theme.windowBackground());

        Utils.setTextColorIfPresent(this, R.id.settings_app_name_text, Theme.textPrimary());
        Utils.setTextColorIfPresent(this, R.id.settings_version_text, Theme.textSecondary());

        Utils.setTextColorIfPresent(this, R.id.settings_caption_general, Theme.textSecondary());

        View togglesBlock = findViewById(R.id.settings_toggles_block);
        if (togglesBlock != null) togglesBlock.setBackgroundColor(Theme.tabRowBackground());
        Utils.setTextColorIfPresent(this, R.id.settings_rus_fix_label, Theme.textPrimary());
        Utils.setTextColorIfPresent(this, R.id.settings_legacy_dl_label, Theme.textPrimary());
        Utils.setTextColorIfPresent(this, R.id.settings_btn_send_log, Theme.textPrimary());
        Utils.setTextColorIfPresent(this, R.id.settings_btn_request_root, Theme.textPrimary());

        View iconSelectorScroll = findViewById(R.id.icon_selector_scroll);
        if (iconSelectorScroll != null) iconSelectorScroll.setBackgroundColor(Theme.tabRowBackground());

        Utils.setTextColorIfPresent(this, R.id.settings_caption_language, Theme.textSecondary());
        Utils.setTextColorIfPresent(this, R.id.settings_caption_theme, Theme.textSecondary());
        Utils.setTextColorIfPresent(this, R.id.settings_caption_icon, Theme.textSecondary());

        View languageRow = findViewById(R.id.language_selector_row);
        if (languageRow != null) languageRow.setBackgroundColor(Theme.tabRowBackground());
        Utils.setTextColorIfPresent(this, R.id.lang_ru_text, Theme.textPrimary());
        Utils.setTextColorIfPresent(this, R.id.lang_en_text, Theme.textPrimary());

        View themeRow = findViewById(R.id.theme_selector_row);
        if (themeRow != null) themeRow.setBackgroundColor(Theme.tabRowBackground());
        Utils.setTextColorIfPresent(this, R.id.theme_light_text, Theme.textPrimary());
        Utils.setTextColorIfPresent(this, R.id.theme_dark_text, Theme.textPrimary());

        Utils.setTextColorIfPresent(this, R.id.settings_caption_information, Theme.textSecondary());

        View disclaimerBlock = findViewById(R.id.settings_disclaimer_block);
        if (disclaimerBlock != null) disclaimerBlock.setBackgroundColor(Theme.tabRowBackground());
        Utils.setTextColorIfPresent(this, R.id.settings_disclaimer_text, Theme.textPrimary());
        Utils.setTextColorIfPresent(this, R.id.settings_telegram_label_text, Theme.textPrimary());
        Utils.setTextColorIfPresent(this, R.id.settings_admin_label_text, Theme.textPrimary());
        Utils.setTextColorIfPresent(this, R.id.link_telegram, Theme.linkColor());
        Utils.setTextColorIfPresent(this, R.id.link_owner, Theme.linkColor());
    }

    private void sendLogFile() {
        File logFile = FileLogger.getLogFile();
        if (logFile == null || !logFile.exists() || logFile.length() == 0) {
            Toast.makeText(this, R.string.toast_no_log_file, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Uri logUri;
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                logUri = Uri.parse("content://com.oddmarket.provider/log");
            } else {
                logUri = Uri.fromFile(logFile);
            }

            Intent sendIntent = new Intent(Intent.ACTION_SEND);
            sendIntent.setType("text/plain");
            sendIntent.putExtra(Intent.EXTRA_SUBJECT, "OddMarket log");
            sendIntent.putExtra(Intent.EXTRA_STREAM, logUri);
            sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(sendIntent, getString(R.string.settings_send_log_label)));
        } catch (Exception e) {
            FileLogger.w(Utils.TAG, "Failed to launch log share intent", e);
            Toast.makeText(this, R.string.toast_no_email_app_found, Toast.LENGTH_SHORT).show();
        }
    }

    private void setupRootRequestButton() {
        final TextView btnRequestRoot = (TextView) findViewById(R.id.settings_btn_request_root);
        if (btnRequestRoot == null) return;

        if (!Utils.isSuBinaryPresent()) {
            btnRequestRoot.setVisibility(View.GONE);
            return;
        }

        btnRequestRoot.setVisibility(Utils.getCachedRootState() == 1 ? View.GONE : View.VISIBLE);

        btnRequestRoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnRequestRoot.setEnabled(false);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final boolean granted = Utils.requestRootAccessAndPersist(SettingsActivity.this);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                btnRequestRoot.setEnabled(true);
                                if (granted) {

                                    btnRequestRoot.setVisibility(View.GONE);
                                    Toast.makeText(SettingsActivity.this, R.string.toast_root_access_granted, Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(SettingsActivity.this, R.string.toast_root_access_denied, Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                    }
                }, "OddMarket-RootRequest").start();
            }
        });
    }

    private void openBrowserLink(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.toast_no_browser_found, Toast.LENGTH_SHORT).show();
        }
    }

    private void applyLauncherIcon(int activeIndex) {
        PackageManager pm = getPackageManager();

        ComponentName activeComponent = new ComponentName(this, aliasNames[activeIndex]);
        pm.setComponentEnabledSetting(activeComponent, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);

        for (int i = 0; i < aliasNames.length; i++) {
            if (i != activeIndex) {
                ComponentName component = new ComponentName(this, aliasNames[i]);
                pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            }
        }
    }

    private void openAppSystemSettings() {
        try {
            Intent intent = new Intent("android.settings.APPLICATION_DETAILS_SETTINGS");
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setClassName("com.android.settings", "com.android.settings.InstalledAppDetails");
                intent.putExtra("com.android.settings.ApplicationPkgName", getPackageName());
                intent.putExtra("pkg", getPackageName());
                startActivity(intent);
            } catch (Exception ex) {
                FileLogger.w(Utils.TAG, "No settings activity available for this device", ex);
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (Utils.handleHomeMenuItem(this, item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
