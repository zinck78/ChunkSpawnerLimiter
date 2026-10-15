package com.github.sarhatabaot.chunkspawnerlimiter.tracker;

import com.github.sarhatabaot.chunkspawnerlimiter.CSLLogger;
import com.github.sarhatabaot.chunkspawnerlimiter.chunk.ChunkCoord;
import com.github.sarhatabaot.chunkspawnerlimiter.counter.CounterDataManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Consumer;

/**
 * Tracks which chunk each entity is in so that when an entity moves between
 * chunks, the counter cache can be updated accordingly.
 * <p>
 * A repeating task polls tracked entities every few seconds to detect chunk
 * changes.
 */
public class EntityChunkTracker {
    private static final int DEFAULT_MAX_ENTITIES_PER_POLL = 256;

    private final Map<UUID, TrackedEntity> trackedEntities = new ConcurrentHashMap<>();
    // All Bukkit entity events and the scheduled poll run on the server thread.
    // LinkedHashSet preserves round-robin order and supports O(1) removals,
    // unlike ConcurrentLinkedQueue.remove(Object)'s linear traversal.
    private final Set<UUID> pollingQueue = new LinkedHashSet<>();
    private final CounterDataManager counterDataManager;
    private final Predicate<Entity> isTracked;
    private final Plugin plugin;
    private final long intervalTicks;
    private final int maxEntitiesPerPoll;
    private final boolean enabled;
    private Consumer<ChunkCoord> arrivalHandler = coord -> { };

    public void setArrivalHandler(Consumer<ChunkCoord> arrivalHandler) {
        this.arrivalHandler = arrivalHandler;
    }

    /**
     * No entity map and no periodic poll in lazy mode. Spawn and breeding
     * enforcement uses the current chunk's live entities directly.
     */
    public static EntityChunkTracker disabled(Plugin plugin, CounterDataManager counterDataManager) {
        return new EntityChunkTracker(plugin, counterDataManager, entity -> false, 40L,
                DEFAULT_MAX_ENTITIES_PER_POLL, false, false);
    }

    public EntityChunkTracker(Plugin plugin,
                              CounterDataManager counterDataManager,
                              Predicate<Entity> isTracked,
                              long intervalTicks) {
        this(plugin, counterDataManager, isTracked, intervalTicks,
                DEFAULT_MAX_ENTITIES_PER_POLL, true);
    }

    EntityChunkTracker(Plugin plugin,
                       CounterDataManager counterDataManager,
                       Predicate<Entity> isTracked,
                       long intervalTicks,
                       boolean startPolling) {
        this(plugin, counterDataManager, isTracked, intervalTicks,
                DEFAULT_MAX_ENTITIES_PER_POLL, startPolling);
    }

    EntityChunkTracker(Plugin plugin,
                       CounterDataManager counterDataManager,
                       Predicate<Entity> isTracked,
                       long intervalTicks,
                       int maxEntitiesPerPoll,
                       boolean startPolling) {
        this(plugin, counterDataManager, isTracked, intervalTicks,
                maxEntitiesPerPoll, startPolling, true);
    }

    private EntityChunkTracker(Plugin plugin,
                               CounterDataManager counterDataManager,
                               Predicate<Entity> isTracked,
                               long intervalTicks,
                               int maxEntitiesPerPoll,
                               boolean startPolling,
                               boolean enabled) {
        this.plugin = plugin;
        this.counterDataManager = counterDataManager;
        this.isTracked = isTracked;
        this.intervalTicks = intervalTicks;
        this.maxEntitiesPerPoll = Math.max(1, maxEntitiesPerPoll);
        this.enabled = enabled;
        if (enabled && startPolling) {
            startPolling();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    private void startPolling() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::pollEntityMovements,
                intervalTicks, intervalTicks);
    }

    /**
     * Record that an entity exists in a specific chunk (call on spawn/world-entry).
     */
    public void recordEntry(@NotNull Entity entity) {
        recordEntryIfNew(entity);
    }

    /**
     * Register loaded entities idempotently: entity load and reload bootstrap
     * can sometimes refer to the same entity. Never double count its UUID.
     */
    public boolean recordEntryIfNew(@NotNull Entity entity) {
        if (!enabled || !isTracked.test(entity)) return false;
        UUID uuid = entity.getUniqueId();
        TrackedEntity old = trackedEntities.put(uuid, TrackedEntity.from(entity));
        pollingQueue.add(uuid);
        return old == null;
    }

    /**
     * Remove tracking for an entity (call on death/despawn/world-exit).
     */
    public void recordExit(@NotNull Entity entity) {
        if (!enabled) return;
        UUID uuid = entity.getUniqueId();
        trackedEntities.remove(uuid);
        pollingQueue.remove(uuid);
    }

