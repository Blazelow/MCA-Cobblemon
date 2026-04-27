package net.villagedex.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resource.ResourceType;
import net.villagedex.data.VillageDexDataLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VillageDexClient implements ClientModInitializer {

    public static final String MOD_ID = "villagedex";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("Village Dex initializing...");

        // Register the datapack loader for catalogue groups and building overrides
        ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new VillageDexDataLoader());

        LOGGER.info("Village Dex ready.");
    }
}
