package net.momirealms.craftengine.proxy.velocity.network.inject;

import io.netty.channel.Channel;
import net.momirealms.craftengine.proxy.common.util.ReflectionUtils;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;

final class ChannelInitializer extends io.netty.channel.ChannelInitializer<Channel> {
    private static volatile Method initChannelMethod;

    private final PacketPipelineInjector injector;
    private final io.netty.channel.ChannelInitializer<Channel> wrappedInitializer;

    ChannelInitializer(
            PacketPipelineInjector injector,
            io.netty.channel.ChannelInitializer<Channel> wrappedInitializer
    ) {
        this.injector = injector;
        this.wrappedInitializer = wrappedInitializer;
    }

    @Override
    protected void initChannel(@NotNull Channel channel) throws Exception {
        // 先让 Velocity 完成自己的 pipeline 构建, 再添加自定义的 handler
        this.invokeWrappedInitializer(channel);
        this.injector.attach(channel);
    }

    boolean belongsTo(PacketPipelineInjector injector) {
        return this.injector == injector;
    }

    io.netty.channel.ChannelInitializer<Channel> wrappedInitializer() {
        return this.wrappedInitializer;
    }

    // 调用 Velocity 原始 protected initializer 上的 ChannelInitializer#initChannel
    private void invokeWrappedInitializer(Channel channel) throws Exception {
        Method method = initChannelMethod;
        if (method == null) {
            method = ReflectionUtils.setAccessible(io.netty.channel.ChannelInitializer.class.getDeclaredMethod("initChannel", Channel.class));
            initChannelMethod = method;
        }
        method.invoke(this.wrappedInitializer, channel);
    }
}
