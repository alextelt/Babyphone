package fr.alexis.babyphone;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Mode « téléphone parent » : reste connecté au téléphone bébé sur le Wi-Fi
 * sans garder le processeur éveillé, sonne quand bébé fait du bruit et permet
 * d'écouter en direct.
 */
public class ParentService extends Service {
    static final String ACTION_STOP = "fr.alexis.babyphone.PARENT_STOP";
    static final String ACTION_LISTEN = "fr.alexis.babyphone.LISTEN";
    static final String ACTION_LISTEN_OFF = "fr.alexis.babyphone.LISTEN_OFF";

    private static final int NOTIF_ID = 2;
    private static final int ALERT_ID = 3;
    private static final int LOST_ID = 4;
    /** Sans nouvelles du bébé pendant 50 s → alerte « déconnecté ». */
    private static final long HEARTBEAT_TIMEOUT_MS = 50_000;
    /**
     * Avant d'alerter « déconnecté », on force une reconnexion et on laisse
     * ce délai au téléphone bébé pour redonner signe de vie (micro-coupures Wi-Fi).
     */
    private static final long GRACE_MS = 20_000;
    private static final long AUTO_LISTEN_MS = 30_000;
    private static final long MANUAL_LISTEN_MS = 15 * 60_000;

    static volatile ParentService instance;

    // État lu par l'écran de l'appli.
    static volatile boolean running = false;
    static volatile boolean connected = false;
    static volatile boolean listening = false;
    static volatile int babyBattery = -1;
    static volatile boolean babyCharging = false;
    static volatile long lastHeartbeat = 0;
    static volatile long lastAlert = 0;
    static volatile String host = "";
    static volatile String status = "Arrêté";

