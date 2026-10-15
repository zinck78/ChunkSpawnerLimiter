package com.github.sarhatabaot.chunkspawnerlimiter.listener;

import com.github.sarhatabaot.chunkspawnerlimiter.PluginConfig;
import com.github.sarhatabaot.chunkspawnerlimiter.chunk.ChunkCoord;
import com.github.sarhatabaot.chunkspawnerlimiter.counter.CounterDataManager;
import com.github.sarhatabaot.chunkspawnerlimiter.reflection.scanner.BlockScanner;
import com.github.sarhatabaot.chunkspawnerlimiter.reflection.scanner.BlockScannerFactory;
import com.github.sarhatabaot.chunkspawnerlimiter.removal.Checks;
import com.github.sarhatabaot.chunkspawnerlimiter.removal.RemovalTaskManager;
import com.github.sarhatabaot.chunkspawnerlimiter.removal.NamedPriorityEnforcer;
import com.github.sarhatabaot.chunkspawnerlimiter.removal.modes.RemovalMode;
import com.github.sarhatabaot.chunkspawnerlimiter.tracker.EntityChunkTracker;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;


public class ChunkListener implements Listener {
    private final Plugin plugin;
    private final PluginConfig pluginConfig;
    private final CounterDataManager counterDataManager;
    private final RemovalTaskManager removalTaskManager;
    private final EntityChunkTracker chunkTracker;
    private final BlockScanner blockScanner;
    private final NamedPriorityEnforcer priorityEnforcer;
    // When supported, entity lifecycle events initialize the tracker without
    // forcing entity data to load from ChunkLoadEvent.
    private boolean entityLoadEventsAvailable;

    public void setEntityLoadEventsAvailable(boolean available) {
        this.entityLoadEventsAvailable = available;
    }

    public boolean hasEntityLoadEventsAvailable() {
        return entityLoadEventsAvailable;
    }

    public ChunkListener(Plugin plugin, PluginConfig pluginConfig,
                         CounterDataManager counterDataManager,
                         RemovalTaskManager removalTaskManager,
                         EntityChunkTracker chunkTracker) {
        this(plugin, pluginConfig, counterDataManager, removalTaskManager, chunkTracker, null);
    }

