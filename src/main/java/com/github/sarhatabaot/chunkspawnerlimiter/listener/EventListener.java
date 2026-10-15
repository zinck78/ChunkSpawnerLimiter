package com.github.sarhatabaot.chunkspawnerlimiter.listener;


import com.github.sarhatabaot.chunkspawnerlimiter.CSLLogger;
import com.github.sarhatabaot.chunkspawnerlimiter.PluginConfig;
import com.github.sarhatabaot.chunkspawnerlimiter.chunk.ChunkCoord;
import com.github.sarhatabaot.chunkspawnerlimiter.counter.CounterData;
import com.github.sarhatabaot.chunkspawnerlimiter.counter.CounterDataManager;
import com.github.sarhatabaot.chunkspawnerlimiter.notification.NotificationService;
import com.github.sarhatabaot.chunkspawnerlimiter.removal.Checks;
import com.github.sarhatabaot.chunkspawnerlimiter.removal.NamedPriorityEnforcer;
import com.github.sarhatabaot.chunkspawnerlimiter.removal.modes.RemovalMode;
import com.github.sarhatabaot.chunkspawnerlimiter.tracker.EntityChunkTracker;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

public class EventListener implements Listener {
    private final Plugin plugin;
    private final PluginConfig pluginConfig;
    private final CounterDataManager counterDataManager;
    private final NotificationService notificationService;
    private final EntityChunkTracker chunkTracker;
    private final NamedPriorityEnforcer priorityEnforcer;

    public EventListener(Plugin plugin, PluginConfig pluginConfig,
                         CounterDataManager counterDataManager,
                         NotificationService notificationService,
                         EntityChunkTracker chunkTracker) {
        this(plugin, pluginConfig, counterDataManager, notificationService, chunkTracker, null);
    }

