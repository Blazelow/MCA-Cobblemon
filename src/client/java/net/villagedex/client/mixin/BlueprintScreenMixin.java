package net.villagedex.client.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
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

@Pseudo
@Mixin(targets = "net.conczin.mca.client.gui.BlueprintScreen")
public abstract class BlueprintScreenMixin extends Screen {

    @Shadow(remap = false) private String page;
    @Shadow(remap = false) protected abstract void setPage(String page);

    // ── Color palette ─────────────────────────────────────────────────────────
    @Unique private static final int COL_SHELL      = 0xFFC0001A;
    @Unique private static final int COL_BG         = 0xFF1A1A1A;
    @Unique private static final int COL_PANEL      = 0xFF0D0D0D;
    @Unique private static final int COL_LIST_BG    = 0xFF111111;
    @Unique private static final int COL_DIVIDER    = 0xFF2A2A2A;
    @Unique private static final int COL_SEL_BG     = 0xFF2A0A0A;
    @Unique private static final int COL_SEL_BAR    = 0xFFFF3A3A;
    @Unique private static final int COL_WHITE      = 0xFFFFFFFF;
    @Unique private static final int COL_DIM        = 0xFF888888;
    @Unique private static final int COL_GREEN      = 0xFF3DD68C;
    @Unique private static final int COL_GOLD       = 0xFFF5A623;
    @Unique private static final int COL_BLUE       = 0xFF9AD0FF;
    @Unique private static final int COL_LCD        = 0xFF88FFAA;
    @Unique private static final int COL_DOT_ON     = 0xFF3DD68C;
    @Unique private static final int COL_DOT_OFF    = 0xFF555555;
    @Unique private static final int COL_MOD_BG     = 0xFF1A3A1A;
    @Unique private static final int COL_BUILT_BG   = 0xFF0A2A1A;
    @Unique private static final int COL_UNBUILT_BG = 0xFF1A1A1A;
    @Unique private static final int COL_REQ_BG     = 0xFF1A1A1A;
    @Unique private static final int COL_REQ_BORDER = 0xFF2A2A2A;

    // ── Layout ────────────────────────────────────────────────────────────────
    @Unique private static final int WIN_W        = 340;
    @Unique private static final int WIN_H        = 250;
    @Unique private static final int LIST_W       = 150;
    @Unique private static final int TOP_BAR_H    = 22;  // red title bar
    @Unique private static final int TAB_BAR_H    = 18;  // tab buttons row
    @Unique private static final int BOTTOM_BAR_H = 18;
    @Unique private static final int ROW_H        = 18;
    @Unique private static final int DOT_SIZE     = 6;

    // ── State ─────────────────────────────────────────────────────────────────
    @Unique private static final String VDX_PAGE = "villagedex_catalog";
    @Unique private boolean vdx$redirecting = false;
    @Unique private String  vdx$returnPage  = "map";
    @Unique private List<String> vdx$tabs   = new ArrayList<>();
    @Unique private int vdx$activeTab       = 0;
    @Unique private int vdx$selectedRow     = 0;
    @Unique private long vdx$tick           = 0;
    @Unique private Set<String> vdx$builtTypes = new HashSet<>();
    @Unique private Map<String, List<BuildingEntry>> vdx$byTab = new LinkedHashMap<>();
    @Unique private final List<net.minecraft.client.gui.widget.ClickableWidget> vdx$ownButtons = new ArrayList<>();
    @Unique private final List<ButtonWidget> vdx$tabButtons = new ArrayList<>();

    @Unique
    private record BuildingEntry(String name, Map<Identifier, Integer> requirements, int iconU, int iconV) {}

    protected BlueprintScreenMixin() { super(Text.empty()); }

    // ── Intercept setPage("catalog") ─────────────────────────────────────────

    @Inject(method = "setPage", remap = false, at = @At("HEAD"), cancellable = true)
    private void vdx$interceptCatalog(String pageName, CallbackInfo ci) {
        // Restore MCA buttons and hide ours when leaving our page
        if (VDX_PAGE.equals(this.page) && !VDX_PAGE.equals(pageName)) {
            for (net.minecraft.client.gui.Element child : this.children()) {
                if (child instanceof net.minecraft.client.gui.widget.ClickableWidget w) {
                    w.visible = !vdx$ownButtons.contains(w);
                }
            }
        }
        if (!"catalog".equals(pageName) || vdx$redirecting) return;
        if (this.page != null && !this.page.isBlank() && !VDX_PAGE.equals(this.page)) {
            vdx$returnPage = this.page;
        } else {
            vdx$returnPage = "map";
        }
        vdx$redirecting = true;
        setPage(VDX_PAGE);
        vdx$redirecting = false;
        ci.cancel();
    }

