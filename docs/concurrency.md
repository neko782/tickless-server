# World concurrency

World events no longer run inside a single-threaded server tick. They run on
world workers, each in its own small transaction that locks only the blocks and
entities it uses. The goal is a server that uses every core while a normal
player cannot tell it apart from vanilla. Exact vanilla ordering between events
in different places is deliberately not preserved.

## What runs where

The **server thread** keeps the parts that are global by nature:

- the game clock (game time, day time, weather, world border, sleeping),
- the chunk system (tickets, loading, unloading, saving, lighting hand-off),
- networking: player packets, connection maintenance, chunk sending,
- tracker membership/pairing, commands and datapack functions.

Player tracking deltas run on each player’s worker lane, ordered with gameplay
packets and simulation steps. Pairing and tracker reads take the same player entity
lock. Delayed delta jobs coalesce; item-frame map updates remain on the server thread.

Movement publishes dirty waypoint sources and receivers after commit, without
entering the waypoint manager monitor while holding player locks. A periodic
server-thread pass refreshes each affected source/receiver connection once from
the latest positions. Registration, removal and connection state retain their
monitor protection. This avoids repeated locator-bar scans and the lock convoy
they caused during dense combat. `-Dtickless.coalesceWaypoints=false` retains the
immediate-update path for comparisons.

Established connections skip the pending-action monitor when that queue is empty.
Server gameplay packets use a per-connection FIFO and one scheduled Netty drainer,
which writes up to 256 packets per turn and flushes once when requested. Callbacks
remain attached to their real channel futures. Explicit flushes, direct event-loop
sends and codec changes drain older queued packets first; login/configuration
packets retain the ordinary path. `-Dtickless.batchPackets=false` and
`-Dtickless.emptyFlushFastPath=false` retain the control paths. `OutboundBatchCheck`
uses real Netty local transport to verify packet ordering, concurrent producers,
flush semantics, pre-connect delivery, callbacks, failed writes and barriers.

Waypoint entity connections now rebuild when their display type changes, rather
than because the source crossed an arbitrary coordinate-distance threshold.
Player-list broadcasts, names and lookups use one membership snapshot, including
sound broadcasts during respawn. Item tracking ignores already removed items;
new item pairings snapshot a live source under its entity lock. A newly acquired
main-thread snapshot lock releases when that read completes. Pre-existing locks,
worker transaction locks and Fabric event locks keep their usual lifetime.

The server's fixed 20 Hz loop services packets, connections, chunk sending,
and other network work. Scheduled callbacks handle world maintenance, the
simulation rate manager, and diagnostic counters. Independent timers drive each
ticking entity, chunk, and block entity, scheduled block and fluid ticks,
datapack functions, raids, spawning, and the dragon fight. The clock callback
advances world time and weather once per simulation step.
These timers coalesce missed deadlines, so a
slow event cannot build an unbounded queue. Entity, chunk and block entity timers all repeat
once per step, so they share a phase wheel (`PhaseWheel`): each timer sits in one of 64 phase
slots and one driver thread per level walks the slots and fires what it finds. A delay queue
paid a heap removal, a heap insertion and a new future per timer per step; with 100,000 falling
blocks its single thread saturated and chunk timers stopped firing. Scheduled-tick deadlines,
which vary, still use a delay queue. Player gameplay updates use their
entity timer on the server thread; non-player entities run on workers.

**World workers** run everything else:

| Event | Unit of work | Origin of the transaction |
| --- | --- | --- |
| scheduled block and fluid ticks | one tick | the ticked block |
| random ticks, precipitation | one pick | the picked block |
| block events (pistons, note blocks, chests) | one event, queued the moment it is raised | the event block |
| block entities (hoppers, furnaces, ...) | one ticker | the block entity |
| entities (except players) | one entity with its passengers | the entity |
| natural spawning | one chunk | chunk centre |
| raids, custom spawners, dragon fight | one pass | none |

There is no level-wide tick phase and no barrier between game ticks. Every
block-ticking chunk has a coalescing *event stream* (`LevelChunk.EventStream`)
for random picks. Scheduled block and fluid ticks use a deadline timer for each
active chunk, and block entities each have a timer and a
queued flag, so a slow hopper does not hold up its neighbors. Entities have a
similar queued flag. A chunk or
entity that falls behind skips the missed steps instead of queueing them, so an
overloaded contraption slows down only itself instead of the whole server.
`LevelEventScheduler` implements this.

Workers are **virtual threads** by default (`-Dtickless.virtualThreads=false`
switches to a fixed platform pool). An event that waits for a lock, or for the
server thread to load a chunk, unmounts, and its carrier core runs other events
meanwhile. The carrier count defaults to cores − 1 (`-Dtickless.workers=N`).

Each event gets its own virtual thread, but many small events are grouped before they reach
the workers: the phase wheel queues block entities that fire in the same slot eight to a task
(each still in its own transaction), entities are drained sixteen to a task, and scheduled ticks
run per chunk. A thread per moving piston tick woke an idle carrier each time, and under light
load that wake-up churn cost more CPU than the events (164% against 97% CPU on the light
benchmark below, together with the light batching). A shared queue emptied by a pool of
drainer threads was tried as well: it saved as much at light load but collapsed under lock
contention, when drainers blocked on world locks held back runnable events queued behind them.

### Sleeping block entities

A block entity with nothing to do stops ticking until something gives it work, as in Lithium.
Two kinds sleep so far (`BlockEntity.isIdle`):

