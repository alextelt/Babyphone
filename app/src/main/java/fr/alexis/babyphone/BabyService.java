package fr.alexis.babyphone;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Mode « téléphone bébé » : écoute le micro en continu (écran éteint possible),
 * détecte les pleurs et prévient le(s) téléphone(s) parent connecté(s).
 */
public class BabyService extends Service {
    static final String ACTION_STOP = "fr.alexis.babyphone.BABY_STOP";
    private static final int NOTIF_ID = 1;

    /** Fenêtre d'analyse : 15 trames de 100 ms = 1,5 s. */
    private static final int WINDOW_FRAMES = 15;
    /** Il faut au moins 5 trames bruyantes (0,5 s) dans la fenêtre pour alerter. */
    private static final int TRIGGER_FRAMES = 5;
    /** Pas plus d'une alerte toutes les 30 s. */
    private static final long COOLDOWN_MS = 30_000;
    private static final long HEARTBEAT_MS = 15_000;

    // État lu par l'écran de l'appli.
    static volatile boolean running = false;
    static volatile int level = 0;
    static volatile int threshold = 55;
    static volatile int clientCount = 0;
    static volatile long lastAlert = 0;
    static volatile String error = null;

    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private volatile ServerSocket server;
    private Thread audioThread;
    private Thread serverThread;
    private Thread heartbeatThread;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private NsdManager nsd;
    private NsdManager.RegistrationListener nsdListener;
    private long lastAlertElapsed = -COOLDOWN_MS;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) {
            return START_STICKY;
        }

        Notifs.createChannels(this);
        Notification n = Notifs.status(this, "Babyphone actif",
                "Le micro écoute bébé…",
                Notifs.action(this, "Arrêter", BabyService.class, ACTION_STOP, 10));
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            error = "Impossible de démarrer l'écoute en arrière-plan : relance depuis l'appli.";
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        error = null;
        threshold = Prefs.threshold(this);
        acquireLocks();
        startServer();
        startAudio();
        startHeartbeat();
        registerNsd();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        level = 0;
        if (nsd != null && nsdListener != null) {
            try { nsd.unregisterService(nsdListener); } catch (Exception ignored) {}
        }
        ServerSocket s = server;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
        for (Client c : clients) {
            c.close();
        }
        clients.clear();
        clientCount = 0;
        if (heartbeatThread != null) heartbeatThread.interrupt();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- verrous

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "babyphone:baby");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "babyphone:baby");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
    }

    // ---------------------------------------------------------------- réseau

    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(Protocol.PORT));
                server = ss;
                while (running) {
                    Socket socket = ss.accept();
                    try {
                        Client c = new Client(socket);
                        clients.add(c);
                        clientCount = clients.size();
                        c.start();
                        c.send(Protocol.HEARTBEAT, Protocol.utf8(heartbeatText()), true);
                    } catch (IOException e) {
                        try { socket.close(); } catch (IOException ignored) {}
                    }
                }
            } catch (IOException e) {
                if (running) {
                    error = "Réseau indisponible (" + e.getMessage() + ")";
                }
            }
        }, "baby-server");
        serverThread.start();
    }

    private void startHeartbeat() {
        heartbeatThread = new Thread(() -> {
            while (running) {
                byte[] hb = Protocol.utf8(heartbeatText());
                for (Client c : clients) {
                    c.send(Protocol.HEARTBEAT, hb, true);
                }
                try {
                    Thread.sleep(HEARTBEAT_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "baby-heartbeat");
        heartbeatThread.start();
    }

    /** "niveauBatterie,enCharge" */
    private String heartbeatText() {
        int pct = -1;
        boolean charging = false;
        try {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (l >= 0 && scale > 0) pct = l * 100 / scale;
                charging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            }
        } catch (Exception ignored) {}
        return pct + "," + (charging ? 1 : 0);
    }

    private void registerNsd() {
        nsd = (NsdManager) getSystemService(NSD_SERVICE);
        if (nsd == null) return;
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(Protocol.NSD_NAME);
        info.setServiceType(Protocol.NSD_TYPE);
        info.setPort(Protocol.PORT);
        nsdListener = new NsdManager.RegistrationListener() {
            @Override public void onRegistrationFailed(NsdServiceInfo s, int code) {}
            @Override public void onUnregistrationFailed(NsdServiceInfo s, int code) {}
            @Override public void onServiceRegistered(NsdServiceInfo s) {}
            @Override public void onServiceUnregistered(NsdServiceInfo s) {}
        };
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (Exception e) {
            nsdListener = null;
        }
    }

    // ---------------------------------------------------------------- audio

    private void startAudio() {
        audioThread = new Thread(() -> audioLoop(), "baby-audio");
        audioThread.start();
    }

    private void audioLoop() {
        int min = AudioRecord.getMinBufferSize(Protocol.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(min, Protocol.FRAME_BYTES * 4);
        AudioRecord recorder;
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, Protocol.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        } catch (SecurityException e) {
            error = "Accès au micro refusé.";
            stopSelf();
            return;
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            error = "Micro indisponible (utilisé par une autre appli ?).";
            recorder.release();
            stopSelf();
            return;
        }

        boolean[] window = new boolean[WINDOW_FRAMES];
        int idx = 0;
        int loud = 0;
        byte[] frame = new byte[Protocol.FRAME_BYTES];

        try {
            recorder.startRecording();
            while (running) {
                int n = readFully(recorder, frame);
                if (n <= 0) {
                    if (n < 0) {
                        error = "Erreur de lecture du micro (" + n + ").";
                        break;
                    }
                    continue;
                }
                int lvl = levelOf(frame, n);
                level = lvl;

                boolean isLoud = lvl >= threshold;
                if (window[idx]) loud--;
                window[idx] = isLoud;
                if (isLoud) loud++;
                idx = (idx + 1) % WINDOW_FRAMES;

                long now = SystemClock.elapsedRealtime();
                if (loud >= TRIGGER_FRAMES && now - lastAlertElapsed > COOLDOWN_MS) {
                    lastAlertElapsed = now;
                    lastAlert = System.currentTimeMillis();
                    byte[] payload = Protocol.utf8(String.valueOf(lvl));
                    for (Client c : clients) {
                        c.send(Protocol.ALERT, payload, true);
                    }
                }

                byte[] copy = null;
                for (Client c : clients) {
                    if (c.listening) {
                        if (copy == null) copy = Arrays.copyOf(frame, n);
                        c.send(Protocol.AUDIO, copy, false);
                    }
                }
            }
        } catch (Exception e) {
            error = "Erreur micro : " + e.getMessage();
        } finally {
            try { recorder.stop(); } catch (Exception ignored) {}
            recorder.release();
            level = 0;
        }
        if (running) {
            stopSelf();
        }
    }

    private static int readFully(AudioRecord r, byte[] buf) {
        int off = 0;
        while (off < buf.length) {
            int n = r.read(buf, off, buf.length - off);
            if (n < 0) return n;
            if (n == 0) break;
            off += n;
        }
        return off;
    }

    /** Niveau 0..100 (−80 dBFS → 0, 0 dBFS → 100). */
    static int levelOf(byte[] b, int len) {
        int samples = len / 2;
        if (samples == 0) return 0;
        long sum = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            int s = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
            sum += (long) s * s;
        }
        double rms = Math.sqrt((double) sum / samples);
        double db = 20.0 * Math.log10(Math.max(rms, 1.0) / 32768.0);
        int lvl = (int) Math.round((db + 80.0) * 100.0 / 80.0);
        return Math.max(0, Math.min(100, lvl));
    }

    // ---------------------------------------------------------------- client

    /** Un téléphone parent connecté. */
    private final class Client {
        final Socket socket;
        final DataOutputStream out;
        final DataInputStream in;
        final ArrayBlockingQueue<Protocol.Frame> queue = new ArrayBlockingQueue<>(64);
        volatile boolean listening = false;
        private boolean alive = true;

        Client(Socket s) throws IOException {
            socket = s;
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        }

        void start() {
            new Thread(() -> writeLoop(), "baby-client-w").start();
            new Thread(() -> readLoop(), "baby-client-r").start();
        }

        void send(byte type, byte[] data, boolean important) {
            Protocol.Frame f = new Protocol.Frame(type, data);
            if (!queue.offer(f) && important) {
                queue.clear();
                queue.offer(f);
            }
        }

        private void writeLoop() {
            try {
                while (isAlive()) {
                    Protocol.Frame f = queue.poll(5, TimeUnit.SECONDS);
                    if (f != null) {
                        Protocol.write(out, f.type, f.data);
                    }
                }
            } catch (Exception ignored) {
            }
            close();
        }

        private void readLoop() {
            try {
                while (isAlive()) {
                    Protocol.Frame f = Protocol.read(in);
                    if (f.type == Protocol.LISTEN_ON) {
                        listening = true;
                    } else if (f.type == Protocol.LISTEN_OFF) {
                        listening = false;
                    }
                }
            } catch (Exception ignored) {
            }
            close();
        }

        synchronized boolean isAlive() {
            return alive;
        }

        synchronized void close() {
            if (!alive) return;
            alive = false;
            listening = false;
            try { socket.close(); } catch (IOException ignored) {}
            clients.remove(this);
            clientCount = clients.size();
        }
    }
}
