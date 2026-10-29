package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 使用实际请求和缓存路径验证好友计数，不连接 QQ。 */
public final class FriendListInitializationTest {
    public static void main(String[] args) throws Exception {
        OnebotChannel channel = new OnebotChannel("FriendListFixture");
        try {
            var initialize = OnebotChannel.class.getDeclaredMethod("initializeLoginAccount", JsonObject.class);
            initialize.setAccessible(true);
            JsonObject self = JsonParser.parseString("{\"user_id\":12345,\"nickname\":\"bot\"}").getAsJsonObject();
            initialize.invoke(channel, self);
            String friends = "[{\"user_id\":11111,\"nickname\":\"first\"},"
                    + "{\"user_id\":22222,\"nickname\":\"second\",\"sex\":null}]";
            AtomicInteger calls = respondWith(channel, ok(friends));
            check(loadFriends(channel) == 2 && calls.get() == 1, "nonempty list counted without retry");
            check(channel.getSession("user/11111").getName().equals("first"), "friend cached for session lookup");
            check(((OnebotChannel.QQAccount) channel.getSession("user/22222")).getSex().equals("unknown"),
                    "missing and null optional sex accepted");
            check(channel.cachedSessions.size() == 3, "self cache entry is not counted as a friend");

            calls = respondWith(channel, ok("[]"));
            check(loadFriends(channel) == 0 && calls.get() == 1, "empty result is accepted without retry");

            calls = respondWith(channel, failed());
            check(loadFriends(channel) == -1 && calls.get() == 1, "failed list is optional and never retried");

            calls = respondWith(channel, ok("null"));
            check(loadFriends(channel) == -1 && calls.get() == 1, "invalid response reports unknown instead of zero");

            calls = respondWith(channel, ok("[{\"user_id\":12345,\"nickname\":\"cached bot\",\"sex\":\"female\"}]"));
            check(loadFriends(channel) == 1 && calls.get() == 1, "self in friend response counted exactly once");
            initialize.invoke(channel, self);
            check(channel.getSession("user/12345") == channel.loginAccount, "self cache retained after lists load");
            check(channel.loginAccount.getSex().equals("female") && channel.loginAccount.getName().equals("bot"),
                    "friend profile retained and login nickname refreshed");

            var connection = OnebotChannel.class.getDeclaredMethod("initializeConnection", OBResponse.class);
            connection.setAccessible(true);
            // 使用内存中的颜色，避免头像网络请求。
            channel.cachedSessions.put("12345", channel.new QQAccount() {
                @Override public java.awt.Color getColor() { return java.awt.Color.WHITE; }
            });
            calls = respondWith(channel, failed(), ok("[{\"group_id\":33333,\"group_name\":\"group\"}]"));
            connection.invoke(channel, ok(self.toString()));
            check(calls.get() == 2 && channel.loginAccount != null, "friend failure does not abort login or group loading");
            check(channel.getSession("group/33333").getName().equals("group"), "group cache populated after friend failure");

            calls = respondWith(channel, ok(friends), failed());
            connection.invoke(channel, ok(self.toString()));
            check(calls.get() == 2 && channel.getSession("user/11111").getName().equals("first"),
                    "group failure does not abort login or friend loading");

            calls = respondWith(channel, failed(), failed());
            connection.invoke(channel, ok(self.toString()));
            check(calls.get() == 2 && channel.getSession("user/12345") == channel.loginAccount,
                    "both optional list failures leave self available");

            calls = respondWith(channel, (OBResponse) null);
            check(loadFriends(channel) == -1 && calls.get() == 1, "missing response times out without retry");
            check(channel.syncActions.isEmpty(), "all offline requests completed");
            System.out.println("FriendListInitializationTest passed");
        } finally {
            channel.onMsgEx.shutdownNow();
        }
    }

    private static int loadFriends(OnebotChannel channel) throws Exception {
        var method = OnebotChannel.class.getDeclaredMethod("loadFriends");
        method.setAccessible(true);
        try {
            return (int) method.invoke(channel);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw e;
        }
    }

    private static OBResponse ok(String data) {
        OBResponse response = new OBResponse();
        response.status = "ok";
        response.data = JsonParser.parseString(data);
        return response;
    }

    private static OBResponse failed() {
        OBResponse response = new OBResponse();
        response.status = "failed";
        response.retcode = 1200;
        response.message = "Cannot read properties of null (reading 'coreInfo')";
        return response;
    }

    private static AtomicInteger respondWith(OnebotChannel channel, OBResponse... responses) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var callbackMethod = Class.forName(OnebotChannel.class.getName() + "$Callback")
                .getDeclaredMethod("callback", OBResponse.class);
        callbackMethod.setAccessible(true);
        channel.wsConnection = new WebSocketClient(URI.create("ws://example.invalid/offline")) {
            @Override public boolean isOpen() { return true; }
            @Override public void send(String text) {
                var request = JsonParser.parseString(text).getAsJsonObject();
                String action = request.get("action").getAsString();
                check(action.equals("get_friend_list") || action.equals("get_group_list"), "only optional lists queried");
                int index = calls.getAndIncrement();
                check(index < responses.length, "no excess retries");
                if (responses[index] == null) return;
                Object callback = ((Map<?, ?>) channel.syncActions).remove(request.get("echo").getAsString());
                try { callbackMethod.invoke(callback, responses[index]); }
                catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            }
            @Override public void onOpen(ServerHandshake handshake) {}
            @Override public void onMessage(String text) {}
            @Override public void onClose(int code, String reason, boolean remote) {}
            @Override public void onError(Exception error) {}
        };
        return calls;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
