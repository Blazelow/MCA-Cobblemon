package net.villagedex.client.mixin;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.villagedex.client.VillageDexClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minimal mixin — only intercepts setPage("catalog") for now.
 * Rendering will be added once we confirm MCA's method names.
 */
@Pseudo
@Mixin(targets = "net.conczin.mca.client.gui.BlueprintScreen")
public abstract class BlueprintScreenMixin extends Screen {

    @Shadow(remap = false) private String page;
    @Shadow(remap = false) protected abstract void setPage(String page);

    @Unique private boolean villagedex$redirecting = false;

    protected BlueprintScreenMixin() {
        super(Text.empty());
    }

    @Inject(method = "setPage", remap = false, at = @At("HEAD"), cancellable = true)
    private void villagedex$interceptCatalog(String pageName, CallbackInfo ci) {
        if (!"catalog".equals(pageName) || villagedex$redirecting) return;
        VillageDexClient.LOGGER.info("VillageDex: intercepted catalog page, redirecting...");
        // For now just log — rendering comes in the next step
        // TODO: redirect to custom Village Dex page
    }
}
