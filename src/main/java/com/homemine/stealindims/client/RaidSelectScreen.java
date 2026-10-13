package com.homemine.stealindims.client;

import com.homemine.stealindims.net.RaidSelectInfoPayload;
import com.homemine.stealindims.net.StartRaidPayload;
import com.homemine.stealindims.raid.RaidDifficulty;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The raid select screen (design §8): pick a difficulty and a map, then depart.
 *
 * <p>Two deviations from the sketch in §8, both noted in the hand-off: the stash chest is not a panel
 * of this screen - it is opened by sneak using the device, since the screen itself needs no slots -
 * and the team readout is omitted with the rest of the party work.
 *
 * <p>Locked difficulties stay visible but disabled, so the ladder is always readable.
 */
public class RaidSelectScreen extends Screen {

    private static final int ROW_WIDTH = 260;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_STEP = 22;

    private final RaidSelectInfoPayload info;
    private RaidDifficulty selectedDifficulty;
    private RaidSelectInfoPayload.MapEntry selectedMap;
    private Button departButton;
    private boolean anyMapSelectable;

    private int difficultyLabelY;
    private int mapLabelY;
    private int hintY;

    public RaidSelectScreen(RaidSelectInfoPayload info) {
        super(Component.translatable("gui.stealindims.select.title"));
        this.info = info;
        RaidDifficulty[] ladder = RaidDifficulty.values();
        RaidSelectInfoPayload.LobbyInfo lobby = info.lobby();
        if (lobby != null) {
            // Something is already being prepared here: mirror its choices and forbid changing them.
            int pinned = Math.min(Math.max(lobby.difficultyOrdinal(), 0), ladder.length - 1);
            this.selectedDifficulty = ladder[pinned];
            this.selectedMap = mapById(lobby.mapId());
            return;
        }
        int highest = Math.min(Math.max(info.highestUnlocked(), 0), ladder.length - 1);
        while (highest > 0 && !ladder[highest].playable()) {
            highest--;
        }
        this.selectedDifficulty = ladder[highest];
        this.selectedMap = defaultMap();
    }

    /**
     * The row that starts out highlighted: the first map that can actually be departed to.
     *
     * <p>Locked rows stay in the list so the roadmap reads at a glance (design §6), but highlighting
     * one would put the screen in a state whose one action is refused, so they are only fallen back
     * on when the whole theme is locked.
     */
    private RaidSelectInfoPayload.MapEntry defaultMap() {
        for (RaidSelectInfoPayload.MapEntry map : this.info.maps()) {
            if (map.selectable()) {
                return map;
            }
        }
        return firstMap();
    }

    private RaidSelectInfoPayload.MapEntry firstMap() {
        return this.info.maps().isEmpty() ? null : this.info.maps().get(0);
    }

    private RaidSelectInfoPayload.MapEntry mapById(String id) {
        RaidSelectInfoPayload.MapEntry first = null;
        for (RaidSelectInfoPayload.MapEntry map : this.info.maps()) {
            if (first == null) {
                first = map;
            }
            if (map.id().equals(id)) {
                return map;
            }
        }
        return first;
    }

    @Override
    protected void init() {
        boolean inLobby = this.info.lobby() != null;
        int centre = this.width / 2;
        int rowX = centre - ROW_WIDTH / 2;
        int y = 46;

        this.difficultyLabelY = y - 12;
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            boolean unlocked = inLobby || difficulty.ordinal() <= this.info.highestUnlocked();
            boolean playable = difficulty.playable();
            boolean selected = difficulty == this.selectedDifficulty;
            Button button = Button.builder(label(difficulty, unlocked, playable, selected), widget -> {
                this.selectedDifficulty = difficulty;
                this.rebuildWidgets();
            }).bounds(rowX, y, ROW_WIDTH, ROW_HEIGHT).build();
            button.active = !inLobby && unlocked && playable;
            addRenderableWidget(button);
            y += ROW_STEP;
        }

        y += 8;
        this.mapLabelY = y - 12;
        this.anyMapSelectable = false;
        for (RaidSelectInfoPayload.MapEntry map : this.info.maps()) {
            if (map.selectable()) {
                this.anyMapSelectable = true;
            }
            boolean selected = map.equals(this.selectedMap);
            Button button = Button.builder(mapLabel(map, selected), widget -> {
                this.selectedMap = map;
                this.rebuildWidgets();
            }).bounds(rowX, y, ROW_WIDTH, ROW_HEIGHT).build();
            // A locked map stays listed but not clickable (design §6): the row is the roadmap, the
            // button is the door.
            button.active = !inLobby && map.selectable();
            addRenderableWidget(button);
            y += ROW_STEP;
        }

