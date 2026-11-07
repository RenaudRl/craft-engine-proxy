package net.momirealms.craftengine.proxy.velocity.network.inject;

import io.netty.channel.Channel;
import com.velocitypowered.api.proxy.Player;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import net.momirealms.craftengine.proxy.common.network.ChannelConnection;
import net.momirealms.craftengine.proxy.common.network.packet.PacketSink;
import net.momirealms.craftengine.proxy.common.util.ReflectionUtils;
import net.momirealms.craftengine.proxy.velocity.VelocityCraftEngine;
import net.momirealms.craftengine.proxy.velocity.reflection.velocitypowered.proxy.connection.MinecraftConnectionProxy;
import net.momirealms.craftengine.proxy.velocity.reflection.velocitypowered.proxy.connection.client.ConnectedPlayerProxy;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class PacketPipelineInjector {
    private static final String MINECRAFT_DECODER = "minecraft-decoder";
    private static final String MINECRAFT_ENCODER = "minecraft-encoder";
    private static final String PACKET_DECODER = "craftengine_proxy_packet_decoder";
    private static final String PACKET_ENCODER = "craftengine_proxy_packet_encoder";
    private static final String CONNECTION_MANAGER_CLASS_NAME = "com.velocitypowered.proxy.network.ConnectionManager";
    private static final String SERVER_INITIALIZER_HOLDER_CLASS_NAME = "com.velocitypowered.proxy.network.ServerChannelInitializerHolder";
    private static final String COMPRESSION_STATE_CLASS_NAME = "com.btcvelocity.api.connection.CompressionState"; // BTCVelocity api 0.4+
    private static final String VELOCITY_CONNECTION_EVENT_CLASS_NAME = "com.velocitypowered.proxy.protocol.VelocityConnectionEvent"; // 内部类, 其他 Velocity
    // BTCVelocity 同时触发两个事件: 有公开事件时只认它, 避免依赖内部类
    private static final boolean PUBLIC_COMPRESSION_EVENT = ReflectionUtils.classExists(COMPRESSION_STATE_CLASS_NAME);

    private final VelocityCraftEngine plugin;
    private final PacketSink packetSink; // raw ByteBuf 捕获回调
    private final Consumer<ChannelConnection> connectionRegisterer; // 新 Channel 注册回调
    private final Consumer<ChannelConnection> connectionUnregister; // Channel 关闭清理回调
    private volatile boolean officialHook; // BTCVelocity 提供官方 pipeline hook 时不再使用反射; 注册失败 (LinkageError) 后回退为 false
    private volatile boolean injected; // initializer 是否处于注入状态
    private volatile Object registeredHook; // 已注册的官方 hook, 仅 officialHook 为 true 时有值

    public PacketPipelineInjector(
            VelocityCraftEngine plugin,
            PacketSink packetSink,
            Consumer<ChannelConnection> connectionRegisterer,
            Consumer<ChannelConnection> connectionUnregister
    ) {
        this(plugin, packetSink, connectionRegisterer, connectionUnregister, BtcPipelineApi.available());
    }

    PacketPipelineInjector(
            VelocityCraftEngine plugin,
            PacketSink packetSink,
            Consumer<ChannelConnection> connectionRegisterer,
            Consumer<ChannelConnection> connectionUnregister,
            boolean officialHook
    ) {
        this.plugin = plugin;
        this.packetSink = packetSink;
        this.connectionRegisterer = connectionRegisterer;
        this.connectionUnregister = connectionUnregister;
        this.officialHook = officialHook;
    }

    // 注入服务端 channel initializer
    @SuppressWarnings("unchecked")
    public void inject() {
        if (this.officialHook) {
            // BTCVelocity: 官方 hook 在 Velocity 自己的 pipeline 构建完成后回调, 与下面的包装方式位置一致
            try {
                if (this.registeredHook == null) {
                    this.registeredHook = BtcPipelineApi.register(this.plugin.server, this.plugin, this::attach);
                }
                this.injected = true;
                return;
            } catch (LinkageError e) {
                // 代理的 API 与编译时不一致 (NoSuchMethodError 等): 回退到反射注入
                this.plugin.logger.warn("BTCVelocity pipeline hook unusable, falling back to reflection", e);
                this.officialHook = false;
            }
        }
        try {
            Object holder = this.serverInitializerHolder();
            Supplier<io.netty.channel.ChannelInitializer<Channel>> supplier = (Supplier<io.netty.channel.ChannelInitializer<Channel>>) holder;
            io.netty.channel.ChannelInitializer<Channel> current = supplier.get();
            if (current instanceof ChannelInitializer initializer) {
                // 如果注入的目标和当前一致, 则代表是重复注入, 忽略.
                if (initializer.belongsTo(this)) {
                    this.injected = true;
                    return;
                }
                // 如果已有其他 CraftEngine wrapper, 取回原始 initializer 后再包装
                current = initializer.wrappedInitializer();
            }
            // 注入
            Field initializerField = ReflectionUtils.getDeclaredField(holder.getClass(), io.netty.channel.ChannelInitializer.class, 0);
            Objects.requireNonNull(initializerField).set(holder, new ChannelInitializer(this, current));
            this.injected = true;
        } catch (ReflectiveOperationException | ClassCastException | NullPointerException e) {
            this.plugin.logger.warn("Failed to inject CraftEngine Velocity packet capture service", e);
        }
    }

    // 撤销注入的 channel initializer.
    @SuppressWarnings("unchecked")
    public void uninject() {
        this.injected = false;
        if (this.officialHook) {
            Object hook = this.registeredHook;
            this.registeredHook = null;
            if (hook != null) {
                BtcPipelineApi.unregister(this.plugin.server, this.plugin, hook);
            }
            return;
        }
        try {
            Object holder = this.serverInitializerHolder();
            Supplier<io.netty.channel.ChannelInitializer<Channel>> supplier = (Supplier<io.netty.channel.ChannelInitializer<Channel>>) holder;
            io.netty.channel.ChannelInitializer<Channel> current = supplier.get();
            if (!(current instanceof ChannelInitializer initializer) || !initializer.belongsTo(this)) {
                return;
            }
            // 撤销注入
            Field initializerField = ReflectionUtils.getDeclaredField(holder.getClass(), io.netty.channel.ChannelInitializer.class, 0);
            Objects.requireNonNull(initializerField).set(holder, initializer.wrappedInitializer());
        } catch (ReflectiveOperationException | ClassCastException | NullPointerException e) {
            this.plugin.logger.warn("Failed to uninject CraftEngine Velocity packet capture service", e);
        }
    }

    boolean injected() {
        return this.injected;
    }

    boolean officialHook() {
        return this.officialHook;
    }

    // 新 Channel 的 Velocity pipeline 构建完成后, 绑定连接状态并添加数据包 handler
    void attach(Channel channel) {
        if (!this.injected) {
            return;
        }
        // 连接状态从 channel 创建时开始记录, 后续再绑定到 ProxyPlayer
        ChannelConnection connection = new ChannelConnection(channel, VelocityCraftEngine.instance().packetListenerManager());
        // 先挂关闭监听再注册: addTo 失败时 (代理会移除已加的 handler) 连接关闭仍会清理注册
        channel.closeFuture().addListener((ChannelFutureListener) future -> this.connectionUnregister.accept(connection));
        this.connectionRegisterer.accept(connection);
        addTo(channel, this.packetSink, connection);
    }

    // 玩家登录后找到其 Netty channel: 官方 API, 或在旧版 Velocity 上通过反射
    @Nullable
    public Channel channelOf(Player player) {
        if (this.officialHook) {
            return BtcPipelineApi.channelOf(player);
        }
        Object connection = ConnectedPlayerProxy.INSTANCE.getConnection(player);
        return MinecraftConnectionProxy.INSTANCE.getChannel(connection);
    }

    // 获取 ServerChannelInitializerHolder
    private Object serverInitializerHolder() throws ReflectiveOperationException {
        Class<?> connectionManagerClass = ReflectionUtils.getClazz(CONNECTION_MANAGER_CLASS_NAME);
        Class<?> initializerHolderClass = ReflectionUtils.getClazz(SERVER_INITIALIZER_HOLDER_CLASS_NAME);
        if (connectionManagerClass == null || initializerHolderClass == null) {
            throw new ClassNotFoundException("Velocity connection manager or server initializer holder is unavailable");
        }

        Field connectionManagerField = ReflectionUtils.getDeclaredField(this.plugin.server, connectionManagerClass, 0);
        if (connectionManagerField == null) {
            throw new NoSuchFieldException("No field of type " + connectionManagerClass.getName() + " found in " + this.plugin.server.getClass().getName());
        }

        Object connectionManager = connectionManagerField.get(this.plugin.server);
        Field serverInitializerHolderField = ReflectionUtils.getDeclaredField(connectionManager, initializerHolderClass, 0);
        if (serverInitializerHolderField == null) {
            throw new NoSuchFieldException("No field of type " + initializerHolderClass.getName() + " found in " + connectionManager.getClass().getName());
        }
        return serverInitializerHolderField.get(connectionManager);
    }

    // 将数据包捕获 handler 添加到 Velocity Minecraft codec handler 之前
    public static void addTo(Channel channel, PacketSink packetSink, ChannelConnection connection) {
        ChannelPipeline pipeline = channel.pipeline();
        removeHandlers(channel);

        if (pipeline.get(MINECRAFT_DECODER) != null) {
            pipeline.addBefore(MINECRAFT_DECODER, PACKET_DECODER, new PacketDecoder(packetSink, connection));
        }
        if (pipeline.get(MINECRAFT_ENCODER) != null) {
            pipeline.addBefore(MINECRAFT_ENCODER, PACKET_ENCODER, new PacketEncoder(packetSink, connection));
        }
    }

    // 从已注入的 channel 中移除 handler
    public static void removeHandlers(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(PACKET_DECODER) != null) {
            pipeline.remove(PACKET_DECODER);
        }
        if (pipeline.get(PACKET_ENCODER) != null) {
            pipeline.remove(PACKET_ENCODER);
        }
    }

    // 检查 Velocity 是否启用了压缩包: BTCVelocity 的公开事件, 其他 Velocity 上回退到内部事件类名
    public static boolean isCompressionEnabledEvent(Object event) {
        if (!(event instanceof Enum<?> value)) {
            return false;
        }
        String type = value.getClass().getName();
        return PUBLIC_COMPRESSION_EVENT
                ? type.equals(COMPRESSION_STATE_CLASS_NAME) && value.name().equals("ENABLED")
                : type.equals(VELOCITY_CONNECTION_EVENT_CLASS_NAME) && value.name().equals("COMPRESSION_ENABLED");
    }

    // 启用压缩并改变 pipeline 后, 将数据包 handler 移回 Velocity codec 之前
    public static void relocate(ChannelPipeline pipeline) {
        relocate(pipeline, MINECRAFT_ENCODER, PACKET_ENCODER);
        relocate(pipeline, MINECRAFT_DECODER, PACKET_DECODER);
    }

    // 保留同一个 handler 实例, 并将其重新添加到目标 handler 之前
    private static void relocate(ChannelPipeline pipeline, String target, String handlerName) {
        ChannelHandler handler = pipeline.get(handlerName);
        if (handler != null && pipeline.get(target) != null) {
            pipeline.remove(handlerName);
            pipeline.addBefore(target, handlerName, handler);
        }
    }
}
