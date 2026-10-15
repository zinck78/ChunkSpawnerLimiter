package com.github.sarhatabaot.chunkspawnerlimiter.tracker;

import com.github.sarhatabaot.chunkspawnerlimiter.chunk.ChunkCoord;
import com.github.sarhatabaot.chunkspawnerlimiter.counter.CounterDataManager;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VillagerOnlyTrackerTest {

    @Test
    void tracksOnlyVillagersAndIgnoresOtherEntityTypesAtEveryEntryPoint() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        Chunk chunk = mock(Chunk.class);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getX()).thenReturn(2);
        when(chunk.getZ()).thenReturn(3);
        Location location = mock(Location.class);
        when(location.getChunk()).thenReturn(chunk);

        Entity villager = mock(Entity.class);
        when(villager.getType()).thenReturn(EntityType.VILLAGER);
        when(villager.getUniqueId()).thenReturn(UUID.randomUUID());
        when(villager.getLocation()).thenReturn(location);
        when(villager.isValid()).thenReturn(true);

        Entity bat = mock(Entity.class);
        when(bat.getType()).thenReturn(EntityType.BAT);
        Entity cow = mock(Entity.class);
        when(cow.getType()).thenReturn(EntityType.COW);

        CounterDataManager counters = new CounterDataManager();
        EntityChunkTracker tracker = new EntityChunkTracker(
                mock(Plugin.class), counters,
                e -> e.getType() == EntityType.VILLAGER, 40L, false);

        tracker.recordEntry(bat);
        tracker.recordEntry(cow);
        tracker.recordEntry(villager);
        tracker.recordEntry(villager); // duplicate events must not double register
        ChunkCoord coord = ChunkCoord.from(chunk);
        assertThat(tracker.recordEntryIfNew(bat, coord)).isFalse();
        assertThat(tracker.recordEntryIfNew(cow, coord)).isFalse();
        assertThat(tracker.getTrackedCount()).isOne();
        assertThat(tracker.removeIfTrackedInChunk(villager, coord)).isTrue();
        assertThat(tracker.removeIfTrackedInChunk(villager, coord)).isFalse();
        assertThat(tracker.getTrackedCount()).isZero();
        verify(bat, never()).getUniqueId();
        verify(cow, never()).getUniqueId();
    }
}