- furnaces, blast furnaces and smokers that are unlit with no cooking progress: no fuel, no
  input, input without a recipe, or a full output slot;
- hoppers locked by redstone, once their cooldown has run out.

Every change that can give such a block entity work calls `BlockEntity.wakeUp`, which bumps a
counter: `setChanged` (every container change, including hopper transfers and menu clicks),
`setItem`, `removeItemNoUpdate`, `clearContent`, a new block state, loaded data and applied
components. A datapack reload wakes every block entity, since recipes and fuels can change.
After a tick commits, the scheduler checks `isIdle` and stores the counter as it was *before*
the tick took the block's lock. While that stored value matches, the phase wheel skips the
timer without queuing any work. A change made while the tick ran therefore keeps the block
entity awake, and a wake that rolls back with its transaction only costs one extra tick. A
sleeping timer stays in its wheel slot, so waking it needs no reinsertion.

What a sleep skips is invisible: an idle furnace's tick changes nothing, and a locked
hopper's tick only lowers a cooldown that is already spent. The exception is the hopper's
`tickedGameTime`, which stops advancing while it sleeps, so a hopper filling a sleeping one can
choose a cooldown of 8 instead of 7. Same-tick order is already not deterministic here (see
above). Furnace fuel whose burn time comes out as 0 is retried every tick, as on vanilla,
because burn times can be rolled from a loot context.

With 16,000 idle block entities (`gen_lag.py ... idle`), world events dropped from 321,000/s to
1,100/s and server CPU from 57% to 14% of a core; see [benchmarks](benchmarks.md#sleeping-block-entities).
`-Dtickless.sleepingBlockEntities=false` turns this off. Fabric mods that give furnaces or
hoppers new behaviour while idle need it off, or must call `wakeUp`.

## Locks

`WorldTxn` is one event's transaction. Locks are taken when world state is used
and released when the event ends:

- **Blocks**: `Level.setBlock`, `getBlockEntity`, `setBlockEntity` and
  `removeBlockEntity` always lock the block. `getBlockState`/`getFluidState`
  lock only within `READ_LOCK_RADIUS` (default 2, `-Dtickless.readRadius`) of
  the event origin; longer-range reads (pathfinding, AI scans) do not lock.
  Entity updates take no read locks at all. Block locks are 65,536 hashed
  lock objects per level (`WorldLocks`): adjacent positions land on unrelated
  locks, which `tools/BlockLockBench.java` showed to be the fastest option for
  both independent neighbours and hot blocks.
- **Entities**: each entity has its own lock. An entity update locks the entity
  and its passengers. Mutations from elsewhere lock the target: damage,
  knockback, effects, healing, removal, riding changes, synced data writes,
  player interaction, item pickup and merging, experience orb merging, hopper
  suction, and hopper access to container entities. Pushing between entities
  does not lock; a lost push is not visible in play.
- The **server thread** has an implicit transaction whose locks are released at
  safe points: after each packet, after each task, and between tick phases.
  `runExclusive` also releases them after each chunk task it runs while waiting for
  workers to stop; before, a chunk task could lock a block that a draining worker needed,
  and the server hung.

### Ownership of entity and block entity fields

Explicit lock calls only cover the paths someone thought of. The build therefore rewrites the
packaged jar (`buildtools/OwnershipTransform.java`, run by the `jar` task with the JDK's
ClassFile API): every `putfield` on a subclass of `Entity` or `BlockEntity`, outside
constructors, first calls `WorldOwnership`, which takes the owner's lock for the rest of the
event: the entity's own lock, or the block entity's block lock. New vanilla code and new mobs are
covered without changes. A write by the thread that already holds the lock costs two loads and a
compare; the TNT cube finishes in 14 s with the rewrite on or off. The same rewrite logs each
write's old value for rollbacks (see "Rollbacks" below).

Writes are not locked outside world transactions (chunk generation, IO), while workers are
paused, and on entities that are not in a level's entity storage yet: the server thread builds
and loads entities while it loads chunks, and locking one there could wait on a worker that
waits for that chunk. Deliberate races opt out with `@Unowned` on a field or method, or with a
`WorldOwnership.racy` scope around code whose writes go through shared setters:

- packet flags (`needsSync`, `syncPosition`, `syncVelocity`), cleared by the tracker;
- pushing between entities, including minecart and boat collisions, and explosion knockback
  (locking the knockback of every primed TNT in a chain serialized the explosions: 26 s
  against 14 s for the TNT cube);
- the per-tick pushability and in-block-state caches that neighbours fill in.

Objects an entity owns are covered the same way. When an entity stores a `Brain`,
`EntityEquipment` or `SimpleContainer` (villager, piglin, allay and horse inventories) in one of
its fields, it adopts it (`WorldOwnership.adopt`), and every method of those classes, and of the
player `Inventory`, first locks the owner. Single-memory reads and hints on a `Brain` are the
exception described under "Deliberate races"; they never expose container contents. On entities and block entities that are containers,
`getItem`, `setItem`, `removeItem`, `removeItemNoUpdate`, `clearContent`, `isEmpty`, `getItems`
and `setItems` lock the container on entry, so a container reached without a lookup is still
locked: copper golems keep the chest they found by scanning chunk block entities and take from
it later. Item stacks have no owner; they are reached through one of these.

`-Dtickless.ownership=check` leaves writes unlocked and instead logs each write site that ran
without its owner's lock, with sampled caller stacks, plus a summary at shutdown. Use it to find
what a new scenario touches; `lock` is the default and `off` disables the hooks.

A worker's transaction is bound as a `ScopedValue` for the length of its event, which every
block access looks up; it replaced a thread local that cost about 10% of worker time in
explosions. The transaction also remembers the last chunk the event read, so block reads skip
the off-thread chunk map lookup.

A transaction that cannot get a lock spins briefly, then waits. Each time it wakes,
`DeadlockResolver` walks the wait graph; if the wait closes a cycle, and the cycle is still there
when read a second time, the youngest member that can be rolled back is aborted. Nothing is lent
and nothing runs nested inside another event. The graph is read while everything runs: without
the second read, the misc scenario rolled back 280,000 transactions, most on stale cycles; with
it, about 100,000, nearly all while the server thread held thousands of block locks in a
`/forceload` that post-processed chunks. Chunk post-processing (the fluid and shape updates
generation leaves behind) now runs as a world event after the chunk is ready instead of on the
server thread, and misc makes about 8,500. `-Dtickless.cycleCheckMicros` delays the first check; 1 ms made the lock stress check 6×
slower with as many rollbacks.

#### Rollbacks

Every worker event is a transaction that can be undone:

1. **Undo log.** Before a write, the old value goes into the event's undo log (`UndoLog`,
   `WorldUndo`). The build's jar rewrite already hooks every field write on an entity or block
   entity to take its owner's lock; it now also reads the field's old value after the lock is
   taken and logs it. Field writes on item stacks, container menus, `SimpleContainer`, the
   player `Inventory` and `FoodData` are logged the same way without a lock. Changes that are not
   one field log an undo action: block states (`LevelChunk.setBlockState`), the block entity map,
   `NonNullList` slots (container contents), `EntityEquipment`, synced entity data and item
   component patches (which copy on write while logging). The fields of a player's connection
   listener are logged too (teleport ids, movement checks): a player's packets and steps run as
   events, and a rolled back move packet that ran again found "position already received this
   tick" and kicked the player. The log is parallel arrays: a field
   write costs a few stores and no allocation, and logs are pooled between events.
