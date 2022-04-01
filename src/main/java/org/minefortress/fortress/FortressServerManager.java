package org.minefortress.fortress;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;
import org.minefortress.entity.Colonist;
import org.minefortress.entity.colonist.ColonistNameGenerator;
import org.minefortress.interfaces.FortressServerPlayerEntity;
import org.minefortress.network.ClientboundSyncBuildingsPacket;
import org.minefortress.network.ClientboundSyncFortressManagerPacket;
import org.minefortress.network.ClientboundSyncSpecialBlocksPacket;
import org.minefortress.network.helpers.FortressChannelNames;
import org.minefortress.network.helpers.FortressServerNetworkHelper;
import org.minefortress.professions.ServerProfessionManager;

import java.util.*;

public final class FortressServerManager extends AbstractFortressManager {

    private static final int DEFAULT_COLONIST_COUNT = 5;

    private boolean needSync = true;
    private boolean needSyncBuildings = false;
    private boolean needSyncSpecialBlocks = false;

    private BlockPos fortressCenter = null;
    private final Set<Colonist> colonists = new HashSet<>();
    private final Set<FortressBulding> buildings = new HashSet<>();
    private final Map<Block, List<BlockPos>> specialBlocks = new HashMap<>();

    private ColonistNameGenerator nameGenerator = new ColonistNameGenerator();
    private final ServerProfessionManager serverProfessionManager;

    private int maxX = Integer.MIN_VALUE;
    private int maxZ = Integer.MIN_VALUE;
    private int minX = Integer.MAX_VALUE;
    private int minZ = Integer.MAX_VALUE;

    public FortressServerManager() {
        serverProfessionManager = new ServerProfessionManager(() -> this);
    }

    public void addBuilding(FortressBulding building) {
        final BlockPos start = building.getStart();
        final BlockPos end = building.getEnd();
        if(start.getX() < minX) minX = start.getX();
        if(start.getZ() < minZ) minZ = start.getZ();
        if(end.getX() > maxX) maxX = end.getX();
        if(end.getZ() > maxZ) maxZ = end.getZ();
        buildings.add(building);
        this.scheduleSyncBuildings();
    }

    public Optional<FortressBedInfo> getFreeBed(){
        for(FortressBulding building : buildings){
            final Optional<FortressBedInfo> freeBed = building.getFreeBed();
            if(freeBed.isPresent()) return freeBed;
        }
        return Optional.empty();
    }

    public void addColonist(Colonist colonist) {
        colonists.add(colonist);
        scheduleSync();
    }

    public void tick(ServerPlayerEntity player) {
        tickFortress(player.world);
        serverProfessionManager.tick(player);
        if(!needSync) return;
        final ClientboundSyncFortressManagerPacket packet = new ClientboundSyncFortressManagerPacket(colonists.size(), fortressCenter);
        FortressServerNetworkHelper.send(player, FortressChannelNames.FORTRESS_MANAGER_SYNC, packet);
        if (needSyncBuildings) {
            final ClientboundSyncBuildingsPacket syncBuildings = new ClientboundSyncBuildingsPacket(buildings);
            FortressServerNetworkHelper.send(player, FortressChannelNames.FORTRESS_BUILDINGS_SYNC, syncBuildings);
            needSyncBuildings = false;
        }
        if(needSyncSpecialBlocks){
            final ClientboundSyncSpecialBlocksPacket syncBlocks = new ClientboundSyncSpecialBlocksPacket(specialBlocks);
            FortressServerNetworkHelper.send(player, FortressChannelNames.FORTRESS_SPECIAL_BLOCKS_SYNC, syncBlocks);
            needSyncSpecialBlocks = false;
        }
        needSync = false;
    }

