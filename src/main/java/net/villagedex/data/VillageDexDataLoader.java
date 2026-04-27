package net.villagedex.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resource.JsonDataLoader;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;
import net.minecraft.util.profiler.Profiler;
import net.villagedex.client.VillageDexClient;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Reads from data/<namespace>/villagedex/catalog/:
 *   groups/<id>.json  → defines a tab in the Village Dex UI
 *   buildings/<id>.json → per-building overrides (nodeItem, hide)
 */
public class VillageDexDataLoader extends JsonDataLoader implements IdentifiableResourceReloadListener {

    private static final Gson GSON = new Gson();
    private static final String DIRECTORY = "villagedex/catalog";

    public record TabGroup(String id, String label, String matchPrefix, int priority) {}

    public record BuildingOverride(Optional<Identifier> nodeItem, boolean hide) {
        public static final BuildingOverride EMPTY = new BuildingOverride(Optional.empty(), false);
    }

    private static final List<TabGroup> GROUPS = new CopyOnWriteArrayList<>();
    private static final Map<String, BuildingOverride> OVERRIDES = Collections.synchronizedMap(new LinkedHashMap<>());

    public VillageDexDataLoader() {
        super(GSON, DIRECTORY);
    }

    @Override
    public Identifier getFabricId() {
        return Identifier.of(VillageDexClient.MOD_ID, "catalog_loader");
    }

    @Override
    protected void apply(Map<Identifier, JsonElement> entries, ResourceManager manager, Profiler profiler) {
        GROUPS.clear();
        synchronized (OVERRIDES) {
            OVERRIDES.clear();
        }

        for (Map.Entry<Identifier, JsonElement> entry : entries.entrySet()) {
            Identifier location = entry.getKey();
            String path = location.getPath();
            try {
                JsonObject json = JsonHelper.asObject(entry.getValue(), "catalog entry");
                if (path.startsWith("groups/")) {
                    String groupId = location.getNamespace() + ":" + path.substring("groups/".length());
                    loadGroup(groupId, json);
                } else if (path.startsWith("buildings/")) {
                    String buildingType = path.substring("buildings/".length());
                    loadBuildingOverride(buildingType, json);
                }
            } catch (Exception ex) {
                VillageDexClient.LOGGER.warn("Rejected catalog entry '{}': {}", location, ex.getMessage());
            }
        }

        GROUPS.sort(Comparator.comparingInt(TabGroup::priority).reversed()
                .thenComparingInt(g -> -g.matchPrefix().length()));

        VillageDexClient.LOGGER.info("VillageDex reload: {} groups, {} building overrides", GROUPS.size(), OVERRIDES.size());
    }

    private static void loadGroup(String id, JsonObject json) {
        String label = JsonHelper.getString(json, "label");
        String matchPrefix = JsonHelper.getString(json, "match_prefix", "");
        int priority = JsonHelper.getInt(json, "priority", 0);
        GROUPS.add(new TabGroup(id, label, matchPrefix, priority));
    }

    private static void loadBuildingOverride(String buildingType, JsonObject json) {
        Optional<Identifier> nodeItem = Optional.empty();
        if (json.has("node_item")) {
            Identifier parsed = Identifier.tryParse(JsonHelper.getString(json, "node_item"));
            if (parsed != null) nodeItem = Optional.of(parsed);
        }
        boolean hide = JsonHelper.getBoolean(json, "hide", false);
        synchronized (OVERRIDES) {
            OVERRIDES.put(buildingType, new BuildingOverride(nodeItem, hide));
        }
    }

    // --- Static accessors used by the Mixin ---

    public static List<TabGroup> getGroups() {
        return Collections.unmodifiableList(GROUPS);
    }

    public static BuildingOverride getOverride(String buildingType) {
        synchronized (OVERRIDES) {
            return OVERRIDES.getOrDefault(buildingType, BuildingOverride.EMPTY);
        }
    }

    /**
     * Returns the tab label that best matches the given building type name,
     * based on which group's matchPrefix the name starts with.
     */
    public static String resolveTab(String buildingTypeName) {
        for (TabGroup group : GROUPS) {
            if (!group.matchPrefix().isEmpty() && buildingTypeName.startsWith(group.matchPrefix())) {
                return group.label();
            }
        }
        // Fallback: derive from path prefix cobblemon/ -> "Cobblemon", etc.
        if (buildingTypeName.startsWith("cobblemon/")) {
            String sub = buildingTypeName.substring("cobblemon/".length());
            String[] parts = sub.split("/");
            return capitalize(parts[0]);
        }
        return "General";
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