2. **Abort.** The victim throws `WorldTxn.Abort` (an `Error`, so `catch (Exception)` lets it
   through; `CrashReport.forThrowable` rethrows it) out of its lock or server-thread wait. Code
   that swallows it anyway is still rolled back when the event returns.
3. **Undo and retry.** The log is replayed newest first, locks are released, and the event
   waits, holding nothing, for what it was waiting for (it takes that lock, or waits for the chunk
   load) before running again from the start. It keeps its start stamp, so it only gets older:
   the oldest transaction in a cycle is never rolled back and every event finishes. This is the
   wait-for-graph form of wound-wait: detection only runs for waits that last, and only real
   cycles pay for a rollback.

Victims must be parked on a world lock or on the server thread. A transaction blocked entering
a Java monitor cannot be unwound, and members holding no monitor are preferred, since unwinding
out of a latch can leave what it guards half updated. The server thread's transaction is never
rolled back. When it has waited 2 ms (`-Dtickless.woundMillis`) for a lock, it rolls the holder
back at the holder's next lock operation, so a long event cannot stall connections.

Effects that another event could act on before a rollback wait for the commit
(`WorldTxn.afterCommit`): an entity that is killed or discarded only looks removed (and stays
locked) until its event commits, block events are queued then, and packets to players are sent
then (a rolled back teleport had sent a position the client acknowledged twice). Kicking a player
also waits for the commit, and then hands the disconnect to the server thread without waiting
for it. An entity added in an
event is locked until the event commits, and a rollback discards it again. Moving an entity to
another dimension or unloading it cannot be deferred, so the event becomes irrevocable
(`WorldTxn.makeIrrevocable`): it stops logging and is never chosen as a victim. Items arriving
through a portal together each merged with the other, and the two irrevocable events waited for
each other forever (`adversarial.py portal_rush`). Any lock taken after a dimension change could
do the same, so an event now takes a single token (`WorldTxn.IRREVOCABLE`) as it becomes
irrevocable, while it can still be rolled back, and keeps it until it ends. At most one event is
irrevocable at a time, so every cycle has a member to roll back: an event waiting for the token
holding other locks is an ordinary waiter. Dimension changes and unloads inside events are rare
enough that serializing the rest of those events costs nothing measurable. Optional locks after
that still use `WorldTxn.lockIfSafe`, which only takes a free lock, so the token holder does not
wait for a merge (`IrrevocableLockCheck`). The irrevocable stretches between the parts of a
`WorldTxn.scoped` event do not take the token: explosions would serialize.

A player's block action (placing, breaking, using an item) is acknowledged when its event
commits, not at the next connection tick (`PlayerLane`, `ServerGamePacketListenerImpl.finishLaneAcks`).
The client keeps its predicted blocks until the ack and then falls back to the last states the
server sent, so the ack must not overtake the action's block updates. The event records the
blocks it changes (`WorldTxn.trackBlockChanges`), and a commit action, which runs while the
event still holds its locks, sends the player those blocks and then the ack. Block changes
otherwise reach players from the world maintenance timer, which runs independently of the
connection tick: before, about a fifth of acks arrived before the block they acknowledged. An
action that changes more than 64 blocks, or blocks in another level, is acknowledged by the
connection tick as before, and so is everything with `-Dtickless.immediateAcks=false`.

