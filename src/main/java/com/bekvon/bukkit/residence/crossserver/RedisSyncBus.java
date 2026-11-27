package com.bekvon.bukkit.residence.crossserver;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 零外部依賴的輕量級 RESP2 Redis Pub/Sub 跨服即時匯流排。
 * 若 Redis 未啟用、無法連線或途中斷線，會靜默退回 MariaDB 輪詢，並在背景嘗試重連，不影響任何核心功能。
 */
public class RedisSyncBus {

    public static final String TOPIC_RESIDENCE_INDEX = "index";
    public static final String TOPIC_PLAYER_LIMITS = "limits";
    public static final String TOPIC_CLEANUP_RECLAIM = "reclaim";
    public static final String TOPIC_AUCTION = "auction";

    private final Logger logger;
    private final boolean enabled;
    private final String host;
    private final int port;
    private final String password;
    private final int database;
    private final String channelPrefix;
    private final int timeoutMs;
    private final String localServerId;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean warnedOffline = new AtomicBoolean(false);
    private final List<BiConsumer<String, String>> listeners = new CopyOnWriteArrayList<>();

    private volatile Socket subSocket;
    private volatile Thread subThread;
    private ExecutorService publishExecutor;

    public RedisSyncBus(
            Logger logger,
            boolean enabled,
            String host,
            int port,
            String password,
            int database,
            String channelPrefix,
            int timeoutMs,
            String localServerId) {
        this.logger = logger != null ? logger : Logger.getLogger("ResidenceRedis");
        this.enabled = enabled;
        this.host = (host == null || host.trim().isEmpty()) ? "localhost" : host.trim();
        this.port = port <= 0 ? 6379 : port;
        this.password = password == null ? "" : password;
        this.database = Math.max(0, database);
        String pfx = (channelPrefix == null || channelPrefix.trim().isEmpty()) ? "residence:" : channelPrefix.trim();
        this.channelPrefix = pfx.endsWith(":") ? pfx : (pfx + ":");
        this.timeoutMs = Math.max(500, timeoutMs);
        this.localServerId = localServerId == null ? "default" : localServerId.trim().toLowerCase();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isConnected() {
        return enabled && connected.get();
    }

    public String getChannelName(String topic) {
        return channelPrefix + topic;
    }

    public void addListener(BiConsumer<String, String> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public synchronized void start() {
        if (!enabled || running.get()) {
            return;
        }
        running.set(true);
        publishExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Residence-Redis-Publisher");
            t.setDaemon(true);
            return t;
        });
        subThread = new Thread(this::subscriberLoop, "Residence-Redis-Subscriber");
        subThread.setDaemon(true);
        subThread.start();
    }

