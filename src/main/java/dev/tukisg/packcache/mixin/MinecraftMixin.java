package dev.tukisg.packcache.mixin;

import dev.tukisg.packcache.BloodStonePackCache;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "onResourceLoadFinished", at = @At("HEAD"))
    private void bloodstone$recordAppliedPacks(CallbackInfo callback) {
        var startup = BloodStonePackCache.startup();
        if (startup != null) startup.completeResourceLoad();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void bloodstone$restoreMenuResources(CallbackInfo callback) {
        var startup = BloodStonePackCache.startup();
        if (startup != null) startup.menuTick((Minecraft) (Object) this);
    }
}