    @Inject(method = "setPage", remap = false, at = @At("TAIL"))
    private void vdx$onPageSet(String pageName, CallbackInfo ci) {
        // Hide all existing MCA buttons when our page is active, restore otherwise
        boolean isOurPage = VDX_PAGE.equals(this.page);
        for (net.minecraft.client.gui.Element child : this.children()) {
            if (child instanceof net.minecraft.client.gui.widget.ClickableWidget widget) {
                if (vdx$ownButtons.contains(widget)) {
                    widget.visible = isOurPage;
                } else {
                    widget.visible = !isOurPage;
                }
            }
        }
        if (!isOurPage) return;
        vdx$rebuild();   // must come first so vdx$tabs is populated
        vdx$addButtons();
    }

    // ── Replace renderCatalog with our Pokédex UI ─────────────────────────────

    @Inject(method = "method_25394", remap = false, at = @At("TAIL"))
    private void vdx$render(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!VDX_PAGE.equals(this.page)) return;
        vdx$tick++;

        int wx  = (this.width  - WIN_W) / 2;
        int wy  = (this.height - WIN_H) / 2;
        int wx2 = wx + WIN_W;
        int wy2 = wy + WIN_H;
        int cy  = wy + TOP_BAR_H + TAB_BAR_H;
        int cy2 = wy2 - BOTTOM_BAR_H;