Not undone, because a rollback is rare and none of them are visible in play: sounds and
particles (level-wide broadcasts are not deferred), brain memories, attributes and effect
timers, statistics, scores and advancements, scheduled ticks (they are deduplicated), chunk
tickets and random number state. Deliberate races (`@Unowned` fields, `WorldOwnership.racy`
scopes) are not logged either, so a rollback never writes an older value over a race's write.

`tools/WorldLockCheck.java` stresses exactly this: 32 virtual threads running random multi-lock
transactions on 12 locks, each adding one to a per-lock counter in a `NonNullList` without
atomics (640,000 transactions, ~200,000 rollbacks, 5.2 s against 5.9 s with lending) finish with no hang, no overlapping use, no
leaked owner, and counters that add up to the increments of the attempts that committed.

#### The wait graph

A cycle can only be broken if every way a transaction waits is an edge the resolver sees:

- **world locks**;
- **the server thread**: a worker blocked on it (`WorldTxn.awaitServerThread`: off-thread chunk
  loads and `executeBlocking`) waits for a pseudo-lock the server thread always owns. Before,
  a power-127 fireball explosion held an entity lock and waited for a chunk load while the
  server thread, ticking that entity, waited for its lock, until the watchdog stopped the server;
  now the explosion is rolled back and waits for the chunk holding nothing;
- **Java monitors**: the build puts hooks around every `monitorenter`/`monitorexit` and turns
  synchronized methods into explicit monitor blocks, so a transaction blocked entering a latch
  is an edge to the transaction holding it. Field writes lock their owner, so world code can
  wait for a world lock inside a latch; the member waiting for the lock is the one rolled back.
  To find a monitor's holder, a transaction joins a shared set on its first monitor and leaves
  it when its event ends. Joining and leaving around every outermost monitor made that set's
  counters about 15% of bee update time.

`ServerThreadCycleCheck` and `MonitorCycleCheck` cover both edges; a wait chain report says what
each member waits on, and flags a member blocked outside the graph. `-Dtickless.traceCycles=N`
logs the first N cycles broken with every member's stack.

A cycle the resolver confirms but cannot break (only the server thread, irrevocable events and
members blocked entering a monitor) is logged as an error, "World wait cycle no rollback can
break", once the same members have formed it for `-Dtickless.stuckCycleMillis` (default 1 s),
with every member's stack (`StuckCycleCheck`). Before, it showed up only as a 10 s lock wait, if
anyone waited on a world lock at all. A server thread waiting for a lock runs chunk tasks, so a
cycle in which it waits for a lock and a member waits for the server thread is not reported.

#### Holding locks for less than the whole event

Locks are kept until the event ends. `WorldTxn.scoped` runs a self-contained part of an event as
its own transaction: what the event did so far commits and its locks are released, then the part
runs, is rolled back and retried on its own if needed, and commits. Explosions use it per damaged
entity and per broken block, and a fireball's direct hit is scoped too; they now measure exposure
without locks, break blocks, and hit entities last. A power-127 explosion used to keep every
entity it hit, including the player the server thread ticks, locked for the seconds it spent
breaking blocks. Once a part has committed the event cannot restart, so what it does between and
after parts is irrevocable; for explosions that is the explosion's own entity being discarded.

Waiting transactions park with `LockSupport`, never `Object.wait`. On JDK 25, hundreds of
virtual threads looping on a contended monitor wait kept every carrier busy: a fireball
explosion holding 580 locks waited for the server thread to reload an unloading chunk, got its
chunk, and never got a carrier back, so the server hung until the watchdog stopped it.
`tools/WorldLockStarvationCheck.java` (run with `-Djdk.virtualThreadScheduler.parallelism=4`)
reproduces it: one lock holder waits for the main thread while 300 transactions wait for its
lock. With `Object.wait` the holder was stuck or resumed only after ~9 s; with parking it
resumes in under 1 ms.

### What is still shared

There is no global or chunk lock around world events. These short latches
protect data structures that are updated in place; each is held for the update
only, never while waiting for a world lock:

- per chunk section: a sequence lock in `PalettedContainer` (reads are
  lock-free and retry if a write overlapped);
- per chunk: heightmaps, sky light sources, scheduled tick containers,
  changed-block tracking for packets;
- per entity: synced data dirty flags;
- per POI section and record: POI storage and ticket claims;
- entity sections: copy-on-write lists, and 256 chunk-striped latches that keep
  an entity's section membership consistent with chunk visibility changes;
- the distance manager: a `StampedLock` written only by the server thread;
  worker reads are optimistic and lock-free unless a write overlapped;
- natural spawning: one monitor per level on the per-tick spawn state (mob caps
  and spawn potentials);
- the scoreboard: its own monitor, a leaf lock. Workers update kill, death and statistic scores
  while commands and functions change scores on the server thread.
- the structure cache (`StructureCheck`): one latch around its maps; storage scans and generation
  checks run outside it, and cached reference counts are replaced, never changed in place. World
  workers (explorer maps, dolphins, eyes of ender) and chunk generation threads check structures
  at once; a concurrent rehash crashed a 1,024-player run at shutdown.

Some shared data is published whole instead: the server thread rebuilds the debug subscriber map
each tick (`ServerDebugSubscribers`), and a stat type copies its map when it creates a stat
(`StatType`, read on every award). The entity tracker map (`ChunkMap.entityMap`) is a
`ConcurrentLong2ObjectMap`: workers look up an entity's tracker to broadcast its packets while
the server thread adds and removes entities. An entity's attribute map is a `ConcurrentHashMap`:
other entities read armor, reach and waypoint ranges without its lock while its own event adds
attribute instances.