    public ChunkListener(Plugin plugin, PluginConfig pluginConfig,
                         CounterDataManager counterDataManager,
                         RemovalTaskManager removalTaskManager,
                         EntityChunkTracker chunkTracker,
                         NamedPriorityEnforcer priorityEnforcer) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.counterDataManager = counterDataManager;
        this.removalTaskManager = removalTaskManager;
        this.chunkTracker = chunkTracker;
        this.priorityEnforcer = priorityEnforcer;
        this.blockScanner = BlockScannerFactory.create(plugin, pluginConfig, counterDataManager);
    }

    @EventHandler
    public void onChunkLoad(@NotNull ChunkLoadEvent event) {
        if (pluginConfig.isWorldDisabled(event.getWorld().getName())) {
            return;
        }

        initializeChunk(event.getChunk());
    }

    private void initializeChunk(@NotNull Chunk chunk) {
        final ChunkCoord chunkCoord = ChunkCoord.from(chunk);

        // Do not build a redundant per-chunk entity cache or register every
        // animal when only spawn/breeding checks are wanted. Those checks count
        // the actual live entities at the time of the event.
        if (!entityLoadEventsAvailable) {
            if (chunkTracker.isEnabled()) {
                addEntityLimits(chunk, chunkCoord);
            }
            // Legacy fallback when entity lifecycle events are unavailable.
            if (priorityEnforcer != null && pluginConfig.shouldTrimExistingOverflow()) {
                priorityEnforcer.queue(chunkCoord);
            }
        }
        
        // Actuality has no block limits: avoid invoking the block scanner
        // at all. Retain the existing scanner for configured block caps.
        if (!pluginConfig.getTrackedBlockMaterials().isEmpty()) {
            blockScanner.scanChunk(chunk, chunkCoord, true);
        }

        RemovalMode removalMode = pluginConfig.getRemovalMode();
        if (removalMode.removesExistingEntities()) {
            removalTaskManager.queueChunkCheck(chunkCoord, removalMode.getEntityRemovalAction());

            if (pluginConfig.isActiveInspections()) {
                removalTaskManager.scheduleRecheck(chunkCoord, removalMode.getEntityRemovalAction(),
                        pluginConfig.getInspectionFrequencySeconds());
            }
        }
    }

    @EventHandler
    public void onChunkUnload(@NotNull ChunkUnloadEvent event) {
        if (pluginConfig.isWorldDisabled(event.getWorld().getName())) {
            return;
        }

        final ChunkCoord chunkCoord = ChunkCoord.from(event.getChunk());
        blockScanner.cancelScan(chunkCoord);
        chunkTracker.forgetChunk(chunkCoord);
        counterDataManager.removeCounterData(chunkCoord);
        removalTaskManager.removeChunkRecheck(chunkCoord);
    }

    private void addEntityLimits(final @NotNull Chunk chunk, final ChunkCoord chunkCoord) {
        final Entity[] entities = chunk.getEntities();
        for (Entity entity: entities) {
            // Legacy fallback for Actuality: initialize the movement cache
            // exclusively from villagers, not every limited livestock type.
            if (pluginConfig.isVillagerOnlyMovementTracking()
                    && entity.getType() != EntityType.VILLAGER) continue;
            if (!Checks.shouldTrackEntity(entity, pluginConfig)) {
                continue;
            }

            counterDataManager.getCounterData(chunkCoord).incrementEntity(entity.getType());
            // Register with the cross-chunk movement tracker
            chunkTracker.recordEntry(entity);
        }
    }

    /**
     * Called by EntitiesLoadEvent on modern servers. Uses the event-provided
     * list instead of calling Chunk#getEntities() in the chunk-load callback.
     * Other mob types (including bats) are never registered for movement.
     */
    public void onEntitiesLoaded(@NotNull Chunk chunk, @NotNull Iterable<Entity> entities) {
        if (pluginConfig.isWorldDisabled(chunk.getWorld().getName())) return;
        ChunkCoord coord = ChunkCoord.from(chunk);
        boolean anyLimitedEntity = false;

        for (Entity entity : entities) {
            // Resolve configured types once; a bat cannot enter any limit or
            // movement logic unless an operator explicitly configures BAT.
            if (!pluginConfig.getResolvedEntityTypes().contains(entity.getType())
                    || !Checks.shouldTrackEntity(entity, pluginConfig)) continue;
            anyLimitedEntity = true;
            if (chunkTracker.isEnabled() && entity.getType() == EntityType.VILLAGER
                    && chunkTracker.recordEntryIfNew(entity, coord)) {
                counterDataManager.getCounterData(coord).incrementEntity(EntityType.VILLAGER);
            }
        }

        // Preserve one-off population cleanup after the entities have loaded.
        if (anyLimitedEntity && priorityEnforcer != null && pluginConfig.shouldTrimExistingOverflow()) {
            priorityEnforcer.queue(coord);
        }
    }

    /** Remove unloaded villagers from the bounded movement tracker. */
    public void onEntitiesUnloaded(@NotNull Chunk chunk, @NotNull Iterable<Entity> entities) {
        if (pluginConfig.isWorldDisabled(chunk.getWorld().getName()) || !chunkTracker.isEnabled()) return;
        ChunkCoord coord = ChunkCoord.from(chunk);
        for (Entity entity : entities) {
            if (entity.getType() != EntityType.VILLAGER) continue;
            // An unload can fire twice or after a prior death/transform.
            // Only decrement a villager that this tracker actually owned.
            if (chunkTracker.removeIfTrackedInChunk(entity, coord)) {
                counterDataManager.decrementEntityIfPresent(coord, EntityType.VILLAGER);
            }
        }
    }

    /**
     * One-time POSTWORLD startup bootstrap. Paper may have loaded spawn chunks
     * before plugin listeners existed. Only enumerate already-loaded entities.
     * This does not capture block snapshots or load additional chunks.
     */
    public void bootstrapAlreadyLoadedEntities() {
        if (!entityLoadEventsAvailable) return;
        for (World world : plugin.getServer().getWorlds()) {
            if (pluginConfig.isWorldDisabled(world.getName())) continue;
            for (Chunk chunk : world.getLoadedChunks()) {
                if (EntityLoadBridge.areEntitiesLoaded(chunk)) {
                    onEntitiesLoaded(chunk, java.util.Arrays.asList(chunk.getEntities()));
                }
            }
        }
    }

    public void rebuildLoadedChunks() {
        for (World world : plugin.getServer().getWorlds()) {
            if (pluginConfig.isWorldDisabled(world.getName())) {
                continue;
            }
            for (Chunk chunk : world.getLoadedChunks()) {
                initializeChunk(chunk);
                // A plugin reload can find entities already loaded. Never
                // request a Chunk#getEntities() unless Paper says they are ready.
                if (entityLoadEventsAvailable && EntityLoadBridge.areEntitiesLoaded(chunk)) {
                    onEntitiesLoaded(chunk, java.util.Arrays.asList(chunk.getEntities()));
                }
            }
        }
    }

    public void shutdown() {
        blockScanner.shutdown();
    }
}
