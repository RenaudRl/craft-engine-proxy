package net.momirealms.craftengine.proxy.velocity.network.inject;

import com.btcvelocity.api.connection.ClientChannelHook;
import com.btcvelocity.api.connection.CompressionState;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.momirealms.craftengine.proxy.common.network.ChannelConnection;
import net.momirealms.craftengine.proxy.velocity.VelocityCraftEngine;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * BTC : on BTCVelocity 0.4+ CraftEngine adds its handlers through the official pipeline hook, and
 * falls back to reflecting on the proxy when the hook is missing or does not link.
 */
class PacketPipelineInjectorTest {

    private final ProxyServer server = mock(ProxyServer.class);
    private final Logger logger = mock(Logger.class);
    private final VelocityCraftEngine plugin =
            new VelocityCraftEngine(this.server, this.logger, mock(PluginContainer.class), Path.of("."));

    /** The signature of the hook before the review, without the owner plugin. */
    interface ProxyWithoutOwner {
        void addClientChannelHook(ClientChannelHook hook);

        void removeClientChannelHook(ClientChannelHook hook);
    }

    @Test
    void theHookIsDetectedByItsMethodsNotJustItsClass() {
        assertTrue(BtcPipelineApi.hasHookMethods(ProxyServer.class));
        assertFalse(BtcPipelineApi.hasHookMethods(ProxyWithoutOwner.class));
        assertFalse(BtcPipelineApi.hasHookMethods(Object.class));
        assertTrue(BtcPipelineApi.available());
    }

    @Test
    void theOfficialPathRegistersTheHookOwnedByThePluginAndRemovesItAgain() {
        PacketPipelineInjector injector = injector(true, new ArrayList<>(), new ArrayList<>());

        injector.inject();
        injector.inject(); // a second inject must not register twice

        ArgumentCaptor<ClientChannelHook> hook = ArgumentCaptor.forClass(ClientChannelHook.class);
        verify(this.server).addClientChannelHook(eq(this.plugin), hook.capture());
        assertTrue(injector.injected());
        assertTrue(injector.officialHook());

        injector.uninject();

        verify(this.server).removeClientChannelHook(eq(this.plugin), eq(hook.getValue()));
        assertFalse(injector.injected());
    }

    @Test
    void aHookThatDoesNotLinkFallsBackToReflection() {
        doThrow(new NoSuchMethodError("addClientChannelHook")).when(this.server).addClientChannelHook(any(), any());
        PacketPipelineInjector injector = injector(true, new ArrayList<>(), new ArrayList<>());

        injector.inject();

        assertFalse(injector.officialHook(), "the reflection path takes over");
        verify(this.logger).warn(contains("falling back to reflection"), any(NoSuchMethodError.class));
        // no Velocity internals here: the reflection path then reports its own failure
        verify(this.logger).warn(contains("Failed to inject"), any(Throwable.class));
        assertFalse(injector.injected());
    }

    @Test
    void withoutTheHookTheReflectionPathIsUsedAndTheApiNeverCalled() {
        PacketPipelineInjector injector = injector(false, new ArrayList<>(), new ArrayList<>());

        injector.inject();

        verify(this.server, never()).addClientChannelHook(any(), any());
        verify(this.logger).warn(contains("Failed to inject"), any(Throwable.class));
        assertFalse(injector.officialHook());
    }

    @Test
    void aConnectionIsUnregisteredOnCloseEvenWhenAddingTheHandlersFails() {
        List<ChannelConnection> registered = new ArrayList<>();
        List<ChannelConnection> unregistered = new ArrayList<>();
        PacketPipelineInjector injector = injector(true, registered, unregistered);
        injector.inject();
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("minecraft-decoder", new ChannelInboundHandlerAdapter());

        // the decoder reads the proxy configuration, which this mocked server does not have
        assertThrows(NullPointerException.class, () -> injector.attach(channel));
        channel.close();

        assertEquals(1, registered.size());
        assertEquals(registered, unregistered);
        assertSame(channel, registered.get(0).channel());
    }

    @Test
    void thePublicCompressionEventIsTheOneUsedOnBtcVelocity() {
        assertTrue(PacketPipelineInjector.isCompressionEnabledEvent(CompressionState.ENABLED));
        assertFalse(PacketPipelineInjector.isCompressionEnabledEvent(CompressionState.DISABLED));
        assertFalse(PacketPipelineInjector.isCompressionEnabledEvent("ENABLED"));
    }

    private PacketPipelineInjector injector(boolean officialHook, List<ChannelConnection> registered,
                                            List<ChannelConnection> unregistered) {
        return new PacketPipelineInjector(this.plugin, (connection, player, side, buffer) -> buffer,
                registered::add, unregistered::add, officialHook);
    }
}