### Finding shared collections

World locks cover entities, block entities and blocks. A collection on a server-wide object is
covered only if someone made it safe, and each one that was not (the waypoint manager, the
entity tracker's player sets, the player list, the structure cache) crashed a stress run before it
was fixed. `RaceCheck` finds them: built with `-PraceCheck`, the jar rewrite
(`buildtools/RaceTransform.java`) sends every call the game makes on a java.util, fastutil or
Guava collection or iterator through a bridge, and a server run with `-Dtickless.raceCheck=true`
logs a "Collection race" with both threads' stacks when

- two threads are inside one collection at once and either one changes it,
- a thread changes a collection while another thread's for-each over it is open (an iteration
  ends when its iterator is exhausted or the method that started it returns), or
- the server thread changes a collection its own iteration is going over from a chunk task it
  ran while that iteration waited for a world lock (see "Known gaps").

Only overlaps that really happen are reported, so collections handed between threads in order
(chunk generation stages, light engine tasks, pooled buffers) are not; a lock-set checker
(Eraser) reported hundreds of those. One call in 32 on a collection more than one thread has used
spins for 20 µs while it is marked, so that racing calls are likely to overlap. `isEmpty` and
`size` are not checked: a stale answer cannot break a collection. The rewrite is never part of a
normal build; the checked server runs several times slower.

`tools/stress/races.sh` builds it into `build/install-race`, runs a self-test
(`tools/race/RaceDetectorSelfTest.java`: rewritten fixture code must report concurrent adds and an
add during another thread's for-each, and must not report a for-each that returned early, a
hand-off between threads or adds under a common lock), then `basic`, `misc`, `graph` and a
players scenario (bots joining, leaving, dying and building while commands run). It fails on any
race not in `tools/stress/race-allowlist.txt`, which names the deliberate ones: optimistic
`StampedLock` reads that are validated and retried. Its first runs found the entity tracker map,
attribute maps, stat types and debug subscribers above.

Commands and datapack functions (including `#minecraft:tick` functions) run in the server
thread's transaction while workers keep going; their block and entity writes lock like any other
event's. Before, every command paused all workers, so a datapack with a tick function made the
world effectively single-threaded. Save snapshots, chunk and entity unloads, and global data swaps pause the workers through
`WorldTxn.requestExclusive`. New events wait at an admission gate while running events drain;
the server thread continues servicing chunk loads and connections. **Autosave**, **`/save-all`**,
eager chunk saves and final unload saves therefore serialize entities and block entities only
when no worker can still mutate them. Deferred unloads recheck holder identity and entity
visibility after draining, so tickets that revive a chunk cancel its pending unload. The data
swap at the end of **`/reload`** (also used by `/datapack`) uses the same gate.

Chunk random-tick producers and scheduled-tick drains also participate in admission through
`WorldTxn.runEventSource`: their individual events retain independent transactions, but the
chunk cannot be snapshotted between extracting a scheduled tick and running it. Nested events
from an already admitted producer may finish through a closing gate. Queued block-entity
and spawning events recheck lifetime inside their transaction after passing the gate, so
work queued before unload cannot mutate a discarded chunk afterward.

## Deliberate races

Where a stale or lost answer is invisible to a player, workers read without locks:

- **Entity lookups by box** in crowded sections (12+ entities) scan a per-section copy of the
  entities' bounding boxes, refreshed each game tick and indexed in one-block horizontal
  cells so a query scans only nearby cells. One reader builds an immutable snapshot while
  other readers scan live entities until it is published. A box can be up to one tick old,
  so a query can miss an entity that moved into it during the tick; every hit is checked
  against the live box. Membership stays exact without a rebuild: an entity gets a unique
  join stamp under the section's monitor when it enters a section and 0 when it leaves, and
  the cache keeps each entity's stamp, so a changed stamp drops an entity that left; entities
  that joined since the build are the tail of the membership array (appends happen in stamp
  order) and are scanned live. Rebuilding on every membership change cost more than the scans
  it saved where snowballs and flying bees come and go: several rebuilds per section per tick.
  Pure class queries can skip repeated casts for cached members when every member of the
  cached snapshot matches the class; later joiners are still checked. Exact-class and custom
  predicates still run per candidate. Pushing in a dense mob pen was about 40% of worker CPU
  before the first box cache. While the game clock is frozen, the level's box clock advances
  with each world maintenance run (about every 50 ms), so entities moved by players or
  commands still show up; a per-query `System.nanoTime()` age check cost 3% of fish and
  endermen updates.
- **Pushing** remembers per entity and game tick whether its position is entity-ticking and
  not climbable, and reads health and sleeping state from plain fields that mirror the synced
  data.
- **Path notifications** keep a concurrent set of tracked mobs with unfinished paths,
  refreshed after movement, recomputation, navigation ticks and stop. Block changes still
  notify chunks and invalidate path-type caches; idle mobs need no path invalidation scan.
  Navigation hints can race and membership is corrected on the next owner update.
  Replacing a navigation keeps the mob's current path in the index; callbacks from an
  obsolete navigation cannot remove it. Tracking removal also removes the mob.
- **Equipment visibility** uses immutable snapshots of armor coverage and mob-visibility
  components. The owner publishes a snapshot on replacement and after inventory ticking;
  in-place component/count changes can remain invisible to targeting until the next entity
  step. Ordinary equipment getters still lock the owner before handing out a mutable stack.
  Targeting therefore does not keep every mob it inspects locked until its sensor event ends.
