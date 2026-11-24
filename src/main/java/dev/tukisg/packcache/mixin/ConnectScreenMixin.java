package dev.tukisg.packcache.mixin;

import dev.tukisg.packcache.BloodStonePackCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
    @Shadow
    protected abstract void connect(Minecraft client, ServerAddress address, ServerData server, TransferState transfer);

    @Inject(method = "connect", at = @At("HEAD"), cancellable = true)
    private void bloodstone$selectServerResources(Minecraft client, ServerAddress address, ServerData server,
                                                  TransferState transfer, CallbackInfo callback) {
        var startup = BloodStonePackCache.startup();
        if (startup != null && startup.connectTo(server.ip)) {
            callback.cancel();
            client.reloadResourcePacks().thenRunAsync(() -> {
                if (client.screen == (Object) this) connect(client, address, server, transfer);
            }, client);
        }
    }
}
