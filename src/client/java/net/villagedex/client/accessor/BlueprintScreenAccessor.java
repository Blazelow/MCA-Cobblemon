package net.villagedex.client.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accessor for MCA Reborn's BlueprintScreen.
 * Uses string target so MCA does not need to be on the compile classpath.
 */
@Pseudo
@Mixin(targets = "net.conczin.mca.client.gui.BlueprintScreen")
public interface BlueprintScreenAccessor {

    @Invoker(value = "setPage", remap = false)
    void villagedex$invokeSetPage(String page);
}