- **Brain memory cells** permit stale observations and lost hints (look/walk targets, nearby
  entities, job-site intentions). Registration publishes an immutable array of stable cells,
  indexed by memory-type identity. Construction batches registrations before publication.
  Each hint publishes its value and expiry together; expiry can only clear the observed hint. Single-cell getters/setters/erasers do not
  acquire another entity's lock; brain ticking, behavior configuration and bulk operations
  still do. Memories were already excluded from rollback. Item transfers, damage, block
  changes and POI ticket claims retain their existing locks. The visible-neighbor cache is
  concurrent and allocates entries only for inspected targets; it computes targeting outside
  map monitors so world waits cannot retain a map bin lock. Nearby snapshots lazily index
  entity types, preserving nearest order and live filtering while skipping irrelevant types.
  A snapshot containing only one type needs neither an ordinal array nor a type map.
- **Passengers** are moved by their vehicle's step. A passenger's own entity event reads its
  vehicle without a lock and returns before starting a transaction; before, it locked the whole
  stack and waited for the vehicle's step only to find out. A passenger dismounted at that
  moment misses one step. In a pen of four-high mob towers, three of every four entity events
  were these.
- **Team rules in pushing** are skipped while nobody is on a team (`Scoreboard.hasTeamMembers`,
  read without the scoreboard monitor). A team joined during a push applies from the next one.
- **Synced data writes** that do not change the value return before locking the owner or
  logging an undo action. Fish and bees rewrite the same air supply, frozen ticks, no-gravity
  and swimming flags every step; that was 12–14% of their update time. A write racing with
  such a no-op wins as if it came after it.
- **Game event listener registries** are created only when a listener registers and are read
  without the chunk's latch. Posting an event (a bee's flap, a mob's step) visits only
  sections that ever had a listener; before, every post created or looked up the registry of
  every section in range under a latch, which was 46% of bee update time.
- **Path-type cache** entries are one long each: the position's mixed hash, whose low bits
  pick the slot and whose other bits are stored, plus the type. Workers overwrite each other's
  entries but never pair a position with another's type, and a hit is exact. Opaque `VarHandle`
  accesses guarantee atomic long entries without ordering unrelated world state. The table has
  65,536 slots instead of vanilla's two arrays of 4,096, which flying bees kept evicting.
- **Moving piston collision shapes** keep one immutable geometry memo per block entity,
  published through a volatile `@Unowned` field. It matches the actual base and moving
  shape identities, direction and exact float offset. Concurrent readers can redundantly
  build the same result, but always return geometry for their observed inputs. Progress
  changes, reloads and rollback need no invalidation call; different inputs miss the memo.
  No-clip is checked first, and dynamic block shapes still rebuild on every query. The
  memo neither takes a world lock nor changes world state.
- **Entity ids** taken on a worker skip the check against the entity tracker, which is not
  safe to read there. The counter would have to wrap past two billion first.
- **Light checks** for changed blocks are collected in a set and taken by the next light
  update, instead of one dispatcher task per change.

POI query snapshots cache only membership, not ticket availability. Block edits invalidate
those snapshots; each query tests the live record occupancy, and ticket mutation retains
its existing synchronization.

Endermen lock the block they take or place and read it again under the lock. Entity updates
read blocks without locks, so two endermen could both see the same dirt block, both remove it
and both carry one, and a block placed by one could be overwritten by another's placement.

Worker collision reads require a full chunk even if its tickets were demoted after the event
started. The loaded fast path is unchanged; an unavailable chunk is requested with a worker
ticket and the event waits through the normal server-thread edge. Treating a demoted chunk as
air let entities fall through platforms during forceload flicker, sometimes dying far below.
Mob pickup locks the item before checking its removal state or reading its stack; inventory
carriers also revalidate retained references on entry. Otherwise two allays could copy the
same stack, since a fully collected item's removed entity still retained its stack.
`FlickerConservationCheck` reproduces both failures with real terrain and allays.

Copper golem chest observations capture the chest position once before looking up its
block state. A world lookup can yield to other work that changes the observation; using
one immutable position prevents a null or unrelated position from being read afterward.
Chest inventory access still locks the container.

## Other thread-safety changes

Vanilla code that assumed one thread and had to change:

- `RedStoneWireBlock` kept its "ignore wire signals" flag on the block
  singleton. Two workers measuring wires at once flipped it for each other and
  wire power never settled ("Too many chained neighbor updates"). It is now
  per thread.
- The level random is backed by `ThreadLocalRandom`, and `setSeed` on it has no
  effect. `LegacyRandomSource` retries instead of throwing when two threads use
  it.
- Neighbour-update queues are per thread; `isHandlingTick` is per thread.
- Scheduled ticks are drained per chunk (`LevelTicks.runDueTicks`) when the
  chunk's earliest deadline arrives. There is no level-wide due-time index.
  Deadlines count from when the clock reached the current game time, so chained
  ticks do not drift; a timer that fires before its step waits for the clock
  callback instead of polling, and a chunk drains at most once per game time.
- Block entity tickers register timers when their chunk becomes block ticking.
- Sensors sort nearby entities by distance while those entities move on other
  workers; `SnapshotSort` reads each distance once so the sort stays valid.
- Chunk packets serialize into a growable buffer because a section can change
  between measuring and writing it.
- Ticket changes and entity-tracker changes made on workers are handed to the
  server thread in order. Players, which only change on the server thread, are
  registered with the chunk map immediately.