    private final Object audioLock = new Object();
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private Handler main;
    private NotificationManager nm;
    private Thread netThread;
    private volatile Socket socket;
    private volatile DataOutputStream out;
    private AudioTrack track;
    private long listenUntil = 0;
    private PowerManager.WakeLock reconnectLock;
    private PowerManager.WakeLock listenLock;
    private WifiManager.WifiLock listenWifiLock;
    private volatile boolean lostNotified = false;
    private boolean graceActive = false;
    private PowerManager.WakeLock graceLock;
    private final Runnable graceCheck = () -> endGrace();
    private boolean triedLastIp = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        main = new Handler(Looper.getMainLooper());
        nm = getSystemService(NotificationManager.class);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        reconnectLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "babyphone:reconnect");
        reconnectLock.setReferenceCounted(false);
        graceLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "babyphone:grace");
        graceLock.setReferenceCounted(false);
        listenLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "babyphone:listen");
        listenLock.setReferenceCounted(false);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            listenWifiLock = createWifiLock(wm);
        }
    }

    @SuppressWarnings("deprecation")
    private static WifiManager.WifiLock createWifiLock(WifiManager wm) {
        WifiManager.WifiLock l = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "babyphone:listen");
        l.setReferenceCounted(false);
        return l;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            Notifs.createChannels(this);
            Notification n = statusNotification("Connexion au babyphone…");
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                } else {
                    startForeground(NOTIF_ID, n);
                }
            } catch (Exception e) {
                stopSelf();
                return START_NOT_STICKY;
            }
            instance = this;
            running = true;
            status = "Connexion au babyphone…";
            netThread = new Thread(() -> netLoop(), "parent-net");
            netThread.start();
        }
        if (ACTION_LISTEN.equals(action)) {
            startListening(MANUAL_LISTEN_MS);
        } else if (ACTION_LISTEN_OFF.equals(action)) {
            stopListening();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        connected = false;
        instance = null;
        stopListening();
        closeSocketAsync();
        if (netThread != null) netThread.interrupt();
        cancelWatchdog();
        if (reconnectLock.isHeld()) reconnectLock.release();
        main.removeCallbacks(graceCheck);
        graceActive = false;
        if (graceLock.isHeld()) graceLock.release();
        if (nm != null) nm.cancel(LOST_ID);
        status = "Arrêté";
        sender.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- connexion

    private void netLoop() {
        while (running) {
            String h = findBaby();
            if (!running) break;
            if (h == null) {
                setStatus("Babyphone introuvable sur le Wi-Fi, nouvel essai…");
                holdReconnectLock();
                if (!pause(5000)) break;
                continue;
            }
            try {
                setStatus("Connexion à " + h + "…");
                Socket s = new Socket();
                s.connect(new InetSocketAddress(h, Protocol.PORT), 5000);
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                socket = s;
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                host = h;
                Prefs.setLastIp(this, h);
                triedLastIp = false;
                connected = true;
                onHeartbeat();
                if (reconnectLock.isHeld()) reconnectLock.release();
                setStatus("Connecté au babyphone");
                if (listening) {
                    sendAsync(Protocol.LISTEN_ON);
                }
                while (running) {
                    handle(Protocol.read(in));
                }
            } catch (IOException e) {
                // connexion impossible ou coupée : on réessaie
            } finally {
                connected = false;
                out = null;
                closeSocket();
            }
            if (running) {
                setStatus("Connexion perdue, reconnexion…");
                holdReconnectLock();
                if (!pause(3000)) break;
            }
        }
    }

    private void handle(Protocol.Frame f) {
        if (f.type == Protocol.HEARTBEAT) {
            String[] parts = f.text().split(",");
            try {
                babyBattery = Integer.parseInt(parts[0]);
                babyCharging = parts.length > 1 && "1".equals(parts[1]);
            } catch (NumberFormatException ignored) {}
            onHeartbeat();
        } else if (f.type == Protocol.ALERT) {
            onHeartbeat();
            main.post(() -> onAlert());
        } else if (f.type == Protocol.AUDIO) {
            synchronized (audioLock) {
                if (listening && track != null) {
                    track.write(f.data, 0, f.data.length);
                }
            }
        }
        if (listening && SystemClock.elapsedRealtime() > listenUntil) {
            stopListening();
        }
    }

    /** IP manuelle, sinon dernière IP connue, sinon recherche automatique. */
    private String findBaby() {
        String manual = Prefs.manualIp(this).trim();
        if (!manual.isEmpty()) return manual;
        String last = Prefs.lastIp(this);
        if (!last.isEmpty() && !triedLastIp) {
            triedLastIp = true;
            return last;
        }
        triedLastIp = false;
        setStatus("Recherche du babyphone sur le Wi-Fi…");
        String found = discover(8000);
        if (found != null) return found;
        return last.isEmpty() ? null : last;
    }

    @SuppressWarnings("deprecation")
    private String discover(long timeoutMs) {
        final NsdManager nsd = (NsdManager) getSystemService(NSD_SERVICE);
        if (nsd == null) return null;
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiManager.MulticastLock ml = null;
        if (wm != null) {
            ml = wm.createMulticastLock("babyphone:discovery");
            ml.setReferenceCounted(false);
            ml.acquire();
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] result = new String[1];
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String type, int code) { latch.countDown(); }
            @Override public void onStopDiscoveryFailed(String type, int code) {}
            @Override public void onDiscoveryStarted(String type) {}
            @Override public void onDiscoveryStopped(String type) {}
            @Override public void onServiceLost(NsdServiceInfo info) {}
            @Override public void onServiceFound(NsdServiceInfo info) {
                if (info.getServiceName() == null || !info.getServiceName().startsWith(Protocol.NSD_NAME)) {
                    return;
                }
                try {
                    nsd.resolveService(info, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo i, int code) {}
                        @Override public void onServiceResolved(NsdServiceInfo i) {
                            InetAddress a = i.getHost();
                            if (a != null && result[0] == null) {
                                result[0] = a.getHostAddress();
                                latch.countDown();
                            }
                        }
                    });
                } catch (Exception ignored) {}
            }
        };
        try {
            nsd.discoverServices(Protocol.NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        } finally {
            try { nsd.stopServiceDiscovery(listener); } catch (Exception ignored) {}
            if (ml != null && ml.isHeld()) ml.release();
        }
        return result[0];
    }

    private void sendAsync(final byte type) {
        final DataOutputStream o = out;
        if (o == null || sender.isShutdown()) return;
        sender.execute(() -> {
            try {
                Protocol.write(o, type, null);
            } catch (IOException ignored) {}
        });
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private void closeSocketAsync() {
        final Socket s = socket;
        if (s == null) return;
        new Thread(() -> {
            try { s.close(); } catch (IOException ignored) {}
        }, "parent-close").start();
    }

    private void holdReconnectLock() {
        // Pendant une coupure, on garde le processeur éveillé (30 min max)
        // pour se reconnecter vite.
        if (!reconnectLock.isHeld()) reconnectLock.acquire(30 * 60_000L);
    }

    private boolean pause(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- surveillance

    private void onHeartbeat() {
        lastHeartbeat = System.currentTimeMillis();
        scheduleWatchdog();
        if (lostNotified) {
            lostNotified = false;
            if (nm != null) nm.cancel(LOST_ID);
        }
    }

    private PendingIntent watchdogIntent() {
        Intent i = new Intent(this, WatchdogReceiver.class);
        return PendingIntent.getBroadcast(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void scheduleWatchdog() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;
        long at = SystemClock.elapsedRealtime() + HEARTBEAT_TIMEOUT_MS + 5000;
        PendingIntent pi = watchdogIntent();
        try {
            boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        }
    }

    private void cancelWatchdog() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) am.cancel(watchdogIntent());
    }

    /** Appelé par l'alarme : le bébé s'est-il tu trop longtemps ? */
    void checkWatchdog() {
        if (!running) return;
        long since = System.currentTimeMillis() - lastHeartbeat;
        if (since <= HEARTBEAT_TIMEOUT_MS) return;
        // Force une reconnexion propre (le téléphone bébé renvoie un signe
        // de vie dès qu'on se reconnecte).
        closeSocketAsync();
        if (lostNotified || graceActive) return;
        // Pas d'alerte tout de suite : on laisse le temps de se reconnecter.
        graceActive = true;
        graceLock.acquire(GRACE_MS + 10_000);
        main.postDelayed(graceCheck, GRACE_MS);
    }

    /** Fin du délai de grâce : toujours rien ? Alors on alerte. */
    private void endGrace() {
        graceActive = false;
        try {
            if (!running) return;
            long since = System.currentTimeMillis() - lastHeartbeat;
            if (since <= HEARTBEAT_TIMEOUT_MS || lostNotified) return;
            lostNotified = true;
            if (nm != null) {
                nm.notify(LOST_ID, Notifs.alert(this, "⚠️ Babyphone déconnecté",
                        "Plus de nouvelles du téléphone bébé depuis " + (since / 1000)
                                + " s. Vérifie le Wi-Fi et la batterie."));
            }
            ring();
        } finally {
            if (graceLock.isHeld()) graceLock.release();
        }
    }

    // ---------------------------------------------------------------- alertes

    private void onAlert() {
        lastAlert = System.currentTimeMillis();
        String time = new SimpleDateFormat("HH:mm", Locale.FRANCE).format(new Date(lastAlert));
        if (nm != null) {
            nm.notify(ALERT_ID, Notifs.alert(this, "👶 Bébé fait du bruit",
                    "Détecté à " + time,
                    Notifs.action(this, "Écouter", ParentService.class, ACTION_LISTEN, 20),
                    Notifs.action(this, "Couper le son", ParentService.class, ACTION_LISTEN_OFF, 21)));
        }
        ring();
        if (Prefs.autoListen(this)) {
            startListening(AUTO_LISTEN_MS);
        }
    }

    private void ring() {
        if (!Prefs.alertSound(this)) return;
        AudioAttributes alarm = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            final Ringtone r = RingtoneManager.getRingtone(this, uri);
            if (r != null) {
                r.setAudioAttributes(alarm);
                r.play();
                main.postDelayed(() -> r.stop(), 3000);
            }
        } catch (Exception ignored) {}
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createWaveform(new long[]{0, 400, 200, 400, 200, 400}, -1), alarm);
            }
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- écoute live

    void startListening(long durationMs) {
        boolean started = false;
        synchronized (audioLock) {
            long now = SystemClock.elapsedRealtime();
            listenUntil = Math.max(listenUntil, now + durationMs);
            if (!listening) {
                try {
                    track = buildTrack();
                    track.play();
                } catch (Exception e) {
                    track = null;
                    return;
                }
                listening = true;
                started = true;
                if (listenWifiLock != null) listenWifiLock.acquire();
            }
            // (Re)prend le verrou jusqu'à la fin prévue de l'écoute.
            listenLock.acquire(listenUntil - now + 60_000);
        }
        if (started) {
            sendAsync(Protocol.LISTEN_ON);
            refreshNotification();
        }
    }

    void stopListening() {
        boolean stopped = false;
        synchronized (audioLock) {
            if (listening) {
                listening = false;
                stopped = true;
                listenUntil = 0;
                if (track != null) {
                    try {
                        track.pause();
                        track.flush();
                    } catch (Exception ignored) {}
                    track.release();
                    track = null;
                }
                if (listenLock.isHeld()) listenLock.release();
                if (listenWifiLock != null && listenWifiLock.isHeld()) listenWifiLock.release();
            }
        }
        if (stopped) {
            sendAsync(Protocol.LISTEN_OFF);
            refreshNotification();
        }
    }

    private AudioTrack buildTrack() {
        int min = AudioTrack.getMinBufferSize(Protocol.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(Protocol.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(Math.max(min, Protocol.FRAME_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    // ---------------------------------------------------------------- notification d'état

    private void setStatus(String s) {
        if (s.equals(status)) return;
        status = s;
        refreshNotification();
    }

    private void refreshNotification() {
        if (!running || nm == null) return;
        String text = status + (listening ? " · 🔊 écoute en cours" : "");
        nm.notify(NOTIF_ID, statusNotification(text));
    }

    private Notification statusNotification(String text) {
        Notification.Action listenAction = listening
                ? Notifs.action(this, "Couper le son", ParentService.class, ACTION_LISTEN_OFF, 30)
                : Notifs.action(this, "Écouter", ParentService.class, ACTION_LISTEN, 31);
        return Notifs.status(this, "Babyphone — parent", text,
                listenAction,
                Notifs.action(this, "Arrêter", ParentService.class, ACTION_STOP, 32));
    }
}
