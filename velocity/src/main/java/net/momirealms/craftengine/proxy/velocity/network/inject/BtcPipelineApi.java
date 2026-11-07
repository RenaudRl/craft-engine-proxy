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
 * The official pipeline hook of BTCVelocity ({@code api} 0.4+). Only this class names BTCVelocity
 * types, and it is only loaded once {@link #available()} said yes, so the plugin still starts on
 * a proxy without them, where {@link PacketPipelineInjector} falls back to reflection.
 */
final class BtcPipelineApi {
    private static final String HOOK_CLASS_NAME = "com.btcvelocity.api.connection.ClientChannelHook";

    private BtcPipelineApi() {
    }

    static boolean available() {
        return ReflectionUtils.classExists(HOOK_CLASS_NAME);
    }

    // returns the registered hook as an Object, so that callers never name a BTCVelocity type
    static Object register(ProxyServer server, Consumer<Channel> attach) {
        ClientChannelHook hook = attach::accept;
        server.addClientChannelHook(hook);
        return hook;
    }

    static void unregister(ProxyServer server, Object hook) {
        server.removeClientChannelHook((ClientChannelHook) hook);
    }

    @Nullable
    static Channel channelOf(Player player) {
        return ConnectionChannel.of(player).orElse(null);
    }
}
