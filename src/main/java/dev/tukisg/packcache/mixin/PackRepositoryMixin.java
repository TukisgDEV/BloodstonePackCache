package dev.tukisg.packcache.mixin;

import dev.tukisg.packcache.BloodStonePackCache;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PackRepository.class)
public abstract class PackRepositoryMixin {
    @Inject(method = "openAllSelected", at = @At("RETURN"))
    private void bloodstone$recordOpenedPacks(CallbackInfoReturnable<List<PackResources>> callback) {
        var startup = BloodStonePackCache.startup();
        var client = Minecraft.getInstance();
        if (startup != null && client != null && client.getResourcePackRepository() == (Object) this) {
            var repository = (PackRepository) (Object) this;
            startup.beginResourceLoad(repository.getSelectedPacks().stream().map(Pack::getId).toList());
        }
    }
}
