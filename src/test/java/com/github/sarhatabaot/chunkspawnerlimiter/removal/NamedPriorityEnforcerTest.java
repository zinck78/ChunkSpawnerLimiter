package com.github.sarhatabaot.chunkspawnerlimiter.removal;

import com.github.sarhatabaot.chunkspawnerlimiter.PluginConfig;
import com.github.sarhatabaot.chunkspawnerlimiter.counter.CounterDataManager;
import com.github.sarhatabaot.chunkspawnerlimiter.notification.NotificationService;
import com.github.sarhatabaot.chunkspawnerlimiter.tracker.EntityChunkTracker;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.Mockito.*;

class NamedPriorityEnforcerTest {

    @Test
    void prefersUnnamedCowOverSeventeenNamedAndUnnamedCows() {
        TestWorld test = new TestWorld();
        PluginConfig config = config();
        List<Entity> cows = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            cows.add(test.create(EntityType.COW, "Named " + i, 100 + i));
        }
        Entity unnamed = test.create(EntityType.COW, null, 1000);
        cows.add(unnamed);
        when(test.chunk.getEntities()).thenReturn(cows.toArray(new Entity[0]));

        test.enforcer(config).enforceChunk(test.chunk);

        verify(unnamed).remove();
        for (Entity named : cows.subList(0, 16)) verify(named, never()).remove();
    }

    @Test
    void removesOneNamedCowWhenSeventeenNamedCowsExist() {
        TestWorld test = new TestWorld();
        PluginConfig config = config();
        List<Entity> cows = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            cows.add(test.create(EntityType.COW, "Cow " + i, i + 1));
        }
        when(test.chunk.getEntities()).thenReturn(cows.toArray(new Entity[0]));

        test.enforcer(config).enforceChunk(test.chunk);

        // Youngest named cow is removed; sixteen older named cows remain.
        verify(cows.get(0)).remove();
        for (Entity cow : cows.subList(1, 17)) verify(cow, never()).remove();
    }

    @Test
    void enforcesSharedGroupTotalButRetainsNamedAnimals() {
        TestWorld test = new TestWorld();
        PluginConfig config = config();
        when(config.getResolvedEntityLimit(EntityType.COW)).thenReturn(16);
        when(config.getResolvedEntityLimit(EntityType.SHEEP)).thenReturn(16);
        when(config.getResolvedEntityTypes()).thenReturn(Set.of(EntityType.COW, EntityType.SHEEP));
        when(config.getEntityGroupTotalLimits()).thenReturn(Map.of("LIVESTOCK", 3));
        when(config.getEntityGroupMembers("LIVESTOCK"))
                .thenReturn(Set.of(EntityType.COW, EntityType.SHEEP));

        Entity tagged = test.create(EntityType.COW, "Moo", 100);
        Entity cow = test.create(EntityType.COW, null, 20);
        Entity sheep = test.create(EntityType.SHEEP, null, 10);
        Entity sheep2 = test.create(EntityType.SHEEP, null, 30);
        when(test.chunk.getEntities()).thenReturn(new Entity[]{tagged, cow, sheep, sheep2});

        test.enforcer(config).enforceChunk(test.chunk);

        verify(sheep).remove(); // youngest unnamed is trimmed first
        verify(tagged, never()).remove();
        verify(cow, never()).remove();
        verify(sheep2, never()).remove();
    }

    @Test
    void namedArrivalMayReplaceUnnamedWithoutExceedingPopulation() {
        TestWorld test = new TestWorld();
        PluginConfig config = config();
        when(config.getEntityGroupTotalMembers(EntityType.COW))
                .thenReturn(Set.of(EntityType.COW, EntityType.SHEEP));
        when(config.getEntityGroupTotalLimit(EntityType.COW)).thenReturn(2);
        Entity oldCow = test.create(EntityType.COW, null, 100);
        Entity sheep = test.create(EntityType.SHEEP, "Fluffy", 100);
        Entity arrival = test.create(EntityType.COW, "Favorite", 1);
        when(test.chunk.getEntities()).thenReturn(new Entity[]{oldCow, sheep, arrival});

        org.assertj.core.api.Assertions.assertThat(
                test.enforcer(config).makeRoomForNamedSpawn(test.chunk, arrival)).isTrue();
        verify(oldCow).remove();
        verify(sheep, never()).remove();
    }

    private static PluginConfig config() {
        PluginConfig config = mock(PluginConfig.class);
        when(config.shouldTrimExistingOverflow()).thenReturn(true);
        when(config.getResolvedEntityTypes()).thenReturn(Set.of(EntityType.COW));
        when(config.getResolvedEntityLimit(EntityType.COW)).thenReturn(16);
        when(config.getEntityGroupTotalLimits()).thenReturn(Map.of());
        when(config.getIgnoreMetadata()).thenReturn(List.of());
        Checks.setup(config);
        return config;
    }

    private static final class TestWorld {
        final World world = mock(World.class);
        final Chunk chunk = mock(Chunk.class);
        final Plugin plugin = mock(Plugin.class);
        final EntityChunkTracker tracker = mock(EntityChunkTracker.class);
        final NotificationService notices = mock(NotificationService.class);

        TestWorld() {
            when(world.getUID()).thenReturn(UUID.randomUUID());
            when(chunk.getWorld()).thenReturn(world);
            when(chunk.getX()).thenReturn(2);
            when(chunk.getZ()).thenReturn(3);
            when(plugin.getLogger()).thenReturn(Logger.getLogger("NamedPriorityTest"));
        }

        Entity create(EntityType type, String name, int age) {
            Entity e = mock(Entity.class);
            Location location = mock(Location.class);
            when(location.getChunk()).thenReturn(chunk);
            when(e.getLocation()).thenReturn(location);
            when(e.getType()).thenReturn(type);
            when(e.getUniqueId()).thenReturn(UUID.randomUUID());
            when(e.getCustomName()).thenReturn(name);
            when(e.getTicksLived()).thenReturn(age);
            when(e.isValid()).thenReturn(true);
            return e;
        }

        NamedPriorityEnforcer enforcer(PluginConfig config) {
            return new NamedPriorityEnforcer(plugin, config,
                    new CounterDataManager(), tracker, notices);
        }
    }
}
