package com.github.sarhatabaot.chunkspawnerlimiter;

import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ActualityVillagerMovementPredicateTest {

    @Test
    void actualityMovementPredicateRejectsAllNonVillagersBeforeLimitLookup() {
        PluginConfig config = mock(PluginConfig.class);
        when(config.hasResolvedEntityLimit(EntityType.VILLAGER)).thenReturn(true);
        when(config.getEntityGroupTotalMembers(EntityType.VILLAGER)).thenReturn(Set.of());
        Entity villager = mock(Entity.class);
        Entity cow = mock(Entity.class);
        Entity bat = mock(Entity.class);
        when(villager.getType()).thenReturn(EntityType.VILLAGER);
        when(cow.getType()).thenReturn(EntityType.COW);
        when(bat.getType()).thenReturn(EntityType.BAT);

        assertThat(ChunkSpawnerLimiter.shouldTrackForMovement(bat, config, true)).isFalse();
        assertThat(ChunkSpawnerLimiter.shouldTrackForMovement(cow, config, true)).isFalse();
        assertThat(ChunkSpawnerLimiter.shouldTrackForMovement(villager, config, true)).isTrue();

        verify(config, never()).hasResolvedEntityLimit(EntityType.COW);
        verify(config, never()).hasResolvedEntityLimit(EntityType.BAT);
        verify(config).hasResolvedEntityLimit(EntityType.VILLAGER);
    }

    @Test
    void genericDefaultModeRetainsOtherConfiguredEntities() {
        PluginConfig config = mock(PluginConfig.class);
        when(config.hasResolvedEntityLimit(EntityType.COW)).thenReturn(true);
        when(config.getEntityGroupTotalMembers(EntityType.COW)).thenReturn(Set.of());
        Entity cow = mock(Entity.class);
        when(cow.getType()).thenReturn(EntityType.COW);

        assertThat(ChunkSpawnerLimiter.shouldTrackForMovement(cow, config, false)).isTrue();
    }
}