    public synchronized void stop() {
        running.set(false);
        connected.set(false);
        closeQuietly(subSocket);
        subSocket = null;
        if (subThread != null) {
            subThread.interrupt();
            subThread = null;
        }
        if (publishExecutor != null) {
            publishExecutor.shutdownNow();
            try {
                publishExecutor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            publishExecutor = null;
        }
    }

    public void publish(String topic, String payload) {
        publishAsync(topic, payload);
    }

    /**
     * 非同步發佈事件至指定主題。若 Redis 未啟用或暫時斷線則靜默略過（由 MariaDB 輪詢保底）。
     */
    public void publishAsync(String topic, String payload) {
        if (!enabled || !running.get() || topic == null || payload == null) {
            return;
        }
        ExecutorService exec = publishExecutor;
        if (exec == null || exec.isShutdown()) {
            return;
        }
        String channel = getChannelName(topic);
        String wrapped = localServerId + "|" + payload;
        exec.submit(() -> publishNow(channel, wrapped));
    }

    /**
     * 同步發佈訊息（供測試或關服前呼叫）。回傳是否成功送達 Redis。
     */
    public boolean publishSync(String topic, String payload) {
        if (!enabled || topic == null || payload == null) {
            return false;
        }
        return publishNow(getChannelName(topic), localServerId + "|" + payload);
    }

    private boolean publishNow(String channel, String message) {
        try (Socket sock = openAuthenticatedSocket(timeoutMs)) {
            OutputStream out = sock.getOutputStream();
            BufferedInputStream in = new BufferedInputStream(sock.getInputStream());
            writeCommand(out, "PUBLISH", channel, message);
            out.flush();
            Object resp = readResp(in);
            return resp != null;
        } catch (Exception ex) {
            connected.set(false);
            if (warnedOffline.compareAndSet(false, true)) {
                logger.log(Level.WARNING, "[Redis] 無法發佈訊息至 Redis (" + host + ":" + port + ")，已自動回退 MariaDB 輪詢：" + ex.getMessage());
            }
            return false;
        }
    }

    private void subscriberLoop() {
        long backoffMs = 2000L;
        while (running.get()) {
            Socket sock = null;
            try {
                // 訂閱連線不設短讀逾時（設 0 阻塞等推播，靠 TCP keepalive 偵測斷線）
                sock = openAuthenticatedSocket(0);
                sock.setKeepAlive(true);
                subSocket = sock;

                OutputStream out = sock.getOutputStream();
                BufferedInputStream in = new BufferedInputStream(sock.getInputStream());
                writeCommand(out, "SUBSCRIBE",
                        getChannelName(TOPIC_RESIDENCE_INDEX),
                        getChannelName(TOPIC_PLAYER_LIMITS),
                        getChannelName(TOPIC_CLEANUP_RECLAIM),
                        getChannelName(TOPIC_AUCTION));
                out.flush();

                connected.set(true);
                if (warnedOffline.getAndSet(false)) {
                    logger.info("[Redis] 已重新連線至 Redis (" + host + ":" + port + ")，恢復即時跨服推播。");
                } else {
                    logger.info("[Redis] 已連線至 Redis (" + host + ":" + port + ")，啟用即時跨服同步。");
                }
                backoffMs = 2000L;

                while (running.get()) {
                    Object frame = readResp(in);
                    if (!(frame instanceof List<?>)) {
                        continue;
                    }
                    List<?> arr = (List<?>) frame;
                    if (arr.size() >= 3 && "message".equalsIgnoreCase(String.valueOf(arr.get(0)))) {
                        String fullChannel = String.valueOf(arr.get(1));
                        String rawPayload = String.valueOf(arr.get(2));
                        dispatchMessage(fullChannel, rawPayload);
                    }
                }
            } catch (Exception ex) {
                connected.set(false);
                if (running.get()) {
                    if (warnedOffline.compareAndSet(false, true)) {
                        logger.warning("[Redis] 無法連線或已斷線 (" + host + ":" + port + ")，已自動回退 MariaDB 輪詢並將於背景重連：" + ex.getMessage());
                    }
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    backoffMs = Math.min(15000L, backoffMs * 2);
                }
            } finally {
                closeQuietly(sock);
                if (subSocket == sock) {
                    subSocket = null;
                }
            }
        }
    }

    private void dispatchMessage(String fullChannel, String rawPayload) {
        if (fullChannel == null || rawPayload == null) {
            return;
        }
        String topic = fullChannel.startsWith(channelPrefix)
                ? fullChannel.substring(channelPrefix.length())
                : fullChannel;

        int sep = rawPayload.indexOf('|');
        String originServer = sep > 0 ? rawPayload.substring(0, sep) : "";
        String payload = sep > 0 ? rawPayload.substring(sep + 1) : rawPayload;

        // 跳過自己發出的同步事件（除非明確標記為需要本服也處理的廣播）
        if (originServer.equalsIgnoreCase(localServerId) && !payload.startsWith("BROADCAST:")) {
            return;
        }

        for (BiConsumer<String, String> listener : listeners) {
            try {
                listener.accept(topic, payload);
            } catch (Throwable t) {
                logger.log(Level.WARNING, "[Redis] 處理主題 " + topic + " 推播訊息時發生例外", t);
            }
        }
    }

    private Socket openAuthenticatedSocket(int readTimeoutMs) throws IOException {
        Socket sock = new Socket();
        sock.connect(new InetSocketAddress(host, port), timeoutMs);
        sock.setSoTimeout(timeoutMs);
        OutputStream out = sock.getOutputStream();
        BufferedInputStream in = new BufferedInputStream(sock.getInputStream());

        if (!password.isEmpty()) {
            writeCommand(out, "AUTH", password);
            out.flush();
            Object authResp = readResp(in);
            if (authResp instanceof RespError) {
                sock.close();
                throw new IOException("Redis AUTH 失敗: " + ((RespError) authResp).message);
            }
        }
        if (database > 0) {
            writeCommand(out, "SELECT", String.valueOf(database));
            out.flush();
            Object selResp = readResp(in);
            if (selResp instanceof RespError) {
                sock.close();
                throw new IOException("Redis SELECT " + database + " 失敗: " + ((RespError) selResp).message);
            }
        }
        sock.setSoTimeout(readTimeoutMs);
        return sock;
    }

    private static void closeQuietly(Socket sock) {
        if (sock != null) {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ===== RESP2 協定編解碼（供單元測試與內部使用） =====

    static void writeCommand(OutputStream out, String... args) throws IOException {
        StringBuilder header = new StringBuilder();
        header.append('*').append(args.length).append("\r\n");
        out.write(header.toString().getBytes(StandardCharsets.UTF_8));
        for (String arg : args) {
            byte[] bytes = (arg == null ? "" : arg).getBytes(StandardCharsets.UTF_8);
            out.write(('$' + String.valueOf(bytes.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    static Object readResp(InputStream in) throws IOException {
        int prefix = in.read();
        if (prefix == -1) {
            throw new EOFException("Redis 連線已關閉");
        }
        switch (prefix) {
        case '+':
            return readLine(in);
        case '-':
            return new RespError(readLine(in));
        case ':':
            return Long.parseLong(readLine(in));
        case '$': {
            int len = Integer.parseInt(readLine(in));
            if (len < 0) {
                return null;
            }
            byte[] buf = new byte[len];
            int offset = 0;
            while (offset < len) {
                int r = in.read(buf, offset, len - offset);
                if (r == -1) {
                    throw new EOFException("讀取 RESP bulk string 時連線中斷");
                }
                offset += r;
            }
            // 消耗結尾 \r\n
            readLine(in);
            return new String(buf, StandardCharsets.UTF_8);
        }
        case '*': {
            int count = Integer.parseInt(readLine(in));
            if (count < 0) {
                return null;
            }
            List<Object> items = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                items.add(readResp(in));
            }
            return items;
        }
        default:
            throw new IOException("不支援的 RESP 回應前綴: " + (char) prefix);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
        int prev = -1;
        while (true) {
            int cur = in.read();
            if (cur == -1) {
                throw new EOFException("讀取 RESP 行結尾時連線中斷");
            }
            if (prev == '\r' && cur == '\n') {
                byte[] raw = baos.toByteArray();
                return new String(raw, 0, Math.max(0, raw.length - 1), StandardCharsets.UTF_8);
            }
            baos.write(cur);
            prev = cur;
        }
    }

    static final class RespError {
        final String message;

        RespError(String message) {
            this.message = message;
        }
    }
}