    public EventListener(Plugin plugin, PluginConfig pluginConfig,
                         CounterDataManager counterDataManager,
                         NotificationService notificationService,
                         EntityChunkTracker chunkTracker,
                         NamedPriorityEnforcer priorityEnforcer) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.counterDataManager = counterDataManager;
        this.notificationService = notificationService;
        this.chunkTracker = chunkTracker;
        this.priorityEnforcer = priorityEnforcer;
    }

    // -- Player lifecycle ----------------------------------------------------

    @EventHandler
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        notificationService.cleanup(event.getPlayer());
    }

    /** Called by the optional modern breeding-event bridge. */
    public void onCompatibleBreed(@NotNull Entity child, Entity breederEntity, @NotNull org.bukkit.event.Cancellable event) {
        if (!pluginConfig.isCreatureSpawnWatch()
                || !pluginConfig.getSpawnReasons().contains("BREEDING")
                || pluginConfig.isWorldDisabled(child.getWorld().getName())) return;
        EntityType type = child.getType();
        if (!Checks.shouldTrackEntity(child, pluginConfig)) return;

        Chunk chunk = child.getLocation().getChunk();
        if (!chunk.isLoaded()) return;
        var members = pluginConfig.getEntityGroupTotalMembers(type);
        var counts = counterDataManager.synchronizeEntityCounts(chunk, type, members, child);
        Integer speciesCap = pluginConfig.getResolvedEntityLimit(type);
        Integer groupCap = members == null || members.isEmpty()
                ? null : pluginConfig.getEntityGroupTotalLimit(type);

        boolean speciesFull = speciesCap != null && counts.typeCount() >= speciesCap;
        boolean groupFull = groupCap != null && counts.groupCount() >= groupCap;
        if (!speciesFull && !groupFull) return;

        event.setCancelled(true);
        Player breeder = breederEntity instanceof Player player ? player : null;
        int count = speciesFull ? counts.typeCount() : counts.groupCount();
        int cap = speciesFull ? speciesCap : groupCap;
        notificationService.notifyBreedingBlocked(breeder, chunk, type, count, cap, !speciesFull);
        queueSpawnOverflowCleanup(chunk, counts.typeCount(), speciesCap,
                counts.groupCount(), groupCap);
    }

    /**
     * A rejected spawn is NOT an extra existing entity. Only clean up when
     * the resident population is already over its cap (e.g. transported mobs).
     * At exactly the cap we cancel the incoming spawn and do not scan again.
     * The named-priority enforcer deduplicates queued cleanup requests.
     */
    private void queueSpawnOverflowCleanup(Chunk chunk,
                                              int typeCount, Integer typeLimit,
                                              int groupCount, Integer groupLimit) {
        if (priorityEnforcer == null || !pluginConfig.shouldTrimExistingOverflow()) return;
        if ((typeLimit != null && typeCount > typeLimit)
                || (groupLimit != null && groupCount > groupLimit)) {
            priorityEnforcer.queue(ChunkCoord.from(chunk));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNameTagApplied(@NotNull PlayerInteractEntityEvent event) {
        if (priorityEnforcer == null || !pluginConfig.shouldTrimExistingOverflow()) return;
        // Main source set targets an older Spigot API; reflect the hand
        // introduced later so both legacy and modern server builds compile.
        org.bukkit.inventory.ItemStack held = event.getPlayer().getItemInHand();
        try {
            Object hand = event.getClass().getMethod("getHand").invoke(event);
            if ("OFF_HAND".equals(hand.toString())) {
                held = (org.bukkit.inventory.ItemStack) event.getPlayer().getInventory()
                        .getClass().getMethod("getItemInOffHand")
                        .invoke(event.getPlayer().getInventory());
            }
        } catch (ReflectiveOperationException ignored) {
            // Older Bukkit has no offhand.
        }
        if (held == null || held.getType() != Material.NAME_TAG) return;
        Entity target = event.getRightClicked();
        if (!Checks.shouldTrackEntity(target, pluginConfig)
                || pluginConfig.isWorldDisabled(target.getWorld().getName())) return;
        // Interaction fires before vanilla applies the tag. Recheck after it is named.
        priorityEnforcer.queue(ChunkCoord.from(target));
    }

    // -- Block events --------------------------------------------------------

    @EventHandler
    public void onBlockPlace(@NotNull BlockPlaceEvent event) {
        if (pluginConfig.isWorldDisabled(event.getBlock().getWorld().getName())) {
            CSLLogger.debug(() -> "%s world is disabled.".formatted(event.getBlock().getWorld().getName()));
            return;
        }

        final Material material = event.getBlock().getType();
        if (!pluginConfig.hasResolvedBlockLimit(material)) {
            CSLLogger.debug(() -> "%s block not in block limits.".formatted(material.name()));
            return;
        }

        final ChunkCoord chunkCoord = ChunkCoord.from(event.getBlock().getLocation());
        final CounterData counterData = counterDataManager.getCounterData(chunkCoord);

        if (Checks.isUnderOrEqualToLimit(counterData.getBlockCount(material),  pluginConfig.getResolvedBlockLimit(material))) {
            CSLLogger.debug(() -> "%s block under block limits (%d/%d)".formatted(material.name(), counterData.getBlockCount(material), pluginConfig.getResolvedBlockLimit(material)));
            counterData.incrementBlock(material);
            return;
        }

        notificationService.notifyBlockLimitReached(
            event.getPlayer(),
            material,
            pluginConfig.getResolvedBlockLimit(material)
        );

        RemovalMode removalMode = pluginConfig.getRemovalMode();
        removalMode.handleBlock(event.getBlock(), event);
    }

    @EventHandler
    public void onBlockBreak(@NotNull BlockBreakEvent event) {
        if (pluginConfig.isWorldDisabled(event.getBlock().getWorld().getName())) {
            return;
        }

        final Material type = event.getBlock().getType();
        // No block limits on Actuality: don't touch counters for every mine.
        if (!pluginConfig.hasResolvedBlockLimit(type)) return;
        final ChunkCoord chunkCoord = ChunkCoord.from(event.getBlock().getLocation());
        counterDataManager.getCounterData(chunkCoord).decrementBlock(type);
    }

    // -- Entity spawn events -------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitySpawn(@NotNull EntitySpawnEvent event) {
        if (event instanceof CreatureSpawnEvent) {
            if (!pluginConfig.isCreatureSpawnWatch()) {
                return;
            }
        } else if (!pluginConfig.isEntitySpawnWatch()) {
            return;
        }

        if (pluginConfig.isWorldDisabled(event.getLocation().getWorld().getName())) {
            CSLLogger.debug(() -> "%s world is disabled.".formatted(event.getLocation().getWorld().getName()));
            return;
        }

        if (event instanceof CreatureSpawnEvent creatureSpawnEvent) {
            String spawnReason = creatureSpawnEvent.getSpawnReason().name();
            if (!pluginConfig.getSpawnReasons().contains(spawnReason)) {
                CSLLogger.debug(() -> "%s entity spawn ignored due to spawn reason: %s".formatted(event.getEntity().getType().name(), spawnReason));
                return;
            }
        }

        final Entity entity = event.getEntity();
        final EntityType entityType = entity.getType();
        if (!Checks.shouldTrackEntity(entity, pluginConfig)) {
            CSLLogger.debug(() -> "%s entity not in entity limits.".formatted(entityType.name()));
            return;
        }

        final Chunk chunk = entity.getLocation().getChunk();
        if (!chunk.isLoaded()) {
            CSLLogger.debug(() -> "Chunk not loaded for entity spawn: %s".formatted(entityType.name()));
            return;
        }

        // Stacking-plugin compatibility: defer the limit check AND the counter
        // update to the next tick. By then, WildStacker/RoseStacker will have
        // merged transient spawn entities into stacked entities, so we count
        // the actual stacks (1 per stack, not 1 per individual entity).
        if (pluginConfig.shouldDelayEntityCountForCompatibility()) {
            scheduleEntityCountFinalization(entity);
            return;
        }

        final ChunkCoord chunkCoord = ChunkCoord.from(chunk);
        final CounterData counterData = counterDataManager.getCounterData(chunkCoord);
        final var groupMembers = pluginConfig.getEntityGroupTotalMembers(entityType);
        final var counts = counterDataManager.synchronizeEntityCounts(
                chunk, entityType, groupMembers, entity);

        final Integer entityTypeLimit = pluginConfig.getResolvedEntityLimit(entityType);
        final Integer groupTotalLimit = groupMembers == null || groupMembers.isEmpty()
                ? null : pluginConfig.getEntityGroupTotalLimit(entityType);

        boolean withinTypeLimit = entityTypeLimit == null ||
            Checks.isUnderOrEqualToLimit(counts.typeCount(), entityTypeLimit);
        boolean withinGroupLimit = groupTotalLimit == null ||
            Checks.isUnderOrEqualToLimit(counts.groupCount(), groupTotalLimit);

        if ((!withinTypeLimit || !withinGroupLimit) && priorityEnforcer != null
                && entity.getCustomName() != null
                && priorityEnforcer.makeRoomForNamedSpawn(chunk, entity)) {
            // A named arrival replaced lower-priority unnamed residents.
            var refreshed = counterDataManager.synchronizeEntityCounts(chunk, entityType, groupMembers, entity);
            withinTypeLimit = entityTypeLimit == null
                    || Checks.isUnderOrEqualToLimit(refreshed.typeCount(), entityTypeLimit);
            withinGroupLimit = groupTotalLimit == null
                    || Checks.isUnderOrEqualToLimit(refreshed.groupCount(), groupTotalLimit);
        }

        if (withinTypeLimit && withinGroupLimit) {
            CSLLogger.debug(() -> "%s entity under entity limits (type: %d/%s)".formatted(
                entityType.name(),
                counts.typeCount(),
                entityTypeLimit != null ? String.valueOf(entityTypeLimit) : "unlimited"
            ));

            counterData.incrementEntity(entityType);
            // Track entity for cross-chunk movement detection
            chunkTracker.recordEntry(entity);
            return;
        }

        if (event instanceof CreatureSpawnEvent creatureEvent
                && creatureEvent.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BREEDING) {
            int count = !withinTypeLimit ? counts.typeCount() : counts.groupCount();
            int cap = !withinTypeLimit ? entityTypeLimit : groupTotalLimit;
            notificationService.notifyBreedingBlocked(null, chunk, entityType, count, cap, withinTypeLimit);
        } else {
            notificationService.notifyEntitiesBlocked(chunk, entityType, 1);
        }

        // Whether natural, egg/command/custom, or a breeding fallback,
        // a rejected spawn is a convenient on-demand opportunity to reconcile
        // pre-existing overflow without a periodic movement or chunk scan.
        queueSpawnOverflowCleanup(chunk, counts.typeCount(), entityTypeLimit,
                counts.groupCount(), groupTotalLimit);

        RemovalMode removalMode = pluginConfig.getRemovalMode();
        removalMode.handleEntity(entity, event);
        // Note: we intentionally do NOT drop a refund egg when the event is cancelled.
        // On modern Paper/Spigot, cancelling CreatureSpawnEvent prevents the entity
        // from spawning, but the player's spawn-egg item has already been consumed
        // from the hand. Dropping a new egg would create an unlimited dupe exploit.
        // (See bug report: "i set the villager limit to 5... i keep my own egg" dupe.)
    }

    // -- Entity death / removal events --------------------------------------

    @EventHandler
    public void onEntityDeath(@NotNull EntityDeathEvent event) {
        if (pluginConfig.isWorldDisabled(event.getEntity().getWorld().getName())) {
            return;
        }

        final Entity entity = event.getEntity();
        // Never create/modify chunk counters for unconfigured mobs (e.g. bats).
        if (!pluginConfig.getResolvedEntityTypes().contains(entity.getType())
                || !Checks.shouldTrackEntity(entity, pluginConfig)) return;
        final ChunkCoord chunkCoord = ChunkCoord.from(entity.getLocation());
        final CounterData counterData = counterDataManager.getCounterData(chunkCoord);

        counterData.decrementEntity(entity.getType());
        chunkTracker.recordExit(entity);
    }

    // -- Entity portal (cross-dimension) ------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPortal(@NotNull EntityPortalEvent event) {
        if (event.isCancelled() || event.getFrom().getWorld() == null) {
            return;
        }

        final Entity entity = event.getEntity();
        if (!Checks.shouldTrackEntity(entity, pluginConfig)) return;

        final ChunkCoord sourceCoord = ChunkCoord.from(event.getFrom());
        final EntityType sourceType = entity.getType();
        final boolean sourceTracked = !pluginConfig.isWorldDisabled(event.getFrom().getWorld().getName());
        plugin.getServer().getScheduler().runTask(plugin,
                () -> reconcilePortalTransition(entity, sourceCoord, sourceType, sourceTracked));
    }

    // -- Entity transformation (pig→zombified piglin, etc.) -----------------

    private void reconcilePortalTransition(@NotNull Entity entity, @NotNull ChunkCoord sourceCoord,
                                           @NotNull EntityType sourceType, boolean sourceTracked) {
        if (!entity.isValid()) {
            if (sourceTracked) {
                counterDataManager.decrementEntityIfPresent(sourceCoord, sourceType);
            }
            chunkTracker.recordExit(entity);
            return;
        }

        final ChunkCoord destinationCoord = ChunkCoord.from(entity);
        final boolean destinationTracked = !pluginConfig.isWorldDisabled(entity.getWorld().getName())
                && Checks.shouldTrackEntity(entity, pluginConfig);

        if (!sourceCoord.equals(destinationCoord)) {
            if (sourceTracked) {
                counterDataManager.decrementEntityIfPresent(sourceCoord, sourceType);
            }
            if (destinationTracked) {
                counterDataManager.getCounterData(destinationCoord).incrementEntity(entity.getType());
            }
        }

        if (destinationTracked) {
            chunkTracker.recordEntry(entity);
        } else {
            chunkTracker.recordExit(entity);
        }

        CSLLogger.debug(() -> "Entity portal: %s moved from %s to %s"
                .formatted(entity.getType().name(), sourceCoord, destinationCoord));
    }

    @EventHandler
    public void onPigZap(@NotNull PigZapEvent event) {
        if (pluginConfig.isWorldDisabled(event.getEntity().getWorld().getName())) {
            return;
        }

        // The pig is being transformed to a zombified piglin.
        // Decrement PIG counter; the new ZOMBIFIED_PIGLIN will fire
        // its own CreatureSpawnEvent (LIGHTNING reason) which we handle.
        final Pig pig = event.getEntity();
        if (pluginConfig.hasResolvedEntityLimit(EntityType.PIG)) {
            final ChunkCoord coord = ChunkCoord.from(pig.getLocation());
            counterDataManager.getCounterData(coord).decrementEntity(EntityType.PIG);
            chunkTracker.recordExit(pig);
            CSLLogger.debug(() -> "Pig zapped in %s".formatted(coord));
        }
    }

    // -- Vehicle events -----------------------------------------------------

    @EventHandler
    public void onVehicleCreate(@NotNull VehicleCreateEvent event) {
        if (!pluginConfig.isVehicleSpawnWatch()) {
            return;
        }

        if (pluginConfig.isWorldDisabled(event.getVehicle().getWorld().getName())) {
            return;
        }

        final Entity vehicle = event.getVehicle();
        final EntityType vehicleType = vehicle.getType();
        if (!Checks.shouldTrackEntity(vehicle, pluginConfig)) {
            return;
        }

        final Chunk chunk = vehicle.getLocation().getChunk();
        if (!chunk.isLoaded()) {
            return;
        }

        final ChunkCoord chunkCoord = ChunkCoord.from(chunk);
        final CounterData counterData = counterDataManager.getCounterData(chunkCoord);

        final var groupMembers = pluginConfig.getEntityGroupTotalMembers(vehicleType);
        final var counts = counterDataManager.synchronizeEntityCounts(
                chunk, vehicleType, groupMembers, vehicle);
        final Integer vehicleTypeLimit = pluginConfig.getResolvedEntityLimit(vehicleType);
        final Integer groupTotalLimit = groupMembers == null || groupMembers.isEmpty()
                ? null : pluginConfig.getEntityGroupTotalLimit(vehicleType);
        boolean withinTypeLimit = vehicleTypeLimit == null ||
            Checks.isUnderOrEqualToLimit(counts.typeCount(), vehicleTypeLimit);
        boolean withinGroupLimit = groupTotalLimit == null ||
            Checks.isUnderOrEqualToLimit(counts.groupCount(), groupTotalLimit);

        if (withinTypeLimit && withinGroupLimit) {
            counterData.incrementEntity(vehicleType);
            chunkTracker.recordEntry(vehicle);
            return;
        }

        RemovalMode removalMode = pluginConfig.getRemovalMode();
        removalMode.handleEntity(vehicle, null);
    }

    @EventHandler
    public void onVehicleDestroy(@NotNull VehicleDestroyEvent event) {
        if (pluginConfig.isWorldDisabled(event.getVehicle().getWorld().getName())) {
            return;
        }

        final Entity vehicle = event.getVehicle();
        if (!pluginConfig.getResolvedEntityTypes().contains(vehicle.getType())
                || !Checks.shouldTrackEntity(vehicle, pluginConfig)) return;
        final ChunkCoord chunkCoord = ChunkCoord.from(vehicle.getLocation());
        final CounterData counterData = counterDataManager.getCounterData(chunkCoord);

        counterData.decrementEntity(vehicle.getType());
        chunkTracker.recordExit(vehicle);
    }

    // -- Internal helpers ---------------------------------------------------

    private void scheduleEntityCountFinalization(@NotNull Entity entity) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            // --- Stale-event guards -------------------------------------------------
            if (!entity.isValid()) {
                return;
            }

            if (pluginConfig.isWorldDisabled(entity.getWorld().getName())) {
                return;
            }

            if (!Checks.shouldTrackEntity(entity, pluginConfig)) {
                return;
            }

            final Chunk chunk = entity.getLocation().getChunk();
            if (!chunk.isLoaded()) {
                return;
            }

            // --- Stacking-plugin aware limit enforcement -----------------------------
            // Count actual entities in the chunk (each WildStacker stack is 1 entity),
            // then either sync the counter (under limit) or remove excess (over limit).
            final ChunkCoord chunkCoord = ChunkCoord.from(chunk);
            final CounterData counterData = counterDataManager.getCounterData(chunkCoord);
            final EntityType entityType = entity.getType();
            final Integer entityTypeLimit = pluginConfig.getResolvedEntityLimit(entityType);

            int actualCount = 0;
            int actualGroupCount = 0;
            final var groupMembers = pluginConfig.getEntityGroupTotalMembers(entityType);
            final Integer groupTotalLimit = groupMembers == null || groupMembers.isEmpty()
                    ? null : pluginConfig.getEntityGroupTotalLimit(entityType);
            for (Entity e : chunk.getEntities()) {
                if (!e.isValid() || e.isDead()) continue;
                if (e.getType() == entityType) actualCount++;
                if (groupMembers != null && groupMembers.contains(e.getType())) actualGroupCount++;
            }

            // Sync counter from actual chunk state (each stack = 1, not N).
            counterData.setEntityCount(entityType, actualCount);

            final int typeExcess = entityTypeLimit == null ? 0 : Math.max(0, actualCount - entityTypeLimit);
            final int groupExcess = groupTotalLimit == null ? 0 : Math.max(0, actualGroupCount - groupTotalLimit);
            if (typeExcess > 0 || groupExcess > 0) {
                // Only the newly spawned type is eligible; never delete existing
                // members of another species to make room for a new spawn.
                int toRemove = Math.max(typeExcess, groupExcess);
                final int fActualCount = actualCount;
                CSLLogger.debug(() -> "Stacking compat: chunk %s has %d %s; type limit %s, shared group limit %s; rejecting up to %d"
                        .formatted(chunkCoord, fActualCount, entityType.name(),
                                entityTypeLimit == null ? "unlimited" : entityTypeLimit,
                                groupTotalLimit == null ? "unlimited" : groupTotalLimit, toRemove));

                int removed = 0;
                for (Entity e : chunk.getEntities()) {
                    if (removed >= toRemove) break;
                    if (e.getType() != entityType) continue;
                    // Prefer to remove the entity that just spawned (the one this
                    // deferred task is for) so existing stacks remain intact.
                    if (e.equals(entity)) {
                        RemovalMode removalMode = pluginConfig.getRemovalMode();
                        removalMode.handleDeferredEntity(e);
                        if (!e.isValid() || e.isDead()) {
                            actualCount = Math.max(0, actualCount - 1);
                            counterData.setEntityCount(entityType, actualCount);
                            removed++;
                        }
                    } else if (e.getTicksLived() < 5) {
                        // Recent spawn — likely a transient pre-merge entity. Remove
                        // to enforce the limit without disturbing existing stacks.
                        e.remove();
                        if (!e.isValid() || e.isDead()) {
                            counterData.decrementEntity(entityType);
                            actualCount = Math.max(0, actualCount - 1);
                            chunkTracker.recordExit(e);
                            removed++;
                        }
                    }
                }
            }

            if (entity.isValid() && !entity.isDead()) {
                chunkTracker.recordEntry(entity);
            } else {
                chunkTracker.recordExit(entity);
            }
        });
    }

}