- Saved data, random sequences, POI storage, entity lookups and game event
  listeners use concurrent collections; creating the same saved data or POI
  chunk from two threads yields one instance.
- Falling blocks lock their landing spot, and one that finds a full block where it lands
  stacks on top instead of dropping as an item. Blocks of one falling column tick on different
  workers and can land in any order; before, 7% of a 102,400-block sand sheet dropped as items.
- Structure template palettes cache their blocks by type in a concurrent map; parallel
  chunk generation filled the plain map from several threads and threw.

## Testing

`tools/stress/` drives a real dedicated server in Docker with console commands:

```sh
tools/stress/regress.sh             # builds into build/install-dev, runs all scenarios in ~30 s
tools/stress/regress.sh 20 basic    # longer load, one scenario
python3 tools/stress/gen_world.py build/stress-8k.txt 60 8000 4
tools/stress/run.sh t8k build/stress-8k.txt
python3 tools/stress/check.py build/stress-t8k/out.log 135424
```

- `regress.sh` builds into `build/install-dev` (`-PinstallTo`), so a server someone is playing on
  from `build/install` keeps its jar, and runs `basic`, `misc`, `clock` and `graph` in parallel
  with a 5 s load window. Scripts wait with `poll` (every 0.5 s) for chunks to load and for the
  TNT cube to finish, instead of fixed sleeps; a round takes about 30 s including the build.
- `gen_graph.py` is the item conservation test: two 32×32 layers of chests, barrels, waxed copper
  chests, hoppers pointing at random neighbours, droppers pushing items back up and dispensers
  firing them into a glass box, with hopper minecarts on a powered rail loop and waxed copper
  golems carrying items between chests. About 190,000 items of 31 kinds (unstackable and renamed
  ones included) are placed; after the run the world is frozen and every container, dropped item,
  minecart and golem hand is read back. `check_graph.py` compares per-item totals. `GRAPH_SEED`
  picks the layout.
- `gen_world.py` builds closed hopper rings (the item total must not change),
  observer clocks with sticky pistons, a wheat farm at random tick speed 100,
  and a pen of mixed mobs.
- `gen_misc.py` covers TNT chains, a nether portal with mobs walking through,
  redstone dust with comparators, minecarts, a village with beds and job
  blocks, fire spread, lava and water, and sculk sensors.
- `gen_lag.py` builds lag machines: piston-gated fluids, unmergeable items, minecart piles,
  a redstone dust and lamp field, and furnaces.
- `gen_heavy.py` builds one heavy non-mob workload per run: a 32×32×32 TNT cube chain
  reaction, a 160×160×4 sheet of falling sand, 1,200 piston clocks shoving glowstone under a
  roof (block light), and 1,024 never-generated chunks force-loaded at once. `heavy.sh` runs
  them and `bench_heavy.py` prints the time to finish, game ticks, events/s, step rates,
  server-thread load and CPU. Finite workloads use `run.sh`'s `poll` directive, which repeats
  a test command until it passes.
- `fireballs.txt` rains big fireballs on mobs while force-loaded chunks toggle, so chunks unload
  while explosions hold entity locks. `funcs.txt` with
  `DATAPACK=tools/stress/datapack/tickstress` runs a tick function that selects, scores and
  teleports every entity each tick while TNT kills mobs, then `/reload` and `/save-all`.
- A `bots N SECONDS` line starts `tools/BotClient.java`: headless players that
  log in, walk, and place and break blocks, reporting action round-trip latency.
- `run.sh` kills a run on the first logged error, on a world-lock wait over
  10 s (unless `LOCK_WAIT_KILL=0`, for workloads that legitimately hold locks that long),
  on a wait outside the deadlock resolver's graph, or at `TIMEOUT`, and writes thread dumps:
  platform threads first (safe), then virtual threads, which can crash JDK 25.
  It disables `pause-when-empty-seconds`: with no players, vanilla's default pauses the
  server after 60 seconds, which silently cut earlier long runs short.
- `-Dtickless.statsIntervalSeconds=5` (set by `run.sh`) logs world events per
  second, the chunk and entity **step rate** (steps per second that actually
  ran, the tickless counterpart of TPS), the server thread's CPU load, loaded chunks, and
  rollbacks (with how many the server thread asked for).

Unit checks, run the same way as before:

```sh
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/workspace" -w /workspace eclipse-temurin:25-jdk \
  sh -c 'mkdir -p build/checks && javac -cp "build/install/minecraft-server/lib/*" -d build/checks tools/WorldLockCheck.java tools/WorldLockStarvationCheck.java tools/LevelTicksCheck.java \
    && java -cp "build/checks:build/install/minecraft-server/lib/*" WorldLockCheck \
    && java -Djdk.virtualThreadScheduler.parallelism=4 -cp "build/checks:build/install/minecraft-server/lib/*" WorldLockStarvationCheck \
    && java -cp "build/checks:build/install/minecraft-server/lib/*" LevelTicksCheck'
```

### Results

AMD Ryzen 9 8940HX (32 threads), Temurin 25 in Docker, 12×12 force-loaded
chunks with 529 hopper rings, 552 piston clocks and a farm, one sample each:

| Mobs | Vanilla | Tickless |
| ---: | --- | --- |
| 3,000 | 41 ms/tick, keeps 20 TPS | 2.4 ms/tick on the server thread; 20 steps/s; ~150k events/s |
| 8,000 | 152 ms/tick, ~6.6 TPS, 31 s behind | 5–6 ms/tick; 20 steps/s for every chunk and entity; ~250k events/s |
| 16,000 | not run | 12 ms/tick; 20 steps/s; ~410k events/s |