    public void forgetChunk(@NotNull ChunkCoord chunkCoord) {
        if (!enabled) return;
        for (Map.Entry<UUID, TrackedEntity> entry : trackedEntities.entrySet()) {
            if (entry.getValue().chunkCoord().equals(chunkCoord)) {
                if (trackedEntities.remove(entry.getKey(), entry.getValue())) {
                    pollingQueue.remove(entry.getKey());
                }
            }
        }
    }

    /**
     * Called periodically to check if any tracked entities have changed chunks
     * and update counters accordingly.
     */
    void pollEntityMovements() {
        if (!enabled) return;
        // Never visit the same entity twice per poll when fewer than 256
        // entities exist; this also bounds work under high population.
        int batchSize = Math.min(maxEntitiesPerPoll, pollingQueue.size());
        for (int processed = 0; processed < batchSize; processed++) {
            Iterator<UUID> iterator = pollingQueue.iterator();
            UUID uuid = iterator.next();
            iterator.remove();

            TrackedEntity previous = trackedEntities.get(uuid);
            if (previous != null && reconcileTrackedEntity(uuid, previous)) {
                pollingQueue.add(uuid);
            }
        }
    }

    private boolean reconcileTrackedEntity(UUID uuid, TrackedEntity previous) {
        Entity entity = previous.entity();
        if (!entity.isValid() || entity.isDead()) {
            if (trackedEntities.remove(uuid, previous)) {
                counterDataManager.decrementEntityIfPresent(previous.chunkCoord(), previous.type());
            }
            return false;
        }

        if (!isTracked.test(entity)) {
            counterDataManager.decrementEntityIfPresent(previous.chunkCoord(), previous.type());
            trackedEntities.remove(uuid, previous);
            return false;
        }

        EntityType currentType = entity.getType();
        ChunkCoord currentCoord = ChunkCoord.from(entity);
        if (previous.type() != currentType || !previous.chunkCoord().equals(currentCoord)) {
            counterDataManager.decrementEntityIfPresent(previous.chunkCoord(), previous.type());
            counterDataManager.getCounterData(currentCoord).incrementEntity(currentType);
            trackedEntities.put(uuid, new TrackedEntity(currentCoord, currentType, entity));
            arrivalHandler.accept(currentCoord);
        }
        return true;
    }

    void reconcileEntities(Iterable<Entity> currentEntities) {
        if (!enabled) return;
        int movesDetected = 0;
        Set<UUID> presentEntities = new HashSet<>();

        for (Entity entity : currentEntities) {
            UUID uuid = entity.getUniqueId();
            TrackedEntity previous = trackedEntities.get(uuid);
            if (previous == null || !entity.isValid()) {
                continue;
            }

            presentEntities.add(uuid);
            EntityType currentType = entity.getType();
            ChunkCoord currentCoord = ChunkCoord.from(entity);

            if (!isTracked.test(entity)) {
                counterDataManager.decrementEntityIfPresent(previous.chunkCoord(), previous.type());
                trackedEntities.remove(uuid, previous);
                movesDetected++;
                continue;
            }

            if (previous.type() != currentType || !previous.chunkCoord().equals(currentCoord)) {
                counterDataManager.decrementEntityIfPresent(previous.chunkCoord(), previous.type());
                counterDataManager.getCounterData(currentCoord).incrementEntity(currentType);
                trackedEntities.put(uuid, new TrackedEntity(currentCoord, currentType, entity));
                arrivalHandler.accept(currentCoord);
                movesDetected++;
            }
        }

        int removalsDetected = 0;
        for (Map.Entry<UUID, TrackedEntity> entry : trackedEntities.entrySet()) {
            if (presentEntities.contains(entry.getKey())) {
                continue;
            }

            TrackedEntity removed = entry.getValue();
            if (trackedEntities.remove(entry.getKey(), removed)) {
                counterDataManager.decrementEntityIfPresent(removed.chunkCoord(), removed.type());
                removalsDetected++;
            }
        }

        if (movesDetected > 0 || removalsDetected > 0) {
            final int finalMoves = movesDetected;
            final int finalRemovals = removalsDetected;
            CSLLogger.debug(() -> "EntityChunkTracker: detected %d moves and %d removals"
                    .formatted(finalMoves, finalRemovals));
        }
    }

    /**
     * Returns the number of entities currently tracked.
     */
    public int getTrackedCount() {
        return trackedEntities.size();
    }

    private record TrackedEntity(ChunkCoord chunkCoord, EntityType type, Entity entity) {
        private static TrackedEntity from(Entity entity) {
            return new TrackedEntity(ChunkCoord.from(entity), entity.getType(), entity);
        }
    }
}
