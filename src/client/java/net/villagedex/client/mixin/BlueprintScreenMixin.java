package net.villagedex.client.mixin;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.villagedex.client.VillageDexClient;
import net.villagedex.data.VillageDexDataLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Injects into MCA Reborn's BlueprintScreen using a string target so we don't
 * need MCA on the compile classpath. MCA is still required at runtime — enforced
 * by fabric.mod.json "depends": { "mca": "*" }.
 */
@Pseudo
@Mixin(targets = "net.conczin.mca.client.gui.BlueprintScreen")
public abstract class BlueprintScreenMixin extends Screen {

    // ── Shadows ──────────────────────────────────────────────────────────────
    // These field/method names must match MCA's obfuscated names exactly.
    @Shadow(remap = false) private String page;
    @Shadow(remap = false) protected abstract void setPage(String page);

    // ── Constants ────────────────────────────────────────────────────────────
    @Unique private static final String PAGE_ID = "villagedex_catalog";

    // Pokédex color palette
    @Unique private static final int COL_SHELL_TOP    = 0xFFC0001A;
    @Unique private static final int COL_BG           = 0xFF1A1A1A;
    @Unique private static final int COL_PANEL        = 0xFF0D0D0D;
    @Unique private static final int COL_LIST_BG      = 0xFF111111;
    @Unique private static final int COL_DIVIDER      = 0xFF2A2A2A;
    @Unique private static final int COL_SELECTED_BG  = 0xFF2A0A0A;
    @Unique private static final int COL_SELECTED_BAR = 0xFFFF3A3A;
    @Unique private static final int COL_TEXT_WHITE   = 0xFFFFFFFF;
    @Unique private static final int COL_TEXT_DIM     = 0xFF888888;
    @Unique private static final int COL_TEXT_GREEN   = 0xFF3DD68C;
    @Unique private static final int COL_TEXT_GOLD    = 0xFFF5A623;
    @Unique private static final int COL_TEXT_BLUE    = 0xFF9AD0FF;
    @Unique private static final int COL_DOT_BUILT    = 0xFF3DD68C;
    @Unique private static final int COL_DOT_UNBUILT  = 0xFF555555;
    @Unique private static final int COL_BADGE_MOD_BG    = 0xFF1A3A1A;
    @Unique private static final int COL_BADGE_MOD_FG    = 0xFF3DD68C;
    @Unique private static final int COL_BADGE_BUILT_BG  = 0xFF0A2A1A;
    @Unique private static final int COL_BADGE_BUILT_FG  = 0xFF3DD68C;
    @Unique private static final int COL_BADGE_UNBUILT_BG = 0xFF1A1A1A;
    @Unique private static final int COL_BADGE_UNBUILT_FG = 0xFF666666;
    @Unique private static final int COL_REQ_BG       = 0xFF1A1A1A;
    @Unique private static final int COL_REQ_BORDER   = 0xFF2A2A2A;

    // Layout
    @Unique private static final int WIN_W       = 340;
    @Unique private static final int WIN_H       = 240;
    @Unique private static final int LIST_W      = 150;
    @Unique private static final int TOP_BAR_H   = 22;
    @Unique private static final int BOTTOM_BAR_H = 18;
    @Unique private static final int ROW_H       = 18;
    @Unique private static final int DOT_SIZE    = 6;

    // ── State ─────────────────────────────────────────────────────────────────
    @Unique private boolean villagedex$active = false;
    @Unique private String  villagedex$returnPage = "map";
    @Unique private List<String> villagedex$tabs = new ArrayList<>();
    @Unique private int villagedex$activeTab = 0;
    @Unique private Map<String, List<BuildingEntry>> villagedex$byTab = new LinkedHashMap<>();
    @Unique private Set<String> villagedex$builtTypes = new HashSet<>();
    @Unique private int villagedex$selectedRow = 0;
    @Unique private long villagedex$tick = 0;

    /** Lightweight stand-in for BuildingType since we can't reference MCA classes directly. */
    @Unique
    private record BuildingEntry(String name, Map<Identifier, Integer> requirements) {}

