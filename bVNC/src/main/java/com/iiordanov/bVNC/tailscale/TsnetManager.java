package com.iiordanov.bVNC.tailscale;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tsnet.Tsnet_;

/**
 * Userspace Tailscale (tsnet.aar) manager and local TCP loopback forwarder for aRDP.
 * Runs entirely in userspace without Android VpnService so system VPNs (e.g. Happ) remain active.
 */
public final class TsnetManager {
    private static final String TAG = "TsnetManager";
    private static final String PREFS_NAME = "ardp_tailscale_prefs";
    private static final String KEY_AUTH_KEY = "tailscale_auth_key";
    private static final String KEY_HOSTNAME = "tailscale_hostname";
    private static final String KEY_CONTROL_URL = "tailscale_control_url";
    private static final String KEY_LAST_PEER_IP = "tailscale_last_peer_ip";
    private static final String KEY_LAST_PEER_NAME = "tailscale_last_peer_name";

    private static TsnetManager sInstance;

    public static synchronized TsnetManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new TsnetManager(context.getApplicationContext());
        }
        return sInstance;
    }

    public static final class PeerInfo {
        public final String id;
        public final String name;
        public final String ip;

        public PeerInfo(String id, String name, String ip) {
            this.id = id != null ? id : "";
            this.name = name != null ? name : "";
            this.ip = ip != null ? ip : "";
        }

        @Override
        public String toString() {
            if (name.isEmpty()) {
                return ip;
            }
            return name + " (" + ip + ")";
        }
    }

    public static final class AccountStatus {
        public final boolean running;
        public final String state;
        public final String authUrl;
        public final String login;
        public final List<PeerInfo> peers;

        public AccountStatus(boolean running, String state, String authUrl, String login, List<PeerInfo> peers) {
            this.running = running;
            this.state = state != null ? state : "";
            this.authUrl = authUrl != null ? authUrl : "";
            this.login = login != null ? login : "";
            this.peers = peers != null ? peers : Collections.emptyList();
        }
    }

    private final Context appContext;
    private final SharedPreferences prefs;

    private Tsnet_ accountTsnet;
    private Tsnet_ authKeyTsnet;
    private boolean authKeyRunning = false;
    private String startedAuthKey = "";
    private String startedHostname = "";
    private String startedControlUrl = "";

    private ServerSocket activeForwardServer;
    private volatile boolean warmUpStarted = false;

    private TsnetManager(Context appContext) {
        this.appContext = appContext;
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Pre-warms userspace Tailscale in the background when credentials/state already exist
     * so connecting from the tile grid does not wait on cold control-plane/DERP bootstrap.
     */
    public void warmUpInBackground() {
        if (warmUpStarted) {
            return;
        }
        warmUpStarted = true;
        Thread warmThread = new Thread(() -> {
            try {
                if (!getAuthKey().isEmpty()) {
                    ensureAuthKeyStarted();
                } else {
                    File stateFile = new File(getBaseStateDir("tsnet-account"), "tailscaled.state");
                    if (stateFile.exists() && stateFile.length() > 0) {
                        pollAccountStatus(true);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Background tsnet warm-up skipped/failed: " + e.getMessage());
            }
        }, "TsnetWarmUp");
        warmThread.setDaemon(true);
        warmThread.start();
    }

    public String getAuthKey() {
        return prefs.getString(KEY_AUTH_KEY, "");
    }

    public void setAuthKey(String authKey) {
        prefs.edit().putString(KEY_AUTH_KEY, authKey != null ? authKey.trim() : "").apply();
    }

    public String getHostname() {
        String h = prefs.getString(KEY_HOSTNAME, "ardp-mobile");
        return (h == null || h.trim().isEmpty()) ? "ardp-mobile" : h.trim();
    }

    public void setHostname(String hostname) {
        String clean = (hostname == null || hostname.trim().isEmpty()) ? "ardp-mobile" : hostname.trim();
        prefs.edit().putString(KEY_HOSTNAME, clean).apply();
    }

    public String getControlUrl() {
        return prefs.getString(KEY_CONTROL_URL, "");
    }

    public void setControlUrl(String controlUrl) {
        prefs.edit().putString(KEY_CONTROL_URL, controlUrl != null ? controlUrl.trim() : "").apply();
    }

    public String getLastPeerIp() {
        return prefs.getString(KEY_LAST_PEER_IP, "");
    }

    public void setLastPeerIp(String ip) {
        prefs.edit().putString(KEY_LAST_PEER_IP, ip != null ? ip.trim() : "").apply();
    }

    public String getLastPeerName() {
        return prefs.getString(KEY_LAST_PEER_NAME, "");
    }

    public void setLastPeerName(String name) {
        prefs.edit().putString(KEY_LAST_PEER_NAME, name != null ? name.trim() : "").apply();
    }

    private File getBaseStateDir(String subDir) {
        File root = appContext.getNoBackupFilesDir();
        if (root == null) {
            root = appContext.getFilesDir();
        }
        File dir = new File(root, subDir);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private synchronized Tsnet_ ensureAccountTsnet() {
        if (accountTsnet == null) {
            accountTsnet = new Tsnet_();
        }
        return accountTsnet;
    }

    public synchronized AccountStatus pollAccountStatus(boolean begin) throws Exception {
        Tsnet_ ts = ensureAccountTsnet();
        JSONObject payload = new JSONObject();
        payload.put("Hostname", getHostname());
        payload.put("StateDir", getBaseStateDir("tsnet-account").getAbsolutePath());

        String raw = ts.account(begin ? "begin" : "status", payload.toString());
        if (raw == null || raw.isEmpty()) {
            return new AccountStatus(false, "Unknown", "", "", Collections.emptyList());
        }
        JSONObject obj = new JSONObject(raw);
        boolean running = obj.optBoolean("running", false);
        String state = obj.optString("state", "");
        String authUrl = obj.optString("authUrl", "");
        String login = obj.optString("login", "");
        List<PeerInfo> peers = new ArrayList<>();
        JSONArray peersArr = obj.optJSONArray("peers");
        if (peersArr != null) {
            for (int i = 0; i < peersArr.length(); i++) {
                JSONObject p = peersArr.optJSONObject(i);
                if (p != null) {
                    peers.add(new PeerInfo(
                            p.optString("id", ""),
                            p.optString("name", ""),
                            p.optString("ip", "")
                    ));
                }
            }
        }
        return new AccountStatus(running, state, authUrl, login, peers);
    }

    public synchronized void logoutAccount() throws Exception {
        if (accountTsnet == null) {
            return;
        }
        JSONObject payload = new JSONObject();
        payload.put("Hostname", getHostname());
        payload.put("StateDir", getBaseStateDir("tsnet-account").getAbsolutePath());
        try {
            accountTsnet.account("logout", payload.toString());
        } finally {
            try {
                accountTsnet.stop();
            } catch (Exception ignored) {
            }
            accountTsnet = null;
        }
    }

    public synchronized void ensureAuthKeyStarted() throws Exception {
        String authKey = getAuthKey();
        if (authKey.isEmpty()) {
            throw new IllegalStateException("Tailscale Auth Key не задан.");
        }
        String hostname = getHostname();
        String controlUrl = getControlUrl();
        if (authKeyRunning && authKeyTsnet != null
                && authKey.equals(startedAuthKey)
                && hostname.equals(startedHostname)
                && controlUrl.equals(startedControlUrl)) {
            return;
        }
        if (authKeyTsnet != null) {
            try {
                authKeyTsnet.stop();
            } catch (Exception ignored) {
            }
            authKeyTsnet = null;
            authKeyRunning = false;
        }
        Tsnet_ ts = new Tsnet_();
        String stateDir = getBaseStateDir("tsnet-authkey").getAbsolutePath();
        ts.start(authKey, hostname, controlUrl, stateDir, 0L);
        authKeyTsnet = ts;
        authKeyRunning = true;
        startedAuthKey = authKey;
        startedHostname = hostname;
        startedControlUrl = controlUrl;
    }

    private synchronized Tsnet_ resolveActiveTsnet() throws Exception {
        String authKey = getAuthKey();
        if (!authKey.isEmpty()) {
            ensureAuthKeyStarted();
            return authKeyTsnet;
        }
        AccountStatus status = pollAccountStatus(true);
        for (int i = 0; !status.running && !"NeedsLogin".equals(status.state) && i < 60; i++) {
            Thread.sleep(500);
            status = pollAccountStatus(false);
        }
        if (!status.running) {
            if ("NeedsLogin".equals(status.state) || status.state.isEmpty()) {
                throw new IllegalStateException(
                        "Tailscale не авторизован. Войдите через браузер или укажите Auth Key в настройках подключения."
                );
            }
            throw new IOException(
                    "Не удалось подключиться к сети Tailscale (состояние: " + status.state + "). Проверьте интернет/VPN и попробуйте снова."
            );
        }
        return ensureAccountTsnet();
    }

    /**
     * Starts a local multi-connection TCP forwarder on 127.0.0.1 that bridges every incoming
     * FreeRDP TCP connection (including NLA retries and resolution changes) over userspace tsnet.
     */
    public synchronized int startForwarder(final String targetHost, final int targetPort) throws Exception {
        if (targetHost == null || targetHost.trim().isEmpty()) {
            throw new IllegalArgumentException("Не указан Tailscale IP / Hostname целевого ПК.");
        }
        final String cleanHost = targetHost.trim();
        final Tsnet_ activeTsnet = resolveActiveTsnet();

        stopForwarder();

        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        int preferredPort = 33000 + Math.floorMod((cleanHost + ":" + targetPort).hashCode(), 20000);
        ServerSocket boundSocket;
        try {
            boundSocket = new ServerSocket();
            boundSocket.setReuseAddress(true);
            boundSocket.bind(new InetSocketAddress(loopback, preferredPort), 50);
        } catch (IOException e) {
            boundSocket = new ServerSocket(0, 50, loopback);
        }
        final ServerSocket serverSocket = boundSocket;
        activeForwardServer = serverSocket;
        final int localPort = serverSocket.getLocalPort();
        Log.i(TAG, "Started local tsnet RDP forwarder 127.0.0.1:" + localPort + " -> " + cleanHost + ":" + targetPort);

        Thread acceptThread = new Thread(() -> {
            while (!serverSocket.isClosed()) {
                try {
                    final Socket clientSocket = serverSocket.accept();
                    clientSocket.setTcpNoDelay(true);
                    Thread bridgeThread = new Thread(() -> bridgeConnection(activeTsnet, cleanHost, targetPort, clientSocket),
                            "TsnetRdpBridge");
                    bridgeThread.setDaemon(true);
                    bridgeThread.start();
                } catch (IOException e) {
                    if (!serverSocket.isClosed()) {
                        Log.e(TAG, "Forwarder accept error", e);
                    }
                    break;
                }
            }
        }, "TsnetRdpAccept-" + localPort);
        acceptThread.setDaemon(true);
        acceptThread.start();

        return localPort;
    }

    private void bridgeConnection(Tsnet_ ts, String targetHost, int targetPort, Socket clientSocket) {
        Socket upstreamSocket = null;
        try {
            String endpoint;
            try {
                endpoint = ts.dialLoopback(targetHost, (long) targetPort);
            } catch (Exception firstErr) {
                Log.w(TAG, "First tsnet dialLoopback failed, retrying in 1.5s: " + firstErr.getMessage());
                Thread.sleep(1500);
                endpoint = ts.dialLoopback(targetHost, (long) targetPort);
            }
            if (endpoint == null || !endpoint.contains(":")) {
                throw new IOException("tsnet dialLoopback returned invalid endpoint: " + endpoint);
            }
            int colon = endpoint.lastIndexOf(':');
            int tsLoopPort = Integer.parseInt(endpoint.substring(colon + 1));
            upstreamSocket = new Socket("127.0.0.1", tsLoopPort);
            upstreamSocket.setTcpNoDelay(true);

            final Socket up = upstreamSocket;
            final Socket down = clientSocket;

            Thread upToDown = new Thread(() -> {
                try {
                    pipe(up.getInputStream(), down.getOutputStream());
                } catch (IOException ignored) {
                } finally {
                    closeQuietly(up);
                    closeQuietly(down);
                }
            }, "TsnetUpToDown");
            upToDown.setDaemon(true);
            upToDown.start();

            pipe(down.getInputStream(), up.getOutputStream());
        } catch (Exception e) {
            Log.e(TAG, "tsnet bridge failed for " + targetHost + ":" + targetPort, e);
        } finally {
            closeQuietly(upstreamSocket);
            closeQuietly(clientSocket);
        }
    }

    private static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            out.flush();
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    public synchronized void stopForwarder() {
        if (activeForwardServer != null) {
            try {
                activeForwardServer.close();
            } catch (IOException ignored) {
            }
            activeForwardServer = null;
        }
    }
}
