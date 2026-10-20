package fr.alexis.babyphone;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_BABY = 1;
    private static final int REQ_PARENT = 2;
    private static final String PERM_NOTIF = "android.permission.POST_NOTIFICATIONS";
    private static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    private LinearLayout babyPanel;
    private LinearLayout parentPanel;
    private Button modeBaby;
    private Button modeParent;
    private Button babyToggle;
    private Button parentToggle;
    private Button listenToggle;
    private Button batteryButton;
    private TextView batteryHint;
    private ProgressBar meter;
    private SeekBar thresholdBar;
    private TextView thresholdLabel;
    private TextView babyInfo;
    private TextView parentInfo;
    private TextView chooseHint;
    private EditText ipField;
    private CheckBox autoListen;
    private CheckBox alertSound;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.FRANCE);
    private String cachedIp = "?";
    private int tick = 0;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 300);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(AudioManager.STREAM_ALARM);
        setContentView(buildUi());
        showMode(Prefs.mode(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        tick = 0;
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    // ---------------------------------------------------------------- interface

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(16);
        return b;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(0, dp(12), 0, 0);
        return l;
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(MATCH, WRAP);
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(32));
        scroll.addView(root);

        root.addView(text("Babyphone", 28, true));
        root.addView(text("Rôle de ce téléphone :", 15, false));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        modeBaby = button("👶  Bébé");
        modeParent = button("👂  Parent");
        row.addView(modeBaby, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(modeParent, new LinearLayout.LayoutParams(0, WRAP, 1f));
        root.addView(row, full());
        modeBaby.setOnClickListener(v -> switchMode("baby"));
        modeParent.setOnClickListener(v -> switchMode("parent"));

        chooseHint = text("Installe l'appli sur les deux téléphones, choisis « Bébé » sur celui qui reste "
                + "dans la chambre et « Parent » sur le tien. Les deux doivent être sur le même Wi-Fi.", 14, false);
        root.addView(chooseHint);

        // ---- Mode bébé
        babyPanel = vertical();
        babyInfo = text("", 16, false);
        babyPanel.addView(babyInfo);
        babyPanel.addView(text("Niveau sonore", 14, true));
        meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        meter.setMax(100);
        babyPanel.addView(meter, new LinearLayout.LayoutParams(MATCH, dp(20)));
        thresholdLabel = text("", 14, true);
        babyPanel.addView(thresholdLabel);
        thresholdBar = new SeekBar(this);
        thresholdBar.setMax(100);
        thresholdBar.setProgress(Prefs.threshold(this));
        thresholdBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int v = Math.max(5, progress);
                BabyService.threshold = v;
                Prefs.setThreshold(MainActivity.this, v);
                updateThresholdLabel();
            }

            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        babyPanel.addView(thresholdBar, full());
        babyPanel.addView(text("Règle le seuil un peu au-dessus du niveau de la chambre au calme "
                + "(ventilateur, bruit blanc…). La barre passe au rouge quand le seuil est dépassé. "
                + "Une alerte part si le bruit dure au moins une demi-seconde.", 13, false));
        babyToggle = button("");
        babyToggle.setOnClickListener(v -> toggleBaby());
        babyPanel.addView(babyToggle, full());
        babyPanel.addView(text("Laisse ce téléphone branché sur secteur, à 1 ou 2 m du lit. "
                + "L'écran peut s'éteindre, l'écoute continue.", 13, false));
        root.addView(babyPanel, full());

        // ---- Mode parent
        parentPanel = vertical();
        parentInfo = text("", 16, false);
        parentPanel.addView(parentInfo);
        listenToggle = button("");
        listenToggle.setOnClickListener(v -> toggleListen());
        parentPanel.addView(listenToggle, full());

        autoListen = new CheckBox(this);
        autoListen.setText("Écouter automatiquement 30 s quand bébé fait du bruit");
        autoListen.setChecked(Prefs.autoListen(this));
        autoListen.setOnCheckedChangeListener((b, checked) -> Prefs.setAutoListen(this, checked));
        parentPanel.addView(autoListen, full());

        alertSound = new CheckBox(this);
        alertSound.setText("Son + vibration d'alerte (même en mode silencieux)");
        alertSound.setChecked(Prefs.alertSound(this));
        alertSound.setOnCheckedChangeListener((b, checked) -> Prefs.setAlertSound(this, checked));
        parentPanel.addView(alertSound, full());

        parentPanel.addView(text("Adresse IP du téléphone bébé (laisse vide pour la recherche automatique) :", 13, false));
        ipField = new EditText(this);
        ipField.setSingleLine(true);
        ipField.setHint("ex. 192.168.1.42");
        ipField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ipField.setText(Prefs.manualIp(this));
        parentPanel.addView(ipField, full());

        parentToggle = button("");
        parentToggle.setOnClickListener(v -> toggleParent());
        parentPanel.addView(parentToggle, full());
        parentPanel.addView(text("Le son passe par le volume des alarmes : il reste audible en mode silencieux. "
                + "Règle-le avec les touches de volume quand l'appli est ouverte.", 13, false));
        root.addView(parentPanel, full());

        // ---- Batterie
        batteryButton = button("⚡  Autoriser le fonctionnement en arrière-plan");
        batteryButton.setOnClickListener(v -> askBatteryExemption());
        LinearLayout.LayoutParams lp = full();
        lp.topMargin = dp(16);
        root.addView(batteryButton, lp);
        batteryHint = text("Indispensable pour que l'appli tienne écran éteint, sur les deux téléphones. "
                + "Sur Xiaomi, Samsung, Huawei, Oppo…, vérifie aussi dans les réglages batterie que "
                + "l'appli n'est pas « optimisée » ni « mise en veille ».", 12, false);
        root.addView(batteryHint);

        updateThresholdLabel();
        return scroll;
    }

    private void updateThresholdLabel() {
        thresholdLabel.setText("Seuil de déclenchement : " + BabyService.threshold + " / 100");
    }

    private void switchMode(String mode) {
        if (BabyService.running && !"baby".equals(mode)) {
            toast("Arrête d'abord le babyphone.");
            return;
        }
        if (ParentService.running && !"parent".equals(mode)) {
            toast("Arrête d'abord le mode parent.");
            return;
        }
        Prefs.setMode(this, mode);
        showMode(mode);
    }

    private void showMode(String mode) {
        boolean baby = "baby".equals(mode);
        boolean parent = "parent".equals(mode);
        babyPanel.setVisibility(baby ? View.VISIBLE : View.GONE);
        parentPanel.setVisibility(parent ? View.VISIBLE : View.GONE);
        chooseHint.setVisibility(baby || parent ? View.GONE : View.VISIBLE);
        modeBaby.setAlpha(baby || !parent ? 1f : 0.45f);
        modeParent.setAlpha(parent || !baby ? 1f : 0.45f);
    }

    private void refresh() {
        if (tick++ % 10 == 0) cachedIp = localIp();

        // Bébé
        boolean br = BabyService.running;
        babyToggle.setText(br ? "⏹  Arrêter le babyphone" : "▶  Démarrer le babyphone");
        StringBuilder sb = new StringBuilder();
        if (br) {
            sb.append("✅ Écoute en cours\n");
            sb.append("Adresse de ce téléphone : ").append(cachedIp).append('\n');
            sb.append("Téléphones parents connectés : ").append(BabyService.clientCount);
        } else {
            sb.append("⏸ Babyphone arrêté");
        }
        if (BabyService.lastAlert > 0) {
            sb.append("\nDernier bruit détecté : ").append(timeFormat.format(new Date(BabyService.lastAlert)));
        }
        if (BabyService.error != null) {
            sb.append("\n⚠️ ").append(BabyService.error);
        }
        babyInfo.setText(sb);
        int lvl = br ? BabyService.level : 0;
        meter.setProgress(lvl);
        meter.setProgressTintList(ColorStateList.valueOf(
                lvl >= BabyService.threshold ? Color.rgb(220, 60, 60) : Color.rgb(60, 170, 90)));

        // Parent
        boolean pr = ParentService.running;
        parentToggle.setText(pr ? "⏹  Arrêter le mode parent" : "▶  Démarrer le mode parent");
        ipField.setEnabled(!pr);
        StringBuilder p = new StringBuilder();
        if (!pr) {
            p.append("⏸ Mode parent arrêté");
        } else if (ParentService.connected) {
            p.append("✅ Connecté au babyphone (").append(ParentService.host).append(')');
            int bat = ParentService.babyBattery;
            if (bat >= 0) {
                p.append("\n🔋 Téléphone bébé : ").append(bat).append(" %")
                        .append(ParentService.babyCharging ? " (en charge)" : " (sur batterie)");
                if (!ParentService.babyCharging && bat < 25) {
                    p.append("\n⚠️ Branche le téléphone bébé !");
                }
            }
        } else {
            p.append("⏳ ").append(ParentService.status);
        }
        if (ParentService.lastAlert > 0) {
            p.append("\n🔔 Dernière alerte : ").append(timeFormat.format(new Date(ParentService.lastAlert)));
        }
        parentInfo.setText(p);
        listenToggle.setEnabled(pr && ParentService.connected);
        listenToggle.setText(ParentService.listening ? "🔇  Arrêter l'écoute" : "🔊  Écouter maintenant");

        // Batterie
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean exempt = pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
        int vis = exempt ? View.GONE : View.VISIBLE;
        batteryButton.setVisibility(vis);
        batteryHint.setVisibility(vis);
    }

    // ---------------------------------------------------------------- actions

    private void toggleBaby() {
        if (BabyService.running) {
            stopService(new Intent(this, BabyService.class));
            return;
        }
        List<String> missing = new ArrayList<>();
        if (missing(Manifest.permission.RECORD_AUDIO)) missing.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && missing(PERM_NOTIF)) missing.add(PERM_NOTIF);
        if (missing.isEmpty()) {
            startBaby();
        } else {
            requestPermissions(missing.toArray(new String[0]), REQ_BABY);
        }
    }

    private void startBaby() {
        BabyService.error = null;
        startForegroundService(new Intent(this, BabyService.class));
    }

    private void toggleParent() {
        if (ParentService.running) {
            stopService(new Intent(this, ParentService.class));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && missing(PERM_NOTIF)) {
            requestPermissions(new String[]{PERM_NOTIF}, REQ_PARENT);
        } else {
            startParent();
        }
    }

    private void startParent() {
        Prefs.setManualIp(this, ipField.getText().toString().trim());
        startForegroundService(new Intent(this, ParentService.class));
    }

    private void toggleListen() {
        if (!ParentService.running) return;
        Intent i = new Intent(this, ParentService.class);
        i.setAction(ParentService.listening ? ParentService.ACTION_LISTEN_OFF : ParentService.ACTION_LISTEN);
        startService(i);
    }

    private void askBatteryExemption() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                toast("Ouvre les réglages batterie et désactive l'optimisation pour Babyphone.");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BABY) {
            if (missing(Manifest.permission.RECORD_AUDIO)) {
                toast("Le micro est indispensable pour le mode bébé.");
            } else {
                startBaby();
            }
        } else if (requestCode == REQ_PARENT) {
            if (Build.VERSION.SDK_INT >= 33 && missing(PERM_NOTIF)) {
                toast("Sans notifications, tu ne verras pas les alertes à l'écran (le son fonctionne quand même).");
            }
            startParent();
        }
    }

    // ---------------------------------------------------------------- utilitaires

    private boolean missing(String permission) {
        return checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "?";
    }
}