        this.hintY = y + 6;
        Component action = Component.translatable(inLobby ? "gui.stealindims.join" : "gui.stealindims.depart");
        this.departButton = addRenderableWidget(Button.builder(action, widget -> depart())
                .bounds(centre - 60, this.height - 32, 120, ROW_HEIGHT)
                .build());
        syncDepartButton();
    }

    /**
     * A run can only be opened when there is somewhere to go: with every map of the theme locked the
     * depart button has nothing to depart to, so it greys out rather than sending a map id the server
     * would bounce (design §6).
     */
    private void syncDepartButton() {
        if (this.departButton == null) {
            return;
        }
        boolean hasMap = this.info.lobby() != null || this.anyMapSelectable;
        boolean mapOpen = this.selectedMap == null || this.selectedMap.selectable();
        this.departButton.active = hasMap && mapOpen;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, hostLabel(), this.width / 2, 30, ChatFormatting.GRAY.getColor());
        guiGraphics.drawString(this.font, Component.translatable("gui.stealindims.select.difficulty"),
                this.width / 2 - ROW_WIDTH / 2, this.difficultyLabelY, ChatFormatting.YELLOW.getColor());
        guiGraphics.drawString(this.font, Component.translatable("gui.stealindims.select.map"),
                this.width / 2 - ROW_WIDTH / 2, this.mapLabelY, ChatFormatting.YELLOW.getColor());
        RaidSelectInfoPayload.LobbyInfo lobby = this.info.lobby();
        if (lobby != null) {
            guiGraphics.drawCenteredString(this.font, lobbyLine(lobby), this.width / 2, this.hintY,
                    ChatFormatting.GREEN.getColor());
        }
        guiGraphics.drawCenteredString(this.font, Component.translatable("gui.stealindims.select.hint"),
                this.width / 2, this.hintY + 12, ChatFormatting.GRAY.getColor());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void depart() {
        if (this.selectedMap == null || !this.selectedMap.selectable()) {
            return;
        }
        if (this.info.lobby() == null && !this.selectedDifficulty.playable()) {
            return;
        }
        PacketDistributor.sendToServer(new StartRaidPayload(this.selectedDifficulty.ordinal(),
                this.selectedMap.id()));
        onClose();
    }

    /** User decision A2: show what is being prepared here, since it cannot be changed from this screen. */
    private Component lobbyLine(RaidSelectInfoPayload.LobbyInfo lobby) {
        RaidDifficulty[] ladder = RaidDifficulty.values();
        int pinned = Math.min(Math.max(lobby.difficultyOrdinal(), 0), ladder.length - 1);
        RaidSelectInfoPayload.MapEntry map = mapById(lobby.mapId());
        String mapName = map == null ? lobby.mapId() : map.name();
        return Component.translatable("gui.stealindims.select.lobby",
                ladder[pinned].displayName(), mapName, lobby.memberCount());
    }

    private Component hostLabel() {
        return Component.translatable("gui.stealindims.select.host",
                RaidClientState.dimensionName(this.info.hostDimension()));
    }

    private static Component label(RaidDifficulty difficulty, boolean unlocked, boolean playable, boolean selected) {
        Component line = Component.translatable("gui.stealindims.select.difficulty_row",
                difficulty.shortName(),
                RaidClientState.formatClock(difficulty.timeLimitSeconds()),
                Math.round(difficulty.equipmentKeepRatio() * 100F),
                RaidClientState.formatValue(difficulty.requiredValue()));
        Component label = Component.literal(selected ? "▶ " : "  ").append(line);
        if (!unlocked) {
            return label.copy().append(Component.translatable("gui.stealindims.select.locked"));
        }
        return playable ? label : label.copy().append(Component.translatable("gui.stealindims.select.under_construction"));
    }

    private static Component mapLabel(RaidSelectInfoPayload.MapEntry map, boolean selected) {
        Component line = Component.translatable("gui.stealindims.select.map_row",
                map.name(),
                Component.translatable("gui.stealindims.form." + map.form()),
                Component.translatable("gui.stealindims.size." + map.size()));
        Component label = Component.literal(selected ? "▶ " : "  ").append(line);
        if (map.locked()) {
            return label.copy().append(Component.translatable("gui.stealindims.select.locked_map"));
        }
        return label;
    }
}