    public void tickFortress(World world) {
        if(colonists.removeIf(colonist -> !colonist.isAlive()))
            scheduleSync();

        for (FortressBulding building : buildings) {
            building.tick();
        }

        if(!specialBlocks.isEmpty()) {
            boolean needSync = false;
            for(Map.Entry<Block, List<BlockPos>> entry : new HashSet<>(specialBlocks.entrySet())) {
                final Block block = entry.getKey();
                final List<BlockPos> positions = entry.getValue();
                needSync = positions.removeIf(pos -> world.getBlockState(pos).getBlock() != block);
                if (positions.isEmpty()) {
                    specialBlocks.remove(block);
                }
            }
            if(needSync) {
                scheduleSyncSpecialBlocks();
            }
        }

    }

    public void setupCenter(BlockPos fortressCenter, World world, ServerPlayerEntity player) {
        if(fortressCenter == null) throw new IllegalArgumentException("Center cannot be null");
        this.fortressCenter = fortressCenter;

        if(!(world instanceof ServerWorld serverWorld))
            throw new IllegalArgumentException("World must be a server world");

        world.setBlockState(fortressCenter, getStateForCampCenter(), 3);
        world.emitGameEvent(player, GameEvent.BLOCK_PLACE, fortressCenter);

        final NbtCompound nbtCompound = new NbtCompound();
        nbtCompound.putUuid("fortressUUID", ((FortressServerPlayerEntity)player).getFortressUuid());
        nbtCompound.putInt("centerX", fortressCenter.getX());
        nbtCompound.putInt("centerY", fortressCenter.getY());
        nbtCompound.putInt("centerZ", fortressCenter.getZ());

        if(minX > fortressCenter.getX()-10) minX = fortressCenter.getX()-10;
        if(minZ > fortressCenter.getZ()-10) minZ = fortressCenter.getZ()-10;
        if(maxX < fortressCenter.getX()+10) maxX = fortressCenter.getX()+10;
        if(maxZ < fortressCenter.getZ()+10) maxZ = fortressCenter.getZ()+10;

        EntityType<?> colonistType = EntityType.get("minefortress:colonist").orElseThrow();
        Iterable<BlockPos> spawnPlaces = BlockPos.iterateRandomly(world.random, DEFAULT_COLONIST_COUNT, fortressCenter, 3);
        for(BlockPos spawnPlace : spawnPlaces) {
            int spawnY = world.getTopY(Heightmap.Type.WORLD_SURFACE, spawnPlace.getX(), spawnPlace.getZ());
            BlockPos spawnPos = new BlockPos(spawnPlace.getX(), spawnY, spawnPlace.getZ());
            colonistType.spawn(serverWorld, nbtCompound, null, player, spawnPos, SpawnReason.MOB_SUMMONED, true, false);
        }

        this.scheduleSync();
    }


    private void scheduleSync() {
        needSync = true;
    }

    private void scheduleSyncBuildings() {
        needSyncBuildings = true;
        this.scheduleSync();
    }

    private void scheduleSyncSpecialBlocks() {
        needSyncSpecialBlocks = true;
        this.scheduleSync();
    }

    public void writeToNbt(NbtCompound tag) {
        if(fortressCenter != null) {
            tag.putInt("centerX", fortressCenter.getX());
            tag.putInt("centerY", fortressCenter.getY());
            tag.putInt("centerZ", fortressCenter.getZ());
        }

        tag.putInt("minX", minX);
        tag.putInt("minZ", minZ);
        tag.putInt("maxX", maxX);
        tag.putInt("maxZ", maxZ);

        if(!buildings.isEmpty()) {
            int i = 0;
            final NbtCompound buildingsTag = new NbtCompound();
            for (FortressBulding building : this.buildings) {
                final NbtCompound buildingTag = new NbtCompound();
                building.writeToNbt(buildingTag);
                buildingsTag.put("building" + i++, buildingTag);
            }
            tag.put("buildings", buildingsTag);
        }

        final NbtCompound nameGeneratorTag = new NbtCompound();
        this.nameGenerator.write(nameGeneratorTag);
        tag.put("nameGenerator", nameGeneratorTag);

        if(!specialBlocks.isEmpty()) {
            final NbtCompound specialBlocksTag = new NbtCompound();
            for (Map.Entry<Block, List<BlockPos>> specialBlock : this.specialBlocks.entrySet()) {
                final String blockId = Registry.BLOCK.getId(specialBlock.getKey()).toString();
                final NbtList posList = new NbtList();
                for (BlockPos pos : specialBlock.getValue()) {
                    posList.add(NbtHelper.fromBlockPos(pos));
                }
                specialBlocksTag.put(blockId, posList);
            }
            tag.put("specialBlocks", specialBlocksTag);
        }

    }

