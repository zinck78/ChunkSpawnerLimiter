package com.github.sarhatabaot.chunkspawnerlimiter;


import com.github.sarhatabaot.chunkspawnerlimiter.command.AdminCommand;
import com.github.sarhatabaot.chunkspawnerlimiter.counter.CounterDataManager;
import com.github.sarhatabaot.chunkspawnerlimiter.listener.ChunkListener;
import com.github.sarhatabaot.chunkspawnerlimiter.listener.BreedingListener;
import com.github.sarhatabaot.chunkspawnerlimiter.listener.EventListener;
import com.github.sarhatabaot.chunkspawnerlimiter.listener.DespawnListener;
import com.github.sarhatabaot.chunkspawnerlimiter.listener.EntityTransformListener;
import com.github.sarhatabaot.chunkspawnerlimiter.notification.NotificationService;
import com.github.sarhatabaot.chunkspawnerlimiter.removal.Checks;
import com.github.sarhatabaot.chunkspawnerlimiter.removal.ExternalChecks;
import com.github.sarhatabaot.chunkspawnerlimiter.removal.RemovalTaskManager;
import com.github.sarhatabaot.chunkspawnerlimiter.removal.NamedPriorityEnforcer;
import com.github.sarhatabaot.chunkspawnerlimiter.removal.modes.RemovalMode;
import com.github.sarhatabaot.chunkspawnerlimiter.tracker.EntityChunkTracker;
import me.despical.commandframework.CommandFramework;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;

import java.io.File;

public class ChunkSpawnerLimiter extends JavaPlugin {
    private RemovalTaskManager removalTaskManager;
    private CounterDataManager counterDataManager;
    private PluginConfig pluginConfig;
    private NotificationService notificationService;
    private EntityChunkTracker entityChunkTracker;
    private ChunkListener chunkListener;
    private NamedPriorityEnforcer priorityEnforcer;

    @Override
    public void onEnable() {
        this.pluginConfig = new PluginConfig(this);

        CSLLogger.setup(this.pluginConfig);
        Checks.setup(pluginConfig);
        ExternalChecks.setup(this.pluginConfig);

        registerCommands();

        if (!pluginConfig.isEnabled()) {
            getLogger().info("ChunkSpawnerLimiter logic is disabled in config.yml.");
            return;
        }

        startRuntime(false);

        if (pluginConfig.isMetrics()) {
            try {
                Metrics metrics = new Metrics(this, 4195);
                metrics.addCustomChart(new SimplePie("removal_mode", () -> pluginConfig.getRemovalMode().getKey()));
            } catch (Exception e) {
                getLogger().fine("bStats metrics initialization skipped: " + e.getMessage());
            }
        }
    }

    private void startRuntime(boolean rebuildLoadedChunks) {
        this.counterDataManager = new CounterDataManager();
        this.removalTaskManager = new RemovalTaskManager(this, counterDataManager, pluginConfig);
        this.notificationService = new NotificationService(pluginConfig);

        // Lazy mode creates no repeating movement task and no per-entity UUID map.
        // Spawn/breeding/overflow checks use chunk.getEntities() on demand.
        this.entityChunkTracker = pluginConfig.isEntityMovementTrackingEnabled()
                ? new EntityChunkTracker(
                        this, counterDataManager,
                        entity -> Checks.shouldTrackEntity(entity, pluginConfig)
                                && (!pluginConfig.isVillagerOnlyMovementTracking()
                                    || entity.getType() == org.bukkit.entity.EntityType.VILLAGER),
                        40L)
                : EntityChunkTracker.disabled(this, counterDataManager);

        RemovalMode.setup(removalTaskManager);
        this.priorityEnforcer = new NamedPriorityEnforcer(
                this, pluginConfig, counterDataManager, entityChunkTracker, notificationService);
        this.entityChunkTracker.setArrivalHandler(priorityEnforcer::queue);

        PluginManager pluginManager = Bukkit.getPluginManager();
        this.chunkListener = new ChunkListener(this, pluginConfig, counterDataManager,
                removalTaskManager, entityChunkTracker, priorityEnforcer);
        pluginManager.registerEvents(chunkListener, this);
        // Optional modern hook: avoid forcing entity loads from ChunkLoadEvent.
        if (pluginConfig.isVillagerOnlyMovementTracking()) {
            chunkListener.setEntityLoadEventsAvailable(
                    com.github.sarhatabaot.chunkspawnerlimiter.listener.EntityLoadBridge
                            .registerIfSupported(this, chunkListener));
        }
        EventListener eventListener = new EventListener(this, pluginConfig, counterDataManager,
                notificationService, entityChunkTracker, priorityEnforcer);
        pluginManager.registerEvents(eventListener, this);
        BreedingListener.registerIfSupported(this, eventListener);
        DespawnListener.registerIfSupported(this, pluginConfig, counterDataManager, entityChunkTracker);
        EntityTransformListener.registerIfSupported(this, pluginConfig, counterDataManager, entityChunkTracker);

        // Loaded spawn chunks may predate plugin enable. Reconcile existing
        // overflow once, but never start periodic entity movement polling.
        if (rebuildLoadedChunks ||
                (pluginConfig.shouldTrimExistingOverflow() && !entityChunkTracker.isEnabled())) {
            chunkListener.rebuildLoadedChunks();
        }
    }

    private void registerCommands() {
        try {
            CommandFramework commandFramework = new CommandFramework(this);
            commandFramework.registerCommands(new AdminCommand(this, pluginConfig));
        } catch (IllegalStateException e) {
            if (e.getMessage().contains("Command Framework has not been relocated")) {
                getLogger().fine("Command Framework initialization skipped during testing: " + e.getMessage());
            } else {
                throw e;
            }
        }
    }

    @Override
    public void onDisable() {
        stopRuntime();
        this.pluginConfig = null;
    }

    private void stopRuntime() {
        if (this.chunkListener != null) {
            this.chunkListener.shutdown();
        }
        try {
            Bukkit.getScheduler().cancelTasks(this);
        } catch (RuntimeException exception) {
            if (!exception.getClass().getName()
                    .equals("be.seeseemelk.mockbukkit.UnimplementedOperationException")) {
                throw exception;
            }
        }
        HandlerList.unregisterAll(this);
        this.counterDataManager = null;
        this.removalTaskManager = null;
        this.notificationService = null;
        this.entityChunkTracker = null;
        this.chunkListener = null;
        this.priorityEnforcer = null;
    }

    public void onReload() {
        stopRuntime();
        this.pluginConfig.reload();
        CSLLogger.setup(this.pluginConfig);
        Checks.setup(pluginConfig);
        ExternalChecks.setup(this.pluginConfig);

        if (!pluginConfig.isEnabled()) {
            getLogger().info("ChunkSpawnerLimiter logic is disabled in config.yml.");
            return;
        }

        startRuntime(true);
    }

    public CounterDataManager getCounterDataManager() {
        return counterDataManager;
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public NotificationService getNotificationService() {
        return notificationService;
    }

    public RemovalTaskManager getRemovalTaskManager() {
        return removalTaskManager;
    }

    public EntityChunkTracker getEntityChunkTracker() {
        return entityChunkTracker;
    }

    public ChunkSpawnerLimiter() {
        super();
    }

    protected ChunkSpawnerLimiter(JavaPluginLoader loader, PluginDescriptionFile description, File dataFolder, File file) {
        super(loader, description, dataFolder, file);
    }
}
