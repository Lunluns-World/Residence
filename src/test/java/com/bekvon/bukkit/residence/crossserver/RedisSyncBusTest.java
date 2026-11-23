package com.bekvon.bukkit.residence.crossserver;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

/**
 * RedisSyncBus 跨服匯流排單元測試：
 * 驗證離線／連不上時的靜默回退機制，以及透過本機 Mock RESP2 伺服器的 Pub/Sub 收發與自身回聲過濾。
 */
class RedisSyncBusTest {

    @Test
    void unreachableHostFallsBackGracefullyWithoutThrowing() {
        // 選用極可能未監聽的本機通訊埠，確認不會拋例外且 isConnected 回傳 false
        RedisSyncBus bus = new RedisSyncBus(
                Logger.getLogger("RedisTest"), true, "127.0.0.1", 1, "", 0, "res:", 500, "srv-1");
        bus.start();
        assertDoesNotThrow(() -> bus.publish(RedisSyncBus.TOPIC_AUCTION, "{\"test\":1}"));
        assertFalse(bus.isConnected());
        assertDoesNotThrow(bus::stop);
    }

    @Test
    void disabledBusIsCompletelyNoOp() {
        RedisSyncBus bus = new RedisSyncBus(
                Logger.getLogger("RedisTest"), false, "localhost", 6379, "", 0, "res:", 500, "srv-1");
        bus.start();
        assertFalse(bus.isEnabled());
        assertFalse(bus.isConnected());
        assertDoesNotThrow(() -> bus.publish(RedisSyncBus.TOPIC_RESIDENCE_INDEX, "srv-1"));
        bus.stop();
    }

    @Test
    void mockResp2PubSubDeliversRemoteMessagesAndFiltersSelfEcho() throws Exception {
        CountDownLatch receivedRemote = new CountDownLatch(1);
        List<String> receivedTopics = new CopyOnWriteArrayList<>();
        List<String> receivedPayloads = new CopyOnWriteArrayList<>();

        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread mockRedis = new Thread(() -> {
                try (Socket client = server.accept()) {
                    BufferedInputStream in = new BufferedInputStream(client.getInputStream());
                    OutputStream out = client.getOutputStream();
                    // 讀取客戶端送出的 SUBSCRIBE 指令（簡易消耗第一個陣列）
                    byte[] buf = new byte[1024];
                    int n = in.read(buf);
                    if (n <= 0) {
                        return;
                    }
                    // 模擬 Redis 推送兩則訊息：第一則為本機 srv-1 回聲（應被過濾），第二則為遠端 srv-2（應送達）
                    writeRespMessage(out, "residence:auction", "srv-1|{\"action\":\"SELF\"}");
                    writeRespMessage(out, "residence:auction", "srv-2|{\"action\":\"BID\",\"id\":42}");
                    out.flush();
                    Thread.sleep(300);
                } catch (Exception ignored) {
                }
            }, "Mock-RESP2-Server");
            mockRedis.setDaemon(true);
            mockRedis.start();

            RedisSyncBus bus = new RedisSyncBus(
                    Logger.getLogger("RedisTest"), true, "127.0.0.1", port, "", 0, "residence:", 1500, "srv-1");
            bus.addListener((topic, payload) -> {
                receivedTopics.add(topic);
                receivedPayloads.add(payload);
                receivedRemote.countDown();
            });
            bus.start();

            assertTrue(receivedRemote.await(3, TimeUnit.SECONDS), "應於 3 秒內收到遠端分流推送事件");
            bus.stop();
        }

        assertEquals(1, receivedTopics.size(), "本機發出的訊息回聲應被自動過濾");
        assertEquals(RedisSyncBus.TOPIC_AUCTION, receivedTopics.get(0));
        assertEquals("{\"action\":\"BID\",\"id\":42}", receivedPayloads.get(0));
    }

    private static void writeRespMessage(OutputStream out, String channel, String payload) throws IOException {
        byte[] chBytes = channel.getBytes(StandardCharsets.UTF_8);
        byte[] plBytes = payload.getBytes(StandardCharsets.UTF_8);
        String header = "*3\r\n$7\r\nmessage\r\n$" + chBytes.length + "\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(chBytes);
        out.write(("\r\n$" + plBytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(plBytes);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