    public void readFromNbt(NbtCompound tag) {
        final int centerX = tag.getInt("centerX");
        final int centerY = tag.getInt("centerY");
        final int centerZ = tag.getInt("centerZ");
        if(centerX != 0 || centerY != 0 || centerZ != 0) {
            fortressCenter = new BlockPos(centerX, centerY, centerZ);
        }

        if(tag.contains("minX")) minX = tag.getInt("minX");
        if(tag.contains("minZ")) minZ = tag.getInt("minZ");
        if(tag.contains("maxX")) maxX = tag.getInt("maxX");
        if(tag.contains("maxZ")) maxZ = tag.getInt("maxZ");

        if(tag.contains("buildings")) {
            final NbtCompound buildingsTag = tag.getCompound("buildings");
            int i = 0;
            while(buildingsTag.contains("building" + i)) {
                final NbtCompound buildingTag = buildingsTag.getCompound("building" + i++);
                FortressBulding building = new FortressBulding(buildingTag);
                buildings.add(building);
                this.scheduleSyncBuildings();
            }
        }

        if(tag.contains("nameGenerator")) {
            final NbtCompound nameGeneratorTag = tag.getCompound("nameGenerator");
            this.nameGenerator = new ColonistNameGenerator(nameGeneratorTag);
        }

        if (tag.contains("specialBlocks")) {
            final NbtCompound specialBlocksTag = tag.getCompound("specialBlocks");
            for (String blockId : specialBlocksTag.getKeys()) {
                final Block block = Registry.BLOCK.get(new Identifier(blockId));
                final NbtList posList = specialBlocksTag.getList(blockId, 0);
                final List<BlockPos> posList2 = new ArrayList<>();
                for (int j = 0; j < posList.size(); j++) {
                    posList2.add(NbtHelper.toBlockPos(posList.getCompound(j)));
                }
                this.specialBlocks.put(block, posList2);
            }
            this.scheduleSyncSpecialBlocks();
        }

        this.scheduleSync();
    }

    public ColonistNameGenerator getNameGenerator() {
        return nameGenerator;
    }

    public int getColonistsCount() {
        return colonists.size();
    }

    public BlockPos getFortressCenter() {
        return fortressCenter;
    }

    public Optional<BlockPos> randomSurfacePos(ServerWorld world){
        if(minX == Integer.MAX_VALUE) return Optional.empty();

        int x = world.random.nextInt(maxX - minX) + minX;
        int z = world.random.nextInt(maxZ - minZ) + minZ;
        int y = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
        return Optional.of(new BlockPos(x, y, z));
    }

    @Override
    public boolean hasRequiredBuilding(String requirementId) {
        return buildings.stream().anyMatch(b -> b.getRequirementId().equals(requirementId));
    }

    @Override
    public boolean hasRequiredBlock(Block block) {
        return this.specialBlocks.containsKey(block);
    }

    public boolean isBlockSpecial(Block block) {
        return block.equals(Blocks.CRAFTING_TABLE);
    }

    public void addSpecialBlocks(Block block, List<BlockPos> blockPos) {
        specialBlocks.computeIfAbsent(block, k -> new ArrayList<>()).addAll(blockPos);
        scheduleSyncSpecialBlocks();
    }

    @Override
    public int getTotalColonistsCount() {
        return this.colonists.size();
    }

    public ServerProfessionManager getServerProfessionManager() {
        return serverProfessionManager;
    }
}