    protected BlueprintScreenMixin() { super(Text.empty()); }

    // ── Intercept "catalog" → redirect to Village Dex ────────────────────────

    @Inject(method = "setPage", remap = false, at = @At("HEAD"), cancellable = true)
    private void villagedex$interceptCatalog(String pageName, CallbackInfo ci) {
        if (!"catalog".equals(pageName) || villagedex$active) return;

        if (this.page != null && !this.page.isBlank() && !PAGE_ID.equals(this.page)) {
            villagedex$returnPage = this.page;
        } else {
            villagedex$returnPage = "map";
        }

        villagedex$active = true;
        setPage(PAGE_ID);
        villagedex$active = false;
        ci.cancel();
    }

    @Inject(method = "setPage", remap = false, at = @At("TAIL"))
    private void villagedex$onPageSet(String pageName, CallbackInfo ci) {
        if (!PAGE_ID.equals(this.page)) return;
        villagedex$rebuild();
        villagedex$addControls();
    }

    // ── Build catalogue data ──────────────────────────────────────────────────

    @Unique
    private void villagedex$rebuild() {
        villagedex$byTab.clear();
        villagedex$builtTypes.clear();

        // Collect built building types via reflection (avoids compile dependency on MCA)
        try {
            Object village = this.getClass().getSuperclass().getDeclaredField("village").get(this);
            if (village != null) {
                Object buildings = village.getClass().getMethod("getBuildings").invoke(village);
                if (buildings instanceof Map<?,?> map) {
                    for (Object building : map.values()) {
                        String type = (String) building.getClass().getMethod("getType").invoke(building);
                        if (type != null) villagedex$builtTypes.add(type);
                    }
                }
            }
        } catch (Exception e) {
            VillageDexClient.LOGGER.warn("VillageDex: could not read village buildings: {}", e.getMessage());
        }

        // Read all building types from MCA via reflection
        try {
            Class<?> btClass = Class.forName("net.conczin.mca.resources.BuildingTypes");
            Object instance = btClass.getMethod("getInstance").invoke(null);
            Map<?, ?> buildingTypes = (Map<?, ?>) instance.getClass()
                    .getMethod("getBuildingTypes").invoke(instance);

            for (Object bt : buildingTypes.values()) {
                boolean visible = (boolean) bt.getClass().getMethod("visible").invoke(bt);
                if (!visible) continue;

                String btName = (String) bt.getClass().getMethod("name").invoke(bt);
                VillageDexDataLoader.BuildingOverride override = VillageDexDataLoader.getOverride(btName);
                if (override.hide()) continue;

                // Read requirements map
                Map<?, ?> rawGroups = (Map<?, ?>) bt.getClass().getMethod("getGroups").invoke(bt);
                Map<Identifier, Integer> requirements = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : rawGroups.entrySet()) {
                    if (entry.getKey() instanceof Identifier id) {
                        requirements.put(id, (Integer) entry.getValue());
                    }
                }

                String tab = VillageDexDataLoader.resolveTab(btName);
                villagedex$byTab.computeIfAbsent(tab, k -> new ArrayList<>())
                        .add(new BuildingEntry(btName, requirements));
            }
        } catch (Exception e) {
            VillageDexClient.LOGGER.error("VillageDex: could not load MCA building types: {}", e.getMessage());
        }

        for (List<BuildingEntry> list : villagedex$byTab.values()) {
            list.sort(Comparator.comparing(e -> villagedex$displayName(e.name())));
        }

