package dev.tukisg.packcache.mixin;

import dev.tukisg.packcache.BloodStonePackCache;
import java.net.Proxy;
import java.util.concurrent.Executor;
import net.minecraft.client.User;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.resources.server.PackDownloader;
import net.minecraft.server.packs.DownloadQueue;
import net.minecraft.server.packs.repository.RepositorySource;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DownloadedPackSource.class)
public abstract class DownloadedPackSourceMixin {
    @Inject(method = "createDownloader", at = @At("RETURN"), cancellable = true)
    private void bloodstone$cacheDownloads(DownloadQueue queue, Executor executor, User user, Proxy proxy,
                                           CallbackInfoReturnable<PackDownloader> callback) {
        callback.setReturnValue(BloodStonePackCache.wrap(callback.getReturnValue(), executor, user, proxy));
    }

    @Inject(method = "createRepositorySource", at = @At("RETURN"), cancellable = true)
    private void bloodstone$startupSource(CallbackInfoReturnable<RepositorySource> callback) {
        RepositorySource original = callback.getReturnValue();
        callback.setReturnValue(consumer -> {
            var startup = BloodStonePackCache.startup();
            if (startup == null) original.loadPacks(consumer);
            else startup.source(original).loadPacks(consumer);
        });
    }

    @Redirect(method = "startReload", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> bloodstone$reuseAppliedResources(Minecraft client) {
        var startup = BloodStonePackCache.startup();
        if (startup != null && startup.resourcesAlreadyApplied(client)) {
            ((DownloadedPackSource) (Object) this).onReloadSuccess();
            return CompletableFuture.completedFuture(null);
        }
        return client.reloadResourcePacks();
    }

    @Inject(method = {"onRecovery", "onRecoveryFailure"}, at = @At("HEAD"))
    private void bloodstone$disableFailedPreload(CallbackInfo callback) {
        var startup = BloodStonePackCache.startup();
        if (startup != null) startup.recovery();
    }
}
