> [!NOTE]
> **Project Status: Slowing Down**
>
> This project isn't going anywhere, but I'm gradually shifting it towards **maintenance mode**.
>
> I'll continue fixing bugs, updating compatibility when needed, and reviewing pull requests, but I want to be realistic about my available time. New features will be much less frequent than they have been in the past.
>
> Thanks to everyone who's used the plugin, reported issues, suggested ideas, or contributed. I really appreciate it, and I hope to keep this project useful for a long time, even if development slows down.

# ChunkSpawnerLimiter

[![Codacy Badge](https://img.shields.io/codacy/grade/d3eefde107a2471d856542804c4a3016?logo=codacy&style=for-the-badge)](https://app.codacy.com/gh/sarhatabaot/ChunkSpawnerLimiter/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)
[![Java CI](https://img.shields.io/github/actions/workflow/status/sarhatabaot/chunkspawnerlimiter/gradle.yml?branch=master&logo=github&style=for-the-badge)](https://github.com/sarhatabaot/ChunkSpawnerLimiter/actions/workflows/gradle.yml)
[![GitBook Badge](https://img.shields.io/badge/wiki-BBDDE5?logo=gitbook&logoColor=000&style=for-the-badge)](https://csl.sarhatabaot.net/)

Maintained fork of the [ChunkSpawnerLimiter](https://dev.bukkit.org/projects/chunkspawnerlimiter) plugin.

A Minecraft plugin for limiting configured entity types and block materials per chunk.
It checks new spawns and placements, and can clean up excess existing entities when chunks load or the configuration reloads.

## Features

* Configure per-chunk limits for entity types, block materials, or groups of either.
* Choose whether to prevent new violations, remove excess entities, or kill them.
* Recheck loaded chunks periodically when inspections are enabled.
* Target Minecraft versions from 1.8.8 through 1.21.10; the build requires Java 21.

## Shared entity group totals (optional)

The normal `entities.limits` group setting still caps **each entity type individually**.
For example, `LIVESTOCK: 16` allows 16 cows and 16 sheep.

You can now add an additional, combined cap:

```yaml
entities:
  entity-groups:
    LIVESTOCK: [COW, SHEEP, PIG, CHICKEN]
  limits:
    LIVESTOCK: 16
    VILLAGER: 8
  group-total-limits:
    LIVESTOCK: 48
  removal:
    mode: prevent
```

With this example, the chunk allows up to **16 of any listed species**
and **48 across those species combined**, plus up to **8 villagers**.
An animal belonging to `LIVESTOCK` counts toward the aggregate even if
it also has a direct type limit. The group total is opt-in; configs without
`group-total-limits` retain existing behavior.

Use `mode: prevent` to protect existing animals. It blocks new spawns when
a chunk is full; it does **not** delete existing mobs or strictly prevent
transported animals from crossing chunk boundaries. A movement poll updates
the count afterward. Animals already over a limit can remain until a player
moves or removes them.

For an Actuality-specific preset, see `presets/actuality.yml`. This is an
example to be copied into the plugin data folder, not automatically enabled.

### Actuality: lazy entity tracking

The preset sets `entities.movement-tracking.enabled: false`. This bypasses
the scheduled movement poll entirely and skips per-entity UUID bookkeeping
and redundant chunk-load counter initialization. New spawns, breeding
attempts and named arrivals still count the *live entities in their chunk*
before allowing them. The opt-in overflow cleanup still runs once when
chunks load (and when a name tag is applied).

Transporting animals across chunk borders can temporarily exceed the cap;
overcrowding is reconciled at the next loaded-population check. If immediate
cross-chunk enforcement is desired, set `entities.movement-tracking.enabled:
true` (the legacy/default behavior). Enabling movement tracking does not
change the standard chunk spawn limits.

### Actuality: named priority and breeding feedback

The Actuality preset explicitly opts into `entities.removal.trim-existing-overflow: true`.
This **changes the otherwise non-destructive `prevent` behavior**: on chunk
loads and applying a name tag, existing
over-limit animals may be silently removed (without drops). Unnamed animals
are removed before named ones. If 17 cows are all named and the cow cap is
16, one named cow is still removed. Younger mobs go first when both have the
same name priority, with UUID as a stable tie breaker. Existing named mobs
take precedence over newly arriving named mobs when no unnamed replacement
can satisfy the cap.

A freshly blocked breeding attempt is cancelled, not compensated by killing
a resident. The modern breeding event is handled when available, plus
a BREEDING spawn-reason fallback for older servers. The preset sends a
rate-limited chat message to the breeder with the type and limit. Consumed
breeding food and name tags are not refunded.

**Back up populated worlds before enabling this setting.** On a server
containing substantially over-limit legacy farms, the next chunk load can
remove a significant number of mobs. To keep the stock `prevent` behavior,
leave `trim-existing-overflow: false` (its default).

## Configuration

Edit `plugins/ChunkSpawnerLimiter/config.yml` and run `/csl reload` to apply changes.
The default configuration documents the available limits and removal modes.

Block scans capture a chunk snapshot on the server thread and count its blocks asynchronously.
There is no configuration switch for synchronous scanning.

## Testing

Run `./gradlew check` for the unit and legacy test suites. Real Paper integration tests use WatchWolf; see [WATCHWOLF.md](WATCHWOLF.md) for setup and commands.