        villagedex$tabs = new ArrayList<>(villagedex$byTab.keySet());
        villagedex$activeTab = Math.min(villagedex$activeTab, Math.max(0, villagedex$tabs.size() - 1));
        villagedex$selectedRow = 0;
    }

    @Unique
    private void villagedex$addControls() {
        int wx = villagedex$winX();
        int wy = villagedex$winY();

        addDrawableChild(
            ButtonWidget.builder(Text.literal("< Back"), b -> setPage(villagedex$returnPage))
                .dimensions(wx + 3, wy + 3, 36, 14)
                .build()
        );

        int tabX = wx + 42;
        int tabY = wy + 3;
        for (int i = 0; i < villagedex$tabs.size(); i++) {
            final int idx = i;
            String label = villagedex$tabs.get(i);
            int tabW = Math.min(60, this.textRenderer.getWidth(label) + 8);
            addDrawableChild(
                ButtonWidget.builder(Text.literal(label), b -> {
                    villagedex$activeTab = idx;
                    villagedex$selectedRow = 0;
                }).dimensions(tabX, tabY, tabW, 14).build()
            );
            tabX += tabW + 2;
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Inject(method = "render", at = @At("TAIL"))
    private void villagedex$render(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!PAGE_ID.equals(this.page)) return;
        villagedex$tick++;

        int wx  = villagedex$winX();
        int wy  = villagedex$winY();
        int wx2 = wx + WIN_W;
        int wy2 = wy + WIN_H;

        // Shell
        ctx.fill(wx, wy, wx2, wy2, COL_BG);
        ctx.fill(wx, wy, wx2, wy + TOP_BAR_H, COL_SHELL_TOP);
        ctx.fill(wx, wy2 - BOTTOM_BAR_H, wx2, wy2, COL_SHELL_TOP);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("VILLAGE DEX"),
                wx + WIN_W / 2, wy + 5, COL_TEXT_WHITE);

        // Scanlines
        int contentY  = wy + TOP_BAR_H;
        int contentY2 = wy2 - BOTTOM_BAR_H;
        for (int sy = contentY; sy < contentY2; sy += 4) {
            ctx.fill(wx, sy + 3, wx2, sy + 4, 0x12000000);
        }

        // List panel
        int listX  = wx;
        int listX2 = wx + LIST_W;
        ctx.fill(listX, contentY, listX2, contentY2, COL_LIST_BG);
        ctx.fill(listX2, contentY, listX2 + 1, contentY2, COL_DIVIDER);

        // Detail panel
        int detailX = listX2 + 1;
        ctx.fill(detailX, contentY, wx2, contentY2, COL_PANEL);

        // List rows
        List<BuildingEntry> buildings = villagedex$currentTabBuildings();
        int rowY = contentY + 2;
        for (int i = 0; i < buildings.size(); i++) {
            BuildingEntry bt = buildings.get(i);
            boolean selected = i == villagedex$selectedRow;
            boolean built    = villagedex$builtTypes.contains(bt.name());
            boolean hovered  = mouseX >= listX && mouseX < listX2
                            && mouseY >= rowY  && mouseY < rowY + ROW_H;

            if (selected) {
                ctx.fill(listX, rowY, listX2, rowY + ROW_H, COL_SELECTED_BG);
                ctx.fill(listX, rowY, listX + 2, rowY + ROW_H, COL_SELECTED_BAR);
            } else if (hovered) {
                ctx.fill(listX, rowY, listX2, rowY + ROW_H, 0xFF1A1A1A);
            }

            // Status dot
            int dotX = listX + 6;
            int dotY = rowY + (ROW_H - DOT_SIZE) / 2;
            ctx.fill(dotX, dotY, dotX + DOT_SIZE, dotY + DOT_SIZE,
                    built ? COL_DOT_BUILT : COL_DOT_UNBUILT);

            // Name
            ctx.drawTextWithShadow(this.textRenderer,
                    Text.literal(villagedex$displayName(bt.name())),
                    listX + 16, rowY + (ROW_H - 8) / 2,
                    selected ? COL_TEXT_WHITE : COL_TEXT_DIM);

            rowY += ROW_H;
        }

        // Detail panel contents
        villagedex$renderDetail(ctx, detailX, contentY, wx2, contentY2);
    }

    @Unique
    private void villagedex$renderDetail(DrawContext ctx, int x, int y, int x2, int y2) {
        List<BuildingEntry> buildings = villagedex$currentTabBuildings();
        if (buildings.isEmpty()) return;
        BuildingEntry bt = buildings.get(Math.min(villagedex$selectedRow, buildings.size() - 1));

        boolean built      = villagedex$builtTypes.contains(bt.name());
        String  statusLabel = built ? "BUILT" : "NOT BUILT";
        int     statusBg    = built ? COL_BADGE_BUILT_BG  : COL_BADGE_UNBUILT_BG;
        int     statusFg    = built ? COL_BADGE_BUILT_FG  : COL_BADGE_UNBUILT_FG;

        int px = x + 6;
        int py = y + 6;

        // Icon box
        ctx.fill(px, py, px + 32, py + 32, 0xFF1A1A1A);
        ctx.drawBorder(px, py, 32, 32, COL_DIVIDER);
        ItemStack icon = villagedex$resolveIcon(bt);
        if (!icon.isEmpty()) ctx.drawItem(icon, px + 8, py + 8);

        // Name + blinking cursor
        boolean blink = (villagedex$tick / 10) % 2 == 0;
        ctx.drawTextWithShadow(this.textRenderer,
                Text.literal(villagedex$displayName(bt.name()) + (blink ? "|" : " ")),
                px + 36, py, COL_TEXT_WHITE);

        // Badges
        int badgeY = py + 12;
        int badgeX = px + 36;
        badgeX = villagedex$drawBadge(ctx, villagedex$modBadge(bt.name()), badgeX, badgeY, COL_BADGE_MOD_BG, COL_BADGE_MOD_FG) + 3;
        villagedex$drawBadge(ctx, statusLabel, badgeX, badgeY, statusBg, statusFg);

        // Divider
        int divY = py + 42;
        ctx.fill(px, divY, x2 - 4, divY + 1, COL_DIVIDER);

        // Description
        String descKey = "buildingType." + bt.name() + ".description";
        String desc    = Text.translatable(descKey).getString();
        if (desc.equals(descKey)) desc = "A building for your village.";
        int descY  = divY + 5;
        int maxW   = x2 - px - 8;
        for (String line : villagedex$wrapText(desc, maxW)) {
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(line), px, descY, 0xFF88FFAA);
            descY += 9;
            if (descY > divY + 35) break;
        }

        // Requirements
        int reqY = divY + 44;
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("— REQUIREMENTS —"), px, reqY, COL_SELECTED_BAR);

        int chipX = px;
        int chipY = reqY + 11;
        for (Map.Entry<Identifier, Integer> req : bt.requirements().entrySet()) {
            Identifier reqId  = req.getKey();
            int        qty    = req.getValue();
            ItemStack  reqIcon = villagedex$resolveRequirementIcon(reqId);
            String     reqName = villagedex$requirementName(reqId);
            int        chipW   = this.textRenderer.getWidth(qty + "x " + reqName) + (reqIcon.isEmpty() ? 4 : 22);

            if (chipX + chipW > x2 - 4) { chipX = px; chipY += 16; }
            if (chipY > y2 - 14) break;

            ctx.fill(chipX, chipY, chipX + chipW, chipY + 13, COL_REQ_BG);
            ctx.drawBorder(chipX, chipY, chipW, 13, COL_REQ_BORDER);

            if (!reqIcon.isEmpty()) {
                ctx.drawItem(reqIcon, chipX + 2, chipY - 1);
                ctx.drawTextWithShadow(this.textRenderer,
                        Text.literal(qty + "x"), chipX + 18, chipY + 3, COL_TEXT_GOLD);
                ctx.drawTextWithShadow(this.textRenderer,
                        Text.literal(reqName),
                        chipX + 18 + this.textRenderer.getWidth(qty + "x") + 2, chipY + 3, COL_TEXT_BLUE);
            } else {
                ctx.drawTextWithShadow(this.textRenderer,
                        Text.literal(qty + "x " + reqName), chipX + 4, chipY + 3, COL_TEXT_BLUE);
            }
            chipX += chipW + 4;
        }
    }

    // ── Mouse clicks ──────────────────────────────────────────────────────────

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void villagedex$mouseClicked(double mouseX, double mouseY, int button,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (!PAGE_ID.equals(this.page) || button != 0) return;
        int contentY = villagedex$winY() + TOP_BAR_H;
        if (mouseX >= villagedex$winX() && mouseX < villagedex$winX() + LIST_W && mouseY >= contentY) {
            int rowIdx = (int)(mouseY - contentY - 2) / ROW_H;
            List<BuildingEntry> buildings = villagedex$currentTabBuildings();
            if (rowIdx >= 0 && rowIdx < buildings.size()) {
                villagedex$selectedRow = rowIdx;
                cir.setReturnValue(true);
                cir.cancel();
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Unique private int villagedex$winX() { return (this.width  - WIN_W) / 2; }
    @Unique private int villagedex$winY() { return (this.height - WIN_H) / 2; }

    @Unique
    private List<BuildingEntry> villagedex$currentTabBuildings() {
        if (villagedex$tabs.isEmpty()) return List.of();
        String tab = villagedex$tabs.get(Math.min(villagedex$activeTab, villagedex$tabs.size() - 1));
        return villagedex$byTab.getOrDefault(tab, List.of());
    }

    @Unique
    private String villagedex$displayName(String id) {
        String key        = "buildingType." + id;
        String translated = Text.translatable(key).getString();
        if (!translated.equals(key)) return translated;
        String[] parts = id.split("/");
        return Arrays.stream(parts[parts.length - 1].split("_"))
                .filter(w -> !w.isEmpty())
                .map(w -> w.substring(0, 1).toUpperCase(Locale.ROOT) + w.substring(1))
                .collect(Collectors.joining(" "));
    }

    @Unique
    private String villagedex$modBadge(String name) {
        if (name.startsWith("cobbleverse/")) return "COBBLEVERSE";
        if (name.startsWith("cobblemon/"))   return "COBBLEMON";
        return "MCA";
    }

    @Unique
    private int villagedex$drawBadge(DrawContext ctx, String text, int x, int y, int bg, int fg) {
        int w = this.textRenderer.getWidth(text) + 6;
        ctx.fill(x, y, x + w, y + 10, bg);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(text), x + 3, y + 1, fg);
        return x + w;
    }

    @Unique
    private ItemStack villagedex$resolveIcon(BuildingEntry bt) {
        Optional<Identifier> override = VillageDexDataLoader.getOverride(bt.name()).nodeItem();
        if (override.isPresent() && Registries.ITEM.containsId(override.get()))
            return new ItemStack(Registries.ITEM.get(override.get()));
        for (Identifier reqId : bt.requirements().keySet()) {
            if (Registries.BLOCK.containsId(reqId)) return new ItemStack(Registries.BLOCK.get(reqId));
            if (Registries.ITEM.containsId(reqId))  return new ItemStack(Registries.ITEM.get(reqId));
        }
        return ItemStack.EMPTY;
    }

    @Unique
    private ItemStack villagedex$resolveRequirementIcon(Identifier id) {
        if (Registries.BLOCK.containsId(id)) return new ItemStack(Registries.BLOCK.get(id));
        if (Registries.ITEM.containsId(id))  return new ItemStack(Registries.ITEM.get(id));
        return ItemStack.EMPTY;
    }

    @Unique
    private String villagedex$requirementName(Identifier id) {
        if (Registries.BLOCK.containsId(id))
            return Text.translatable(Registries.BLOCK.get(id).getTranslationKey()).getString();
        if (Registries.ITEM.containsId(id))
            return Text.translatable(Registries.ITEM.get(id).getTranslationKey()).getString();
        return Arrays.stream(id.getPath().replace('_', ' ').split(" "))
                .map(w -> w.isEmpty() ? w : w.substring(0, 1).toUpperCase(Locale.ROOT) + w.substring(1))
                .collect(Collectors.joining(" "));
    }

    @Unique
    private List<String> villagedex$wrapText(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            String test = current.isEmpty() ? word : current + " " + word;
            if (this.textRenderer.getWidth(test) > maxWidth) {
                if (!current.isEmpty()) lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(test);
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines;
    }
}
