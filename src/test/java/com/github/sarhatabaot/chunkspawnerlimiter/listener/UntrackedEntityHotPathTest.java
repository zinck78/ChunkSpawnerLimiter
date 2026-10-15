package com.github.sarhatabaot.chunkspawnerlimiter.listener;

import com.github.sarhatabaot.chunkspawnerlimiter.PluginConfig;
import com.github.sarhatabaot.chunkspawnerlimiter.counter.CounterData;
import com.github.sarhatabaot.chunkspawnerlimiter.counter.CounterDataManager;
import com.github.sarhatabaot.chunkspawnerlimiter.notification.NotificationService;
import com.github.sarhatabaot.chunkspawnerlimiter.tracker.EntityChunkTracker;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Bat;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.mockito.Mockito.*;

class UntrackedEntityHotPathTest {

    @Test
    void untrackedBatDeathDoesNotCreateOrAccessPopulationCounters() {
        PluginConfig config = mock(PluginConfig.class);
        when(config.getResolvedEntityTypes()).thenReturn(Set.of(EntityType.VILLAGER));
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Bat bat = mock(Bat.class);
        when(bat.getWorld()).thenReturn(world);
        when(bat.getType()).thenReturn(EntityType.BAT);
        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(bat);

        CounterDataManager counters = mock(CounterDataManager.class);
        EntityChunkTracker tracker = mock(EntityChunkTracker.class);
        EventListener listener = new EventListener(mock(Plugin.class), config, counters,
                mock(NotificationService.class), tracker);
        listener.onEntityDeath(event);

        verifyNoInteractions(counters, tracker);
        verify(bat, never()).getLocation();
    }

    @Test
    void unlimitedBlockBreaksDoNotCreateOrAccessBlockCounters() {
        PluginConfig config = mock(PluginConfig.class);
        Block block = mock(Block.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(block.getWorld()).thenReturn(world);
        when(block.getType()).thenReturn(Material.STONE);
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getBlock()).thenReturn(block);

        CounterDataManager counters = mock(CounterDataManager.class);
        EventListener listener = new EventListener(mock(Plugin.class), config, counters,
                mock(NotificationService.class), mock(EntityChunkTracker.class));
        listener.onBlockBreak(event);

        verifyNoInteractions(counters);
        verify(block, never()).getLocation();
    }

    @Test
    void unconfiguredVehicleDestructionDoesNotTouchCounters() {
        PluginConfig config = mock(PluginConfig.class);
        when(config.getResolvedEntityTypes()).thenReturn(Set.of(EntityType.VILLAGER));
        Vehicle vehicle = mock(Vehicle.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(vehicle.getWorld()).thenReturn(world);
        when(vehicle.getType()).thenReturn(EntityType.MINECART);
        VehicleDestroyEvent event = mock(VehicleDestroyEvent.class);
        when(event.getVehicle()).thenReturn(vehicle);

        CounterDataManager counters = mock(CounterDataManager.class);
        EntityChunkTracker tracker = mock(EntityChunkTracker.class);
        EventListener listener = new EventListener(mock(Plugin.class), config, counters,
                mock(NotificationService.class), tracker);
        listener.onVehicleDestroy(event);

        verifyNoInteractions(counters, tracker);
        verify(vehicle, never()).getLocation();
    }

    @Test
    void configuredVillagerDeathStillUpdatesItsCounterAndTracker() {
        PluginConfig config = mock(PluginConfig.class);
        when(config.getResolvedEntityTypes()).thenReturn(Set.of(EntityType.VILLAGER));
        when(config.hasResolvedEntityLimit(EntityType.VILLAGER)).thenReturn(true);
        when(config.getEntityGroupTotalMembers(EntityType.VILLAGER)).thenReturn(Set.of());
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(java.util.UUID.randomUUID());
        Chunk chunk = mock(Chunk.class);
        when(chunk.getWorld()).thenReturn(world);
        Location location = mock(Location.class);
        when(location.getChunk()).thenReturn(chunk);
        Villager villager = mock(Villager.class);
        when(villager.getType()).thenReturn(EntityType.VILLAGER);
        when(villager.getWorld()).thenReturn(world);
        when(villager.getLocation()).thenReturn(location);
        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(villager);

        CounterDataManager counters = mock(CounterDataManager.class);
        CounterData data = mock(CounterData.class);
        when(counters.getCounterData(any())).thenReturn(data);
        EntityChunkTracker tracker = mock(EntityChunkTracker.class);
        EventListener listener = new EventListener(mock(Plugin.class), config, counters,
                mock(NotificationService.class), tracker);
        listener.onEntityDeath(event);

        verify(data).decrementEntity(EntityType.VILLAGER);
        verify(tracker).recordExit(villager);
    }

    @Test
    void configuredBlockBreakStillUpdatesItsCounter() {
        PluginConfig config = mock(PluginConfig.class);
        when(config.hasResolvedBlockLimit(Material.STONE)).thenReturn(true);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(java.util.UUID.randomUUID());
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getType()).thenReturn(Material.STONE);
        Chunk chunk = mock(Chunk.class);
        when(chunk.getWorld()).thenReturn(world);
        Location location = mock(Location.class);
        when(location.getChunk()).thenReturn(chunk);
        when(block.getLocation()).thenReturn(location);
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getBlock()).thenReturn(block);

        CounterDataManager counters = mock(CounterDataManager.class);
        CounterData data = mock(CounterData.class);
        when(counters.getCounterData(any())).thenReturn(data);
        EventListener listener = new EventListener(mock(Plugin.class), config, counters,
                mock(NotificationService.class), mock(EntityChunkTracker.class));
        listener.onBlockBreak(event);

        verify(data).decrementBlock(Material.STONE);
    }

}