        // Shell
        ctx.fill(wx, wy, wx2, wy2, COL_BG);
        ctx.fill(wx, wy, wx2, wy + TOP_BAR_H, COL_SHELL);
        ctx.fill(wx, wy2 - BOTTOM_BAR_H, wx2, wy2, COL_SHELL);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("VILLAGE DEX"), wx + WIN_W / 2, wy + 7, COL_WHITE);

        // Tab bar background
        ctx.fill(wx, wy + TOP_BAR_H, wx2, wy + TOP_BAR_H + TAB_BAR_H, 0xFF222222);
        // Active tab highlight — red underline + bright text on active, dim inactive
        for (int ti = 0; ti < vdx$tabButtons.size(); ti++) {
            ButtonWidget btn = vdx$tabButtons.get(ti);
            boolean isActive = ti == vdx$activeTab;
            int bx = btn.getX(), by2 = btn.getY(), bw = btn.getWidth();
            // Draw highlight background on active tab
            if (isActive) {
                ctx.fill(bx, by2, bx + bw, by2 + TAB_BAR_H - 2, 0xFF3A0000);
                ctx.drawBorder(bx, by2, bw, TAB_BAR_H - 2, COL_SEL_BAR);
                // Red underline
                ctx.fill(bx, by2 + TAB_BAR_H - 4, bx + bw, by2 + TAB_BAR_H - 2, COL_SEL_BAR);
                // Override text color to white
                ctx.drawCenteredTextWithShadow(this.textRenderer,
                        Text.literal(vdx$tabs.get(ti)), bx + bw / 2, by2 + 3, COL_WHITE);
            } else {
                // Dim inactive tabs slightly
                ctx.drawCenteredTextWithShadow(this.textRenderer,
                        Text.literal(vdx$tabs.get(ti)), bx + bw / 2, by2 + 3, COL_DIM);
            }
        }

        // Scanlines
        for (int sy = cy; sy < cy2; sy += 4) ctx.fill(wx, sy + 3, wx2, sy + 4, 0x12000000);

        // List panel
        ctx.fill(wx, cy, wx + LIST_W, cy2, COL_LIST_BG);
        ctx.fill(wx + LIST_W, cy, wx + LIST_W + 1, cy2, COL_DIVIDER);

        // Detail panel
        ctx.fill(wx + LIST_W + 1, cy, wx2, cy2, COL_PANEL);

        // List rows
        List<BuildingEntry> buildings = vdx$currentBuildings();
        int rowY = cy + 2;
        for (int i = 0; i < buildings.size(); i++) {
            BuildingEntry b = buildings.get(i);
            boolean sel   = i == vdx$selectedRow;
            boolean built = vdx$builtTypes.contains(b.name());
            if (sel) {
                ctx.fill(wx, rowY, wx + LIST_W, rowY + ROW_H, COL_SEL_BG);
                ctx.fill(wx, rowY, wx + 2, rowY + ROW_H, COL_SEL_BAR);
            }
            int dotX = wx + 6, dotY = rowY + (ROW_H - DOT_SIZE) / 2;
            ctx.fill(dotX, dotY, dotX + DOT_SIZE, dotY + DOT_SIZE, built ? COL_DOT_ON : COL_DOT_OFF);
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(vdx$name(b.name())),
                    wx + 16, rowY + (ROW_H - 8) / 2, sel ? COL_WHITE : COL_DIM);
            rowY += ROW_H;
        }

        // Detail
        vdx$renderDetail(ctx, wx + LIST_W + 1, cy, wx2, cy2);
    }

    @Unique
    private void vdx$renderDetail(DrawContext ctx, int x, int y, int x2, int y2) {
        List<BuildingEntry> buildings = vdx$currentBuildings();
        if (buildings.isEmpty()) return;
        BuildingEntry b = buildings.get(Math.min(vdx$selectedRow, buildings.size() - 1));

        boolean built = vdx$builtTypes.contains(b.name());
        int px = x + 6, py = y + 6;

        // Icon box
        ctx.fill(px, py, px + 32, py + 32, 0xFF1A1A1A);
        ctx.drawBorder(px, py, 32, 32, COL_DIVIDER);
        Optional<Identifier> ovId = VillageDexDataLoader.getOverride(b.name()).nodeItem();
        boolean drewIcon = false;
        // node_item override takes priority — always use drawItem
        if (ovId.isPresent()) {
            ItemStack icon = vdx$icon(b);
            if (!icon.isEmpty()) { ctx.drawItem(icon, px + 8, py + 8); drewIcon = true; }
        }
        // Cobblemon buildings: use drawItem (renders 3D model like inventory)
        if (!drewIcon && b.name().startsWith("cobblemon/")) {
            ItemStack icon = vdx$icon(b);
            if (!icon.isEmpty()) { ctx.drawItem(icon, px + 8, py + 8); drewIcon = true; }
        }
        // MCA buildings: use sprite sheet via iconU/V
        if (!drewIcon && b.iconU() >= 0) {
            net.minecraft.util.Identifier sheet = net.minecraft.util.Identifier.of("villagedex", "textures/buildings/mca_buildings.png");
            ctx.drawTexture(sheet, px + 8, py + 8, b.iconU() * 16, b.iconV() * 16, 16, 16);
            drewIcon = true;
        }
        // Final fallback
        if (!drewIcon) {
            ItemStack icon = vdx$icon(b);
            if (!icon.isEmpty()) ctx.drawItem(icon, px + 8, py + 8);
        }

        // Name + blink cursor
        boolean blink = (vdx$tick / 10) % 2 == 0;
        ctx.drawTextWithShadow(this.textRenderer,
                Text.literal(vdx$name(b.name()) + (blink ? "|" : " ")), px + 36, py, COL_WHITE);

        // Badges
        int bx = px + 36, by = py + 12;
        bx = vdx$badge(ctx, vdx$modBadge(b.name()), bx, by, COL_MOD_BG, COL_GREEN) + 3;
        vdx$badge(ctx, built ? "BUILT" : "NOT BUILT", bx, by,
                built ? COL_BUILT_BG : COL_UNBUILT_BG,
                built ? COL_GREEN : COL_DIM);

        // Divider
        ctx.fill(px, py + 42, x2 - 4, py + 43, COL_DIVIDER);

        // Description
        String descKey = "buildingType." + b.name() + ".description";
        String desc = Text.translatable(descKey).getString();
        if (desc.equals(descKey)) desc = "A building for your village.";
        int dy = py + 48;
        for (String line : vdx$wrap(desc, x2 - px - 8)) {
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(line), px, dy, COL_LCD);
            if ((dy += 9) > py + 66) break;
        }

        // Requirements
        int ry = py + 78;
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("— REQUIREMENTS —"), px, ry, COL_SEL_BAR);
        int chipX = px, chipY = ry + 11;
        for (Map.Entry<Identifier, Integer> req : b.requirements().entrySet()) {
            ItemStack ri = vdx$reqIcon(req.getKey());
            String rname = vdx$reqName(req.getKey());
            int qty = req.getValue();
            int chipW = this.textRenderer.getWidth(qty + "x " + rname) + (ri.isEmpty() ? 6 : 22);
            if (chipX + chipW > x2 - 4) { chipX = px; chipY += 16; }
            if (chipY > y2 - 14) break;
            ctx.fill(chipX, chipY, chipX + chipW, chipY + 13, COL_REQ_BG);
            ctx.drawBorder(chipX, chipY, chipW, 13, COL_REQ_BORDER);
            if (!ri.isEmpty()) {
                ctx.drawItem(ri, chipX + 2, chipY - 1);
                ctx.drawTextWithShadow(this.textRenderer, Text.literal(qty + "x"), chipX + 18, chipY + 3, COL_GOLD);
                ctx.drawTextWithShadow(this.textRenderer, Text.literal(rname),
                        chipX + 18 + this.textRenderer.getWidth(qty + "x") + 2, chipY + 3, COL_BLUE);
            } else {
                ctx.drawTextWithShadow(this.textRenderer, Text.literal(qty + "x " + rname), chipX + 4, chipY + 3, COL_BLUE);
            }
            chipX += chipW + 4;
        }
    }

    // ── Mouse click on list rows ──────────────────────────────────────────────

    @Inject(method = "method_25402", remap = false, at = @At("HEAD"), cancellable = true)
    private void vdx$mouseClicked(double mx, double my, int btn, CallbackInfoReturnable<Boolean> ci) {
        if (!VDX_PAGE.equals(this.page) || btn != 0) return;
        int wx = (this.width - WIN_W) / 2;
        int cy = (this.height - WIN_H) / 2 + TOP_BAR_H + TAB_BAR_H;
        if (mx >= wx && mx < wx + LIST_W && my >= cy) {
            int row = (int)(my - cy - 2) / ROW_H;
            List<BuildingEntry> buildings = vdx$currentBuildings();
            if (row >= 0 && row < buildings.size()) {
                vdx$selectedRow = row;
                ci.cancel();
            }
        }
    }

    // ── Data helpers ──────────────────────────────────────────────────────────

    @Unique
    private void vdx$rebuild() {
        vdx$byTab.clear();
        vdx$builtTypes.clear();

        // Read built buildings via reflection
        try {
            Object village = this.getClass().getSuperclass().getDeclaredField("village").get(this);
            if (village != null) {
                Map<?,?> buildings = (Map<?,?>) village.getClass().getMethod("getBuildings").invoke(village);
                for (Object building : buildings.values()) {
                    String type = (String) building.getClass().getMethod("getType").invoke(building);
                    if (type != null) vdx$builtTypes.add(type);
                }
            }
        } catch (Exception e) {
            VillageDexClient.LOGGER.warn("VillageDex: could not read village buildings: {}", e.getMessage());
        }

        // Read all building types via reflection
        try {
            Class<?> btClass = Class.forName("net.conczin.mca.resources.BuildingTypes");
            Object instance = btClass.getMethod("getInstance").invoke(null);
            Map<?,?> types = (Map<?,?>) instance.getClass().getMethod("getBuildingTypes").invoke(instance);
            for (Object bt : types.values()) {
                if (!(boolean) bt.getClass().getMethod("visible").invoke(bt)) continue;
                String btName = (String) bt.getClass().getMethod("name").invoke(bt);
                if (VillageDexDataLoader.getOverride(btName).hide()) continue;
                Map<?,?> rawGroups = (Map<?,?>) bt.getClass().getMethod("getGroups").invoke(bt);
                Map<Identifier, Integer> reqs = new LinkedHashMap<>();
                for (Map.Entry<?,?> e : rawGroups.entrySet()) {
                    if (e.getKey() instanceof Identifier id) reqs.put(id, (Integer) e.getValue());
                }
                int iconU = -1, iconV = -1;
                try {
                    Object u = bt.getClass().getMethod("iconU").invoke(bt);
                    Object v = bt.getClass().getMethod("iconV").invoke(bt);
                    if (u instanceof Integer ui) iconU = ui;
                    if (v instanceof Integer vi) iconV = vi;
                } catch (Exception ignored) {}
                String tab = VillageDexDataLoader.resolveTab(btName);
                vdx$byTab.computeIfAbsent(tab, k -> new ArrayList<>()).add(new BuildingEntry(btName, reqs, iconU, iconV));
            }
        } catch (Exception e) {
            VillageDexClient.LOGGER.error("VillageDex: could not load building types: {}", e.getMessage());
        }

        for (List<BuildingEntry> list : vdx$byTab.values())
            list.sort(Comparator.comparing(e -> vdx$name(e.name())));

        vdx$tabs = new ArrayList<>(vdx$byTab.keySet());
        vdx$activeTab = Math.min(vdx$activeTab, Math.max(0, vdx$tabs.size() - 1));
        vdx$selectedRow = 0;
    }

    @Unique
    private void vdx$addButtons() {
        vdx$ownButtons.clear();
        int wx = (this.width - WIN_W) / 2, wy = (this.height - WIN_H) / 2;
        // Back button in top-left of red title bar
        var back = addDrawableChild(ButtonWidget.builder(Text.literal("< Back"), b -> setPage(vdx$returnPage))
                .dimensions(wx + 4, wy + 4, 44, 14).build());
        vdx$ownButtons.add(back);
        // Tab buttons in their own row below the red bar
        vdx$tabButtons.clear();
        int tx = wx + 4;
        int ty = wy + TOP_BAR_H + 1;
        for (int i = 0; i < vdx$tabs.size(); i++) {
            final int idx = i;
            String label = vdx$tabs.get(i);
            int tw = this.textRenderer.getWidth(label) + 10;
            // Use empty text — we draw our own label in render to avoid button gradient
            var tab = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
                vdx$activeTab = idx; vdx$selectedRow = 0;
            }).dimensions(tx, ty, tw, TAB_BAR_H - 2).build());
            vdx$ownButtons.add(tab);
            vdx$tabButtons.add(tab);
            tx += tw + 3;
        }
    }

    @Unique private List<BuildingEntry> vdx$currentBuildings() {
        if (vdx$tabs.isEmpty()) return List.of();
        return vdx$byTab.getOrDefault(vdx$tabs.get(Math.min(vdx$activeTab, vdx$tabs.size() - 1)), List.of());
    }

    @Unique private String vdx$name(String id) {
        String key = "buildingType." + id, t = Text.translatable(key).getString();
        if (!t.equals(key)) return t;
        String[] parts = id.split("/");
        return Arrays.stream(parts[parts.length - 1].split("_"))
                .filter(w -> !w.isEmpty())
                .map(w -> w.substring(0,1).toUpperCase(Locale.ROOT) + w.substring(1))
                .collect(Collectors.joining(" "));
    }

    @Unique private String vdx$modBadge(String name) {
        if (name.startsWith("cobbleverse/")) return "COBBLEVERSE";
        if (name.startsWith("cobblemon/"))   return "COBBLEMON";
        return "MCA";
    }

    @Unique private int vdx$badge(DrawContext ctx, String text, int x, int y, int bg, int fg) {
        int w = this.textRenderer.getWidth(text) + 6;
        ctx.fill(x, y, x + w, y + 10, bg);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(text), x + 3, y + 1, fg);
        return x + w;
    }

    @Unique private ItemStack vdx$icon(BuildingEntry b) {
        Optional<Identifier> ov = VillageDexDataLoader.getOverride(b.name()).nodeItem();
        if (ov.isPresent()) {
            if (Registries.ITEM.containsId(ov.get()))
                return new ItemStack(Registries.ITEM.get(ov.get()));
            if (Registries.BLOCK.containsId(ov.get())) {
                net.minecraft.item.Item asItem = Registries.BLOCK.get(ov.get()).asItem();
                if (asItem != net.minecraft.item.Items.AIR) return new ItemStack(asItem);
                // Block has no item form — try item registry with same ID anyway
                return new ItemStack(Registries.BLOCK.get(ov.get()));
            }
        }
        for (Identifier id : b.requirements().keySet()) {
            ItemStack stack = vdx$reqIcon(id);
            if (!stack.isEmpty()) return stack;
        }
        return ItemStack.EMPTY;
    }

    // Preferred items for specific tags that don't have vanilla entries
    @Unique private static final Map<String, String> TAG_PREFERRED_ITEM = Map.of(
        "mca:tombstones", "mca:cross_headstone",
        "mca:chests",     "minecraft:chest",
        "c:chests",       "minecraft:chest"
    );

    @Unique private ItemStack vdx$reqIcon(Identifier id) {
        // Direct item/block lookup
        if (Registries.ITEM.containsId(id))  return new ItemStack(Registries.ITEM.get(id));
        if (Registries.BLOCK.containsId(id)) {
            net.minecraft.item.Item asItem = Registries.BLOCK.get(id).asItem();
            if (asItem != net.minecraft.item.Items.AIR) return new ItemStack(asItem);
            return new ItemStack(Registries.BLOCK.get(id));
        }

        // Check preferred item overrides for known tags
        String tagKey = id.getNamespace() + ":" + id.getPath();
        if (TAG_PREFERRED_ITEM.containsKey(tagKey)) {
            Identifier preferred = Identifier.tryParse(TAG_PREFERRED_ITEM.get(tagKey));
            if (preferred != null && Registries.ITEM.containsId(preferred))
                return new ItemStack(Registries.ITEM.get(preferred));
            if (preferred != null && Registries.BLOCK.containsId(preferred))
                return new ItemStack(Registries.BLOCK.get(preferred).asItem());
        }

        // Tag fallback — prefer vanilla (minecraft namespace) items first
        net.minecraft.registry.tag.TagKey<net.minecraft.item.Item> itemTag =
                net.minecraft.registry.tag.TagKey.of(net.minecraft.registry.RegistryKeys.ITEM, id);
        var tagContents = Registries.ITEM.getEntryList(itemTag);
        if (tagContents.isPresent() && tagContents.get().size() > 0) {
            for (var entry : tagContents.get()) {
                Identifier entryId = Registries.ITEM.getId(entry.value());
                if (entryId != null && "minecraft".equals(entryId.getNamespace())) {
                    return new ItemStack(entry.value());
                }
            }
            return new ItemStack(tagContents.get().get(0).value());
        }
        // Also try block tags
        net.minecraft.registry.tag.TagKey<net.minecraft.block.Block> blockTag =
                net.minecraft.registry.tag.TagKey.of(net.minecraft.registry.RegistryKeys.BLOCK, id);
        var blockTagContents = Registries.BLOCK.getEntryList(blockTag);
        if (blockTagContents.isPresent() && blockTagContents.get().size() > 0) {
            for (var entry : blockTagContents.get()) {
                Identifier entryId = Registries.BLOCK.getId(entry.value());
                if (entryId != null && "minecraft".equals(entryId.getNamespace())) {
                    return new ItemStack(entry.value().asItem());
                }
            }
            return new ItemStack(blockTagContents.get().get(0).value().asItem());
        }
        return ItemStack.EMPTY;
    }

    @Unique private String vdx$reqName(Identifier id) {
        if (Registries.ITEM.containsId(id))  return Text.translatable(Registries.ITEM.get(id).getTranslationKey()).getString();
        if (Registries.BLOCK.containsId(id)) return Text.translatable(Registries.BLOCK.get(id).getTranslationKey()).getString();
        // Tag: prefer vanilla item name, fallback to formatted path
        net.minecraft.registry.tag.TagKey<net.minecraft.item.Item> itemTag =
                net.minecraft.registry.tag.TagKey.of(net.minecraft.registry.RegistryKeys.ITEM, id);
        var tagContents = Registries.ITEM.getEntryList(itemTag);
        if (tagContents.isPresent() && tagContents.get().size() > 0) {
            net.minecraft.item.Item best = tagContents.get().get(0).value();
            for (var entry : tagContents.get()) {
                Identifier eid = Registries.ITEM.getId(entry.value());
                if (eid != null && "minecraft".equals(eid.getNamespace())) { best = entry.value(); break; }
            }
            return Text.translatable(best.getTranslationKey()).getString() + "s";
        }
        // Format tag path as readable name
        return Arrays.stream(id.getPath().replace('_',' ').split(" "))
                .map(w -> w.isEmpty() ? w : w.substring(0,1).toUpperCase(Locale.ROOT) + w.substring(1))
                .collect(Collectors.joining(" "));
    }

    @Unique private List<String> vdx$wrap(String text, int maxW) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            String test = cur.isEmpty() ? word : cur + " " + word;
            if (this.textRenderer.getWidth(test) > maxW) {
                if (!cur.isEmpty()) lines.add(cur.toString());
                cur = new StringBuilder(word);
            } else cur = new StringBuilder(test);
        }
        if (!cur.isEmpty()) lines.add(cur.toString());
        return lines;
    }
}
