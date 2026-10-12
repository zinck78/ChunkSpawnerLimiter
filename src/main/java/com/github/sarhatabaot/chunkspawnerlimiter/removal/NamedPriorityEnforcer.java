package com.github.sarhatabaot.chunkspawnerlimiter.removal;

import com.github.sarhatabaot.chunkspawnerlimiter.PluginConfig;
import com.github.sarhatabaot.chunkspawnerlimiter.chunk.ChunkCoord;
import com.github.sarhatabaot.chunkspawnerlimiter.counter.CounterDataManager;
import com.github.sarhatabaot.chunkspawnerlimiter.notification.NotificationService;
import com.github.sarhatabaot.chunkspawnerlimiter.tracker.EntityChunkTracker;
import org.bukkit.Chunk;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Opt-in strict population cap for existing entities. Unlike 'prevent', which
 * only cancels new spawns, this handles overcrowding caused by chunk loading,
 * name-tagging and moving entities between chunks. Unnamed animals are culled
 * first. Named animals count toward the same cap and are culled only when the
 * remaining population still exceeds it.
 *
 * This never force-loads chunks and runs exclusively on the server thread.
 */
public final class NamedPriorityEnforcer {
    private static final Comparator<Entity> VICTIM_ORDER = Comparator
            .comparing((Entity entity) -> entity.getCustomName() != null)
            .thenComparingInt(Entity::getTicksLived)
            .thenComparing(entity -> entity.getUniqueId().toString());

    private final Plugin plugin;
    private final PluginConfig config;
    private final CounterDataManager counters;
    private final EntityChunkTracker tracker;
    private final NotificationService notifications;
    private final Set<ChunkCoord> pending = new java.util.LinkedHashSet<>();
    private boolean drainScheduled;

    public NamedPriorityEnforcer(Plugin plugin, PluginConfig config,
                                 CounterDataManager counters, EntityChunkTracker tracker,
                                 NotificationService notifications) {
        this.plugin = plugin;
        this.config = config;
        this.counters = counters;
        this.tracker = tracker;
        this.notifications = notifications;
    }

    /**
     * Queue a one-off population check. Deduplicate incoming chunk load
     * events and batch at most max-chunks-per-tick to avoid a burst of hundreds
     * of synchronous entity scans during fast exploration or plugin reload.
     */
    public void queue(ChunkCoord coord) {
        if (!config.shouldTrimExistingOverflow() || !pending.add(coord)) return;
        scheduleDrain();
    }

    private void scheduleDrain() {
        if (drainScheduled) return;
        drainScheduled = true;
        plugin.getServer().getScheduler().runTask(plugin, this::drain);
    }

    private void drain() {
        drainScheduled = false;
        int max = config.getInspectionMaxChunksPerTick();
        java.util.Iterator<ChunkCoord> iterator = pending.iterator();
        for (int scanned = 0; scanned < max && iterator.hasNext(); scanned++) {
            ChunkCoord coord = iterator.next();
            iterator.remove();
            Chunk chunk = coord.getChunk();
            if (chunk != null && chunk.isLoaded()
                    && !config.isWorldDisabled(chunk.getWorld().getName())) {
                enforceChunk(chunk);
            }
        }
        if (!pending.isEmpty()) {
            scheduleDrain();
        }
    }

