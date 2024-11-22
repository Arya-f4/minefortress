package org.minefortress.fortress;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.remmintan.mods.minefortress.core.FortressGamemode;
import net.remmintan.mods.minefortress.core.FortressState;
import net.remmintan.mods.minefortress.core.dtos.buildings.BlueprintMetadata;
import net.remmintan.mods.minefortress.core.dtos.buildings.BuildingHealthRenderInfo;
import net.remmintan.mods.minefortress.core.interfaces.blueprints.ProfessionType;
import net.remmintan.mods.minefortress.core.interfaces.buildings.IEssentialBuildingInfo;
import net.remmintan.mods.minefortress.core.interfaces.client.IClientFortressManager;
import net.remmintan.mods.minefortress.core.interfaces.client.IClientManagersProvider;
import net.remmintan.mods.minefortress.core.interfaces.client.IHoveredBlockProvider;
import net.remmintan.mods.minefortress.core.interfaces.combat.IClientFightManager;
import net.remmintan.mods.minefortress.core.interfaces.professions.IClientProfessionManager;
import net.remmintan.mods.minefortress.core.interfaces.professions.IHireInfo;
import net.remmintan.mods.minefortress.core.interfaces.resources.IClientResourceManager;
import net.remmintan.mods.minefortress.core.utils.CoreModUtils;
import net.remmintan.mods.minefortress.networking.c2s.C2SJumpToCampfire;
import net.remmintan.mods.minefortress.networking.c2s.ServerboundSetGamemodePacket;
import net.remmintan.mods.minefortress.networking.helpers.FortressChannelNames;
import net.remmintan.mods.minefortress.networking.helpers.FortressClientNetworkHelper;
import org.minefortress.MineFortressMod;
import org.minefortress.fight.ClientFightManager;
import org.minefortress.fortress.resources.client.ClientResourceManagerImpl;
import org.minefortress.professions.ClientProfessionManager;
import org.minefortress.professions.hire.ClientHireHandler;
import org.minefortress.renderer.gui.fortress.RepairBuildingScreen;
import org.minefortress.renderer.gui.hire.HirePawnScreen;
import org.minefortress.utils.BlockUtils;
import org.minefortress.utils.ModUtils;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public final class ClientFortressManager implements IClientFortressManager {

    private final IClientProfessionManager professionManager;
    private final IClientResourceManager resourceManager = new ClientResourceManagerImpl();
    private final IClientFightManager fightManager = new ClientFightManager();
    private boolean connectedToTheServer = false;
    private boolean initialized = false;

    private BlockPos fortressCenter = null;
    private int colonistsCount = 0;
    private int reservedColonistCount = 0;

    private IEssentialBuildingInfo hoveredBuilding = null;

    private List<IEssentialBuildingInfo> buildings = new ArrayList<>();
    private Map<Block, List<BlockPos>> specialBlocks = new HashMap<>();
    private Map<Block, List<BlockPos>> blueprintsSpecialBlocks = new HashMap<>();

    private FortressGamemode gamemode;

    private int maxColonistsCount;

    private FortressState state = FortressState.BUILD_SELECTION;

    public ClientFortressManager() {
        professionManager = new ClientProfessionManager(
                () -> ((IClientManagersProvider) MinecraftClient.getInstance())
                        .get_ClientFortressManager()
        );
    }

    @Override
    public void jumpToCampfire() {
        final var packet = new C2SJumpToCampfire();
        FortressClientNetworkHelper.send(C2SJumpToCampfire.CHANNEL, packet);
    }

    @Override
    public void updateBuildings(List<IEssentialBuildingInfo> buildings) {
        this.buildings = buildings;
    }

    @Override
    public void setSpecialBlocks(Map<Block, List<BlockPos>> specialBlocks, Map<Block, List<BlockPos>> blueprintSpecialBlocks) {
        this.specialBlocks = specialBlocks;
        this.blueprintsSpecialBlocks = blueprintSpecialBlocks;
    }

    @Override
    public int getReservedPawnsCount() {
        return reservedColonistCount;
    }

    @Override
    public void sync(
            int colonistsCount,
            BlockPos fortressCenter,
            FortressGamemode gamemode,
            boolean connectedToTheServer,
            int maxColonistsCount,
            int reservedColonistCount) {
        this.colonistsCount = colonistsCount;
        this.fortressCenter = fortressCenter;
        this.gamemode = gamemode;
        this.connectedToTheServer = connectedToTheServer;
        this.maxColonistsCount = maxColonistsCount;
        this.reservedColonistCount = reservedColonistCount;
        this.initialized = true;
    }

    @Override
    public void tick(IHoveredBlockProvider fortressClient) {
        final MinecraftClient client = (MinecraftClient) fortressClient;
        if(
                client.world == null ||
                client.interactionManager == null ||
                client.interactionManager.getCurrentGameMode() != MineFortressMod.FORTRESS
        ) {
            hoveredBuilding = null;
            return;
        }
        if(!initialized) return;

        if (state != FortressState.BUILD_EDITING && state != FortressState.BUILD_SELECTION) {
            hoveredBuilding = null;
        }
        resetBuildEditState();

        if(isCenterNotSet()) {
            final var blueprintManager = CoreModUtils.getMineFortressManagersProvider().get_BlueprintManager();
            if (!blueprintManager.isSelecting()) {
                blueprintManager.select("campfire");
            } else {
                final var id = blueprintManager.getSelectedStructure().getId();
                if (!id.equals("campfire")) {
                    blueprintManager.select("campfire");
                }
            }
        }
    }

    private void resetBuildEditState() {
        if(this.state == FortressState.BUILD_EDITING && !CoreModUtils.getMineFortressManagersProvider().get_PawnsSelectionManager().hasSelected()) {
            this.state = FortressState.BUILD_SELECTION;
        }
    }

    @Override
    public void open_HireScreen(MinecraftClient client, String screenName, Map<String, IHireInfo> professions, List<String> additionalInfo) {
        final var handler = new ClientHireHandler(screenName, professions, additionalInfo);
        final var screen = new HirePawnScreen(handler);
        client.setScreen(screen);
    }

    @Override
    public boolean isConnectedToTheServer() {
        return initialized && connectedToTheServer;
    }

    @Override
    public boolean notInitialized() {
        return !initialized;
    }

    @Override
    public boolean isCenterNotSet() {
        return initialized && fortressCenter == null && this.gamemode != FortressGamemode.NONE;
    }

    @Override
    public void setupFortressCenter(BlockPos pos) {
        if(fortressCenter!=null) throw new IllegalStateException("Fortress center already set");
        fortressCenter = pos;
    }

    @Override
    public List<BlockPos> getBuildingSelection(BlockPos pos) {
        for(IEssentialBuildingInfo building : buildings){
            final BlockPos start = building.getStart();
            final BlockPos end = building.getEnd();
            if(BlockUtils.isPosBetween(pos, start, end)){
                hoveredBuilding = building;
                return StreamSupport
                        .stream(BlockPos.iterate(start, end).spliterator(), false)
                        .map(BlockPos::toImmutable)
                        .collect(Collectors.toList());
            }
        }
        hoveredBuilding = null;
        return Collections.emptyList();
    }

    @Override
    public boolean isBuildingHovered() {
        return hoveredBuilding != null;
    }

    @Override
    public Optional<IEssentialBuildingInfo> getHoveredBuilding() {
        return Optional.ofNullable(hoveredBuilding);
    }

    @Override
    public Optional<String> getHoveredBuildingName() {
        return getHoveredBuilding()
                .map(IEssentialBuildingInfo::getBlueprintId)
                .flatMap(it -> ModUtils.getBlueprintManager().getBlueprintMetadataManager().getByBlueprintId(it))
                .map(BlueprintMetadata::getName);
    }

    @Override
    public IClientProfessionManager getProfessionManager() {
        return professionManager;
    }

    @Override
    public boolean hasRequiredBuilding(ProfessionType type, int level, int minCount) {
        final var requiredBuilding = buildings.stream()
                .filter(b -> b.satisfiesRequirement(type, level));
        if (type == ProfessionType.MINER ||
                type == ProfessionType.LUMBERJACK ||
                type == ProfessionType.WARRIOR) {
            return requiredBuilding
                    .mapToLong(it -> it.getBedsCount() * 10)
                    .sum() > minCount;
        }
        final var count = requiredBuilding.count();
        if (type == ProfessionType.ARCHER)
            return count * 10 > minCount;

        if (type == ProfessionType.FARMER)
            return count * 5 > minCount;

        if (type == ProfessionType.FISHERMAN)
            return count * 3 > minCount;

        return count > minCount;
    }

    @Override
    public int countBuildings(ProfessionType type, int level) {
        return (int) buildings.stream()
                .filter(b -> b.satisfiesRequirement(type, level))
                .count();
    }

    @Override
    public boolean hasRequiredBlock(Block block, boolean blueprint, int minCount) {
        if(blueprint)
            return this.blueprintsSpecialBlocks.getOrDefault(block, Collections.emptyList()).size() > minCount;
        else
            return this.specialBlocks.getOrDefault(block, Collections.emptyList()).size() > minCount;
    }

    @Override
    public int getTotalColonistsCount() {
        return colonistsCount;
    }

    @Override
    public void setGamemode(FortressGamemode gamemode) {
        if(gamemode == null) throw new IllegalArgumentException("Gamemode cannot be null");
        if(gamemode == FortressGamemode.NONE) throw new IllegalArgumentException("Gamemode cannot be NONE");
        final ServerboundSetGamemodePacket serverboundSetGamemodePacket = new ServerboundSetGamemodePacket(gamemode);
        FortressClientNetworkHelper.send(FortressChannelNames.FORTRESS_SET_GAMEMODE, serverboundSetGamemodePacket);
    }

    @Override
    public boolean gamemodeNeedsInitialization() {
        return this.initialized && this.gamemode == FortressGamemode.NONE;
    }

    public boolean isCreative() {
        return this.gamemode == FortressGamemode.CREATIVE;
    }

    @Override
    public boolean isSurvival() {
        return this.gamemode != null && this.gamemode == FortressGamemode.SURVIVAL;
    }

    public IClientResourceManager getResourceManager() {
        return resourceManager;
    }

    @Override
    public IClientFightManager getFightManager() {
        return fightManager;
    }

    @Override
    public int getMaxColonistsCount() {
        return maxColonistsCount;
    }

    @Override
    public void reset() {
        this.initialized = false;
        this.state = FortressState.BUILD_SELECTION;
    }

    // getter and setter for state
    @Override
    public void setState(FortressState state) {
        this.state = state;
        if(state == FortressState.AREAS_SELECTION) {
            ModUtils.getAreasClientManager().getSavedAreasHolder().setNeedRebuild(true);
        }
        if(state == FortressState.BUILD_SELECTION || state == FortressState.BUILD_EDITING) {
            CoreModUtils.getClientTasksHolder().ifPresent(it -> it.setNeedRebuild(true));
        }
    }

    @Override
    public FortressState getState() {
        return this.state;
    }

    @Override
    public List<BuildingHealthRenderInfo> getBuildingHealths() {
        return switch (this.getState()) {
            case COMBAT -> buildings
                    .stream()
                    .filter(it -> it.getHealth() < 100)
                    .map(this::buildingToHealthRenderInfo)
                    .toList();
            case BUILD_SELECTION, BUILD_EDITING -> buildings
                    .stream()
                    .filter(it -> it.getHealth() < 33)
                    .map(this::buildingToHealthRenderInfo)
                    .toList();
            default -> Collections.emptyList();
        };
    }

    private BuildingHealthRenderInfo buildingToHealthRenderInfo(IEssentialBuildingInfo buildingInfo) {
        final var start = buildingInfo.getStart();
        final var end = buildingInfo.getEnd();

        final var maxY = Math.max(start.getY(), end.getY());
        final var centerX = (start.getX() + end.getX()) / 2;
        final var centerZ = (start.getZ() + end.getZ()) / 2;

        final var center = new Vec3d(centerX, maxY, centerZ);
        final var health = buildingInfo.getHealth();

        return new BuildingHealthRenderInfo(center, health);
    }

    @Override
    public void openRepairBuildingScreen(UUID buildingId, Map<BlockPos, BlockState> blocksToRepair) {
        MinecraftClient.getInstance().setScreen(new RepairBuildingScreen(buildingId, blocksToRepair, resourceManager));
    }
}
