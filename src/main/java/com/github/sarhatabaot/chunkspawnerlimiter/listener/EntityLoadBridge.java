package com.github.sarhatabaot.chunkspawnerlimiter.listener;

import com.github.sarhatabaot.chunkspawnerlimiter.CSLLogger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Modern entity lifecycle bridge, intentionally compiled against the old
 * Spigot API. It registers only when EntitiesLoadEvent is available.
 *
 * ChunkLoadEvent must not force entity data to load just to count livestock.
 * An entity load callback supplies the entities which the server has already
 * loaded, including villagers to initialize movement tracking.
 */
public final class EntityLoadBridge {
    private static final Method ENTITIES_LOADED = findEntitiesLoadedMethod();

    private EntityLoadBridge() {}

    public static boolean registerIfSupported(Plugin plugin, ChunkListener delegate) {
        try {
            Class<? extends Event> loadEvent = Class
                    .forName("org.bukkit.event.world.EntitiesLoadEvent")
                    .asSubclass(Event.class);
            Method getChunk = loadEvent.getMethod("getChunk");
            Method getEntities = loadEvent.getMethod("getEntities");

            Bukkit.getPluginManager().registerEvent(loadEvent, new Listener() {},
                    EventPriority.MONITOR, (ignored, event) -> {
                        try {
                            Chunk chunk = (Chunk) getChunk.invoke(event);
                            Object list = getEntities.invoke(event);
                            if (list instanceof Iterable<?> entities) {
                                delegate.onEntitiesLoaded(chunk, typedEntities(entities));
                            }
                        } catch (ReflectiveOperationException exception) {
                            CSLLogger.debug(() -> "EntitiesLoadEvent handler: " + exception.getMessage());
                        }
                    }, plugin);

            registerUnloadEventIfSupported(plugin, delegate);
            return true;
        } catch (ClassNotFoundException ignored) {
            // Older Spigot/Paper versions retain the original ChunkLoadEvent path.
            return false;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().warning("Entity-load bridge unavailable; using legacy chunk handling: "
                    + exception.getMessage());
            return false;
        }
    }

    private static void registerUnloadEventIfSupported(Plugin plugin, ChunkListener delegate) {
        try {
            Class<? extends Event> unloadEvent = Class
                    .forName("org.bukkit.event.world.EntitiesUnloadEvent")
                    .asSubclass(Event.class);
            Method getChunk = unloadEvent.getMethod("getChunk");
            Method getEntities = unloadEvent.getMethod("getEntities");
            Bukkit.getPluginManager().registerEvent(unloadEvent, new Listener() {},
                    EventPriority.MONITOR, (ignored, event) -> {
                        try {
                            Chunk chunk = (Chunk) getChunk.invoke(event);
                            Object list = getEntities.invoke(event);
                            if (list instanceof Iterable<?> entities) {
                                delegate.onEntitiesUnloaded(chunk, typedEntities(entities));
                            }
                        } catch (ReflectiveOperationException exception) {
                            CSLLogger.debug(() -> "EntitiesUnloadEvent handler: " + exception.getMessage());
                        }
                    }, plugin);
        } catch (ClassNotFoundException ignored) {
            // Some supported server builds do not have a separate unload event.
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().warning("Optional EntitiesUnloadEvent hook unavailable: "
                    + exception.getMessage());
        }
    }

    /**
     * The API event supplies Entity instances. Filter reflectively to keep this
     * bridge loadable by older Spigot versions.
     */
    private static Iterable<Entity> typedEntities(Iterable<?> raw) {
        java.util.List<Entity> entities = new java.util.ArrayList<>();
        for (Object value : raw) {
            if (value instanceof Entity entity) entities.add(entity);
        }
        return entities;
    }

    private static Method findEntitiesLoadedMethod() {
        try {
            return Chunk.class.getMethod("isEntitiesLoaded");
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    /**
     * A missing status API is not evidence entities are loaded. Fail closed:
     * never force-load them during a reload bootstrap.
     */
    public static boolean areEntitiesLoaded(Chunk chunk) {
        if (ENTITIES_LOADED == null) return false;
        try {
            return Boolean.TRUE.equals(ENTITIES_LOADED.invoke(chunk));
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }
}