    /**
     * Admit a new named animal over the limit only if removable UNNAMED animals
     * can make room. Reject the incoming named animal rather than removing
     * an existing named animal to accommodate it.
     */
    public boolean makeRoomForNamedSpawn(Chunk chunk, Entity incoming) {
        if (!config.shouldTrimExistingOverflow() || incoming.getCustomName() == null) return false;

        EntityType type = incoming.getType();
        Integer typeLimit = config.getResolvedEntityLimit(type);
        Set<EntityType> members = config.getEntityGroupTotalMembers(type);
        Integer groupLimit = members.isEmpty() ? null : config.getEntityGroupTotalLimit(type);
        if (typeLimit == null && groupLimit == null) return false;

        int typeCount = 0;
        int groupCount = 0;
        List<Entity> unnamedSameType = new ArrayList<>();
        List<Entity> unnamedGroup = new ArrayList<>();
        for (Entity entity : chunk.getEntities()) {
            if (entity == incoming || !entity.isValid() || entity.isDead()) continue;
            if (entity.getType() == type) typeCount++;
            if (members.contains(entity.getType())) groupCount++;
            if (entity.getCustomName() != null || shouldProtect(entity)) continue;
            if (entity.getType() == type) unnamedSameType.add(entity);
            else if (members.contains(entity.getType())) unnamedGroup.add(entity);
        }
        int typeOver = typeLimit == null ? 0 : Math.max(0, typeCount + 1 - typeLimit);
        int groupOver = groupLimit == null ? 0 : Math.max(0, groupCount + 1 - groupLimit);
        if (typeOver == 0 && groupOver == 0) return true;
        unnamedSameType.sort(VICTIM_ORDER);
        unnamedGroup.sort(VICTIM_ORDER);

        if (unnamedSameType.size() < typeOver) return false;
        List<Entity> victims = new ArrayList<>();
        for (int i = 0; i < typeOver; i++) victims.add(unnamedSameType.get(i));
        int moreGroup = Math.max(0, groupOver - (members.contains(type) ? victims.size() : 0));
        for (int i = typeOver; i < unnamedSameType.size() && moreGroup > 0; i++, moreGroup--) {
            victims.add(unnamedSameType.get(i));
        }
        for (int i = 0; i < unnamedGroup.size() && moreGroup > 0; i++, moreGroup--) {
            victims.add(unnamedGroup.get(i));
        }
        if (moreGroup > 0) return false;

        for (Entity victim : victims) remove(victim);
        return true;
    }

    /** Run a bounded, single-chunk pass on chunk entry/arrival/name application. */
    public void enforceChunk(Chunk chunk) {
        if (!config.shouldTrimExistingOverflow()) return;
        List<Entity> eligible = new ArrayList<>();
        Map<EntityType, Integer> typeCounts = new EnumMap<>(EntityType.class);
        for (Entity entity : chunk.getEntities()) {
            if (entity.isValid() && !entity.isDead()
                    && Checks.shouldTrackEntity(entity, config)) {
                eligible.add(entity);
                typeCounts.merge(entity.getType(), 1, Integer::sum);
            }
        }

        // One sorting pass; every limit removes unnamed first.
        eligible.sort(VICTIM_ORDER);
        Set<UUID> removed = new HashSet<>();
        Map<EntityType, Integer> removedByType = new EnumMap<>(EntityType.class);

        for (EntityType type : config.getResolvedEntityTypes()) {
            Integer cap = config.getResolvedEntityLimit(type);
            if (cap == null) continue;
            int overflow = typeCounts.getOrDefault(type, 0) - cap;
            for (Entity entity : eligible) {
                if (overflow <= 0) break;
                if (entity.getType() != type || removed.contains(entity.getUniqueId())
                        || shouldProtect(entity)) continue;
                remove(entity);
                removed.add(entity.getUniqueId());
                removedByType.merge(type, 1, Integer::sum);
                overflow--;
            }
        }

        for (Map.Entry<String, Integer> group : config.getEntityGroupTotalLimits().entrySet()) {
            Set<EntityType> members = config.getEntityGroupMembers(group.getKey());
            int count = 0;
            for (Entity entity : eligible) {
                if (!removed.contains(entity.getUniqueId()) && members.contains(entity.getType())) count++;
            }
            int overflow = count - group.getValue();
            for (Entity entity : eligible) {
                if (overflow <= 0) break;
                if (removed.contains(entity.getUniqueId()) || !members.contains(entity.getType())
                        || shouldProtect(entity)) continue;
                remove(entity);
                removed.add(entity.getUniqueId());
                removedByType.merge(entity.getType(), 1, Integer::sum);
                overflow--;
            }
        }

        for (Map.Entry<EntityType, Integer> entry : removedByType.entrySet()) {
            notifications.notifyEntitiesRemoved(chunk, entry.getKey(), entry.getValue());
        }
    }

    private boolean shouldProtect(Entity entity) {
        // External plugin exclusions and raid members remain exempt from
        // destructive cleanup; names are a preference, not an exemption.
        return Checks.hasMetaData(entity) || ExternalChecks.hasNbtData(entity);
    }

    private void remove(Entity entity) {
        ChunkCoord coord = ChunkCoord.from(entity);
        EntityType type = entity.getType();
        entity.remove(); // no drops; prevents profitable kill loops
        tracker.recordExit(entity);
        counters.decrementEntityIfPresent(coord, type);
        if (entity.getCustomName() != null) {
            plugin.getLogger().warning("Population limit: removed named " + type.name()
                    + " in " + coord + " because no lower-priority alternative could satisfy the cap.");
        }
    }
}
