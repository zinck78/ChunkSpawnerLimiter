package com.github.sarhatabaot.chunkspawnerlimiter.listener;

import com.github.sarhatabaot.chunkspawnerlimiter.CSLLogger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Optional EntityBreedEvent bridge. The main plugin is compiled against an
 * older Spigot API on which this event did not yet exist.
 */
public final class BreedingListener {
    private BreedingListener() {}

    public static void registerIfSupported(Plugin plugin, EventListener delegate) {
        try {
            Class<? extends Event> type = Class.forName("org.bukkit.event.entity.EntityBreedEvent")
                    .asSubclass(Event.class);
            Method getEntity = type.getMethod("getEntity");
            Method getBreeder = type.getMethod("getBreeder");
            Listener marker = new Listener() { };
            Bukkit.getPluginManager().registerEvent(type, marker, EventPriority.HIGHEST,
                    (listener, event) -> {
                        if (event instanceof Cancellable cancellable && !cancellable.isCancelled()) {
                            try {
                                Entity child = (Entity) getEntity.invoke(event);
                                Object breeder = getBreeder.invoke(event);
                                delegate.onCompatibleBreed(child,
                                        breeder instanceof Entity e ? e : null, cancellable);
                            } catch (ReflectiveOperationException exception) {
                                CSLLogger.debug(() -> "Breeding handler error: " + exception.getMessage());
                            }
                        }
                    }, plugin, true);
        } catch (ClassNotFoundException ignored) {
            // Legacy versions use the CreatureSpawnEvent BREEDING fallback.
        } catch (ReflectiveOperationException exception) {
            CSLLogger.debug(() -> "Could not register breeding handler: " + exception.getMessage());
        }
    }
}
