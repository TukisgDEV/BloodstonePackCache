package dev.tukisg.packcache.mixin;

import dev.tukisg.packcache.BloodStonePackCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class HandshakeMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private ServerData serverData;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bloodstone$checkConnectionScope(CallbackInfo callback) {
        minecraft.execute(() -> {
            var startup = BloodStonePackCache.startup();
            if (startup != null && startup.connectTo(serverData == null ? null : serverData.ip)) minecraft.reloadResourcePacks();
        });
    }
}
