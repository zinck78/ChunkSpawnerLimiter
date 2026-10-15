package com.github.sarhatabaot.chunkspawnerlimiter.listener;

import com.github.sarhatabaot.chunkspawnerlimiter.PluginConfig;
import com.github.sarhatabaot.chunkspawnerlimiter.chunk.ChunkCoord;
import com.github.sarhatabaot.chunkspawnerlimiter.counter.CounterData;
import com.github.sarhatabaot.chunkspawnerlimiter.counter.CounterDataManager;
import com.github.sarhatabaot.chunkspawnerlimiter.reflection.scanner.BlockScanner;
import com.github.sarhatabaot.chunkspawnerlimiter.reflection.scanner.BlockScannerFactory;
import com.github.sarhatabaot.chunkspawnerlimiter.removal.NamedPriorityEnforcer;
import com.github.sarhatabaot.chunkspawnerlimiter.removal.RemovalTaskManager;
import com.github.sarhatabaot.chunkspawnerlimiter.removal.modes.RemovalMode;
import com.github.sarhatabaot.chunkspawnerlimiter.tracker.EntityChunkTracker;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ChunkListenerEntityLoadTest {

    @Test
    void modernChunkLoadDoesNotEnumerateOrForceLoadEntities() {
        Plugin plugin = mock(Plugin.class);
        PluginConfig config = mock(PluginConfig.class);
        CounterDataManager counters = mock(CounterDataManager.class);
        RemovalTaskManager removals = mock(RemovalTaskManager.class);
        NamedPriorityEnforcer enforcer = mock(NamedPriorityEnforcer.class);
        EntityChunkTracker tracker = mock(EntityChunkTracker.class);
        BlockScanner scanner = mock(BlockScanner.class);
        RemovalMode mode = new com.github.sarhatabaot.chunkspawnerlimiter.removal.modes.Prevent(removals);
        World world = mock(World.class);
        Chunk chunk = mock(Chunk.class);
        ChunkLoadEvent event = mock(ChunkLoadEvent.class);
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(chunk.getWorld()).thenReturn(world);
        when(event.getWorld()).thenReturn(world);
        when(event.getChunk()).thenReturn(chunk);
        when(config.getRemovalMode()).thenReturn(mode);
        when(config.getTrackedBlockMaterials()).thenReturn(Set.of(Material.STONE));
        when(config.shouldTrimExistingOverflow()).thenReturn(true);
        when(tracker.isEnabled()).thenReturn(true);

        try (MockedStatic<BlockScannerFactory> factory = mockStatic(BlockScannerFactory.class)) {
            factory.when(() -> BlockScannerFactory.create(plugin, config, counters)).thenReturn(scanner);
            ChunkListener listener = new ChunkListener(plugin, config, counters, removals, tracker, enforcer);
            listener.setEntityLoadEventsAvailable(true);
            listener.onChunkLoad(event);

            verify(chunk, never()).getEntities();
            verify(enforcer, never()).queue(any(ChunkCoord.class));
            verify(scanner).scanChunk(eq(chunk), any(ChunkCoord.class), eq(true));
        }
    }

    @Test
    void modernEntityLoadOnlyRegistersVillagersForMovementAndDoesNotCountBats() {
        Plugin plugin = mock(Plugin.class);
        PluginConfig config = mock(PluginConfig.class);
        CounterDataManager counters = mock(CounterDataManager.class);
        RemovalTaskManager removals = mock(RemovalTaskManager.class);
        NamedPriorityEnforcer enforcer = mock(NamedPriorityEnforcer.class);
        EntityChunkTracker tracker = mock(EntityChunkTracker.class);
        BlockScanner scanner = mock(BlockScanner.class);
        CounterData data = mock(CounterData.class);
        World world = mock(World.class);
        Chunk chunk = mock(Chunk.class);
        Entity villager = mock(Entity.class);
        Entity cow = mock(Entity.class);
        Entity bat = mock(Entity.class);
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(chunk.getWorld()).thenReturn(world);
        when(counters.getCounterData(any(ChunkCoord.class))).thenReturn(data);
        when(tracker.isEnabled()).thenReturn(true);
        when(tracker.recordEntryIfNew(eq(villager), any(ChunkCoord.class))).thenReturn(true, false);
        when(config.getResolvedEntityTypes()).thenReturn(Set.of(EntityType.VILLAGER, EntityType.COW));
        when(config.hasResolvedEntityLimit(EntityType.VILLAGER)).thenReturn(true);
        when(config.hasResolvedEntityLimit(EntityType.COW)).thenReturn(true);
        when(config.getEntityGroupTotalMembers(any(EntityType.class))).thenReturn(Set.of());
        when(config.shouldTrimExistingOverflow()).thenReturn(true);
        when(villager.getType()).thenReturn(EntityType.VILLAGER);
        when(cow.getType()).thenReturn(EntityType.COW);
        when(bat.getType()).thenReturn(EntityType.BAT);

        try (MockedStatic<BlockScannerFactory> factory = mockStatic(BlockScannerFactory.class)) {
            factory.when(() -> BlockScannerFactory.create(plugin, config, counters)).thenReturn(scanner);
            ChunkListener listener = new ChunkListener(plugin, config, counters, removals, tracker, enforcer);

            listener.onEntitiesLoaded(chunk, List.of(bat, cow, villager));
            listener.onEntitiesLoaded(chunk, List.of(villager));

            verify(tracker, times(2)).recordEntryIfNew(eq(villager), any(ChunkCoord.class));
            verify(tracker, never()).recordEntryIfNew(eq(cow), any(ChunkCoord.class));
            verify(tracker, never()).recordEntryIfNew(eq(bat), any(ChunkCoord.class));
            verify(data, times(1)).incrementEntity(EntityType.VILLAGER);
            verify(enforcer, times(2)).queue(any(ChunkCoord.class));
        }
    }

    @Test
    void startupBootstrapIndexesExistingVillagersButOnlyReadsLoadedEntityChunks() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        PluginConfig config = mock(PluginConfig.class);
        CounterDataManager counters = mock(CounterDataManager.class);
        RemovalTaskManager removals = mock(RemovalTaskManager.class);
        NamedPriorityEnforcer enforcer = mock(NamedPriorityEnforcer.class);
        EntityChunkTracker tracker = mock(EntityChunkTracker.class);
        BlockScanner scanner = mock(BlockScanner.class);
        CounterData counter = mock(CounterData.class);
        World world = mock(World.class);
        Chunk ready = mock(Chunk.class);
        Chunk notReady = mock(Chunk.class);
        Entity villager = mock(Entity.class);
        Entity bat = mock(Entity.class);

        when(plugin.getServer()).thenReturn(server);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getLoadedChunks()).thenReturn(new Chunk[]{ready, notReady});
        when(ready.getWorld()).thenReturn(world);
        when(counters.getCounterData(any(ChunkCoord.class))).thenReturn(counter);
        when(config.getResolvedEntityTypes()).thenReturn(Set.of(EntityType.VILLAGER));
        when(config.hasResolvedEntityLimit(EntityType.VILLAGER)).thenReturn(true);
        when(config.getEntityGroupTotalMembers(any(EntityType.class))).thenReturn(Set.of());
        when(tracker.isEnabled()).thenReturn(true);
        when(tracker.recordEntryIfNew(eq(villager), any(ChunkCoord.class))).thenReturn(true);
        when(villager.getType()).thenReturn(EntityType.VILLAGER);
        when(bat.getType()).thenReturn(EntityType.BAT);
        when(ready.getEntities()).thenReturn(new Entity[]{bat, villager});

        try (MockedStatic<BlockScannerFactory> factory = mockStatic(BlockScannerFactory.class);
             MockedStatic<EntityLoadBridge> bridge = mockStatic(EntityLoadBridge.class)) {
            factory.when(() -> BlockScannerFactory.create(plugin, config, counters)).thenReturn(scanner);
            bridge.when(() -> EntityLoadBridge.areEntitiesLoaded(ready)).thenReturn(true);
            bridge.when(() -> EntityLoadBridge.areEntitiesLoaded(notReady)).thenReturn(false);
            ChunkListener listener = new ChunkListener(plugin, config, counters, removals, tracker, enforcer);
            listener.setEntityLoadEventsAvailable(true);
            listener.bootstrapAlreadyLoadedEntities();

            verify(ready, times(1)).getEntities();
            verify(notReady, never()).getEntities();
            verify(tracker, times(1)).recordEntryIfNew(eq(villager), any(ChunkCoord.class));
            verify(tracker, never()).recordEntryIfNew(eq(bat), any(ChunkCoord.class));
            verify(counter).incrementEntity(EntityType.VILLAGER);
            verifyNoInteractions(scanner);
        }
    }

    @Test
    void entityUnloadDecrementsOnlyIfVillagerWasTrackedInThatChunk() {
        Plugin plugin = mock(Plugin.class);
        PluginConfig config = mock(PluginConfig.class);
        CounterDataManager counters = mock(CounterDataManager.class);
        RemovalTaskManager removals = mock(RemovalTaskManager.class);
        NamedPriorityEnforcer enforcer = mock(NamedPriorityEnforcer.class);
        EntityChunkTracker tracker = mock(EntityChunkTracker.class);
        BlockScanner scanner = mock(BlockScanner.class);
        World world = mock(World.class);
        Chunk chunk = mock(Chunk.class);
        Entity villager = mock(Entity.class);
        Entity bat = mock(Entity.class);

        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(chunk.getWorld()).thenReturn(world);
        when(tracker.isEnabled()).thenReturn(true);
        when(villager.getType()).thenReturn(EntityType.VILLAGER);
        when(bat.getType()).thenReturn(EntityType.BAT);
        when(tracker.removeIfTrackedInChunk(eq(villager), any(ChunkCoord.class)))
                .thenReturn(true, false);

        try (MockedStatic<BlockScannerFactory> factory = mockStatic(BlockScannerFactory.class)) {
            factory.when(() -> BlockScannerFactory.create(plugin, config, counters)).thenReturn(scanner);
            ChunkListener listener = new ChunkListener(plugin, config, counters, removals, tracker, enforcer);
            listener.onEntitiesUnloaded(chunk, List.of(villager, bat));
            listener.onEntitiesUnloaded(chunk, List.of(villager));

            verify(tracker, times(2)).removeIfTrackedInChunk(eq(villager), any(ChunkCoord.class));
            verify(tracker, never()).removeIfTrackedInChunk(eq(bat), any(ChunkCoord.class));
            verify(counters, times(1)).decrementEntityIfPresent(any(ChunkCoord.class), eq(EntityType.VILLAGER));
        }
    }

}
