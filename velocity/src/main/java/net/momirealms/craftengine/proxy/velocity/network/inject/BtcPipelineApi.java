package net.momirealms.craftengine.proxy.velocity.network.inject;

import com.btcvelocity.api.connection.ClientChannelHook;
import com.btcvelocity.api.connection.ConnectionChannel;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.channel.Channel;
import net.momirealms.craftengine.proxy.common.util.ReflectionUtils;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * The official pipeline hook of BTCVelocity ({@code api} 0.4+). Only the methods below name
 * BTCVelocity types, and they are only called once {@link #available()} said yes, so the plugin
 * still starts on a proxy without them, where {@link PacketPipelineInjector} falls back to reflection.
 */
final class BtcPipelineApi {
    private static final String HOOK_CLASS_NAME = "com.btcvelocity.api.connection.ClientChannelHook";
    private static final String ADD_METHOD = "addClientChannelHook";
    private static final String REMOVE_METHOD = "removeClientChannelHook";

    private BtcPipelineApi() {
    }

    static boolean available() {
        return hasHookMethods(ProxyServer.class);
    }

    // 只检查类不够: 早期 BTCVelocity 构建有 ClientChannelHook 但方法签名不同 (无 plugin 参数)
    static boolean hasHookMethods(Class<?> serverType) {
        Class<?> hookType = ReflectionUtils.getClazz(HOOK_CLASS_NAME);
        if (hookType == null) {
            return false;
        }
        try {
            serverType.getMethod(ADD_METHOD, Object.class, hookType);
            serverType.getMethod(REMOVE_METHOD, Object.class, hookType);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    // returns the registered hook as an Object, so that callers never name a BTCVelocity type
    static Object register(ProxyServer server, Object plugin, Consumer<Channel> attach) {
        ClientChannelHook hook = attach::accept;
        server.addClientChannelHook(plugin, hook);
        return hook;
    }

    static void unregister(ProxyServer server, Object plugin, Object hook) {
        server.removeClientChannelHook(plugin, (ClientChannelHook) hook);
    }

    @Nullable
    static Channel channelOf(Player player) {
        return ConnectionChannel.of(player).orElse(null);
    }
}