Hopper item totals were conserved in every run (135,424 items). With 20 bots on
an otherwise idle platform, block actions are acknowledged in about 0.5 ms (p50), against
0–50 ms on vanilla; see "Player actions" in the benchmarks.

## POI section identity and query snapshots

Section creation uses 64 chunk stripes so competing workers return the same
section instance. Completed pending loads are not unpacked by the tick thread
while a synchronous reader owns their unpacking. This prevents replacement of
objects that another worker has already mutated.

POI chunk-query snapshots flatten section/type groups into immutable record arrays.
Loading, creating or dirtying a section increments its chunk's version; queries
rebuild membership after invalidation. A lazy cursor tests each group's type once
and reads each record's live ticket availability. Section snapshot readers do not take
the section monitor: the distance tracker can query while holding its own monitor,
whereas section mutation can notify the distance tracker. A version accompanies each
published snapshot so an overlapping invalidation forces a rebuild on the next query.
Concurrent builds may observe
concurrent edits, and the next query rebuilds after invalidation. Nearby searches also reuse flattened groups for the same chunk square, with a
4096-entry retention limit. A global query version invalidates squares after
section creation, loading or dirtying; caller predicates, square/range geometry
and live occupancy still run for each query. Membership is bound when the stream
is consumed. Ticket claims and releases retain their exclusive synchronization.
`SectionStorageCheck` and `PoiChunkCacheCheck` cover identity, competing unpack
ownership, concurrent additions, ticket changes, save and reload.

Releasing a POI that a block edit already removed returns false. A villager's remembered-type
check and ticket release can overlap TNT destroying its job block or bed. Section release
also holds the section monitor so it cannot observe the temporary empty map during refresh.
`PoiSnapshotCheck` covers release after removal; live records retain their ticket synchronization.

## Known gaps

- While the server thread waits for a world lock it runs chunk tasks, so workers waiting for a
  chunk load can finish. Any server-thread loop that can wait on a lock can therefore see its
  data change underneath it. This crashed `PersistentEntitySectionManager.processUnloads`
  (fastutil iterator NPE) when power-127 fireballs held entity locks while chunks unloaded; that
  loop now iterates a copy. The race check reports any other loop with this shape that a stress
  run reaches, but loops no run reaches may remain.
- 100 power-127 fireballs at once (`/function fireballs:rain`) no longer hang or crash the
  server, and the server thread stays responsive, but entities stall for tens of seconds while
  the explosions, each breaking millions of blocks, finish. Vanilla freezes entirely.
- A rollback does not undo everything (see "Rollbacks"), and an event that became irrevocable
  (it moved an entity to another dimension, or committed a scoped part) cannot be rolled back.
  Only one event at a time can become irrevocable by a dimension change, but the stretches
  between scoped parts do not take that token; a cycle of such stretches, monitors and the
  server thread is reported as an error after a second.

- Shutdown after `gen_heavy.py chunkgen` once waited forever for chunk-map work and has not
  recurred. A light update that throws could have caused it (it left the "update scheduled"
  flag set) and no longer can; `stopServer` also logs the outstanding kinds of work every 10 s
  ("Still waiting to stop") in case it is something else.
  A later misc run stalled inside the normal tick wait during shutdown. The drain loop now
  uses its own bounded deadline; the misc rerun and full regressions exited successfully.
  The stress harness records forced kills and nonzero exits so they cannot report PASS.

- Same-tick ordering between different chunks is not deterministic. Redstone
  that relies on exact update order across chunk borders can behave differently
  from vanilla.
- `/tick sprint` advances the clock faster than chunks can follow; busy chunks
  skip steps instead of running every one.
- Command blocks run their command on the worker that ticks them, with normal
  locks, not in a world pause.
- The stress bot does not implement full client movement, so vanilla also
  reports it as "moved too quickly". It still exercises login, chunk sending,
  and block placing and breaking.

## Earlier work

Parallel chunk generation (below), the block lock benchmark and the parallel
changed-chunk packet preparation predate world workers and still apply.

### Parallel chunk generation

World generation dispatches work for separate chunk centers onto the background
pool. Tasks for the same center run in order; nearby centers may advance their
independent stages at the same time. Feature steps lock their 3-by-3 write
neighbourhood with ordered striped locks (`ChunkNeighborhoodLocks`), because
features can write one neighbouring chunk. `tools/ParallelChunkDispatcherCheck.java`
verifies overlap, same-center ordering and feature-write exclusion;
`tools/ParallelChunkDispatcherBench.java` measured a dense 10-by-10 queue at a
median 85 ms with same-center exclusion versus 205 ms with 5-by-5 exclusion.

### Block lock benchmark

`tools/BlockLockBench.java` compares 4096 individual `ReentrantLock`s with two
atomic bitsets. One sample (successful lock/unlock operations per second, 32
threads):

| Work distribution | Dense bits | Padded bits | Individual locks |
| --- | ---: | ---: | ---: |
| One hot block | 23.1 M | 17.1 M | 101.8 M |
| One distinct adjacent block per worker | 3.7 M | 798.6 M | 689.4 M |
| Random among 4096 blocks | 30.2 M | 224.0 M | 297.5 M |

World locks use individual lock objects with hashed placement, combining the
hot-block behaviour of individual locks with the spreading of the padded bitset.
