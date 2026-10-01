# Lab 4.2 Design Document

---

## Preface

### Goals

- Provide a **linearizable** sharded key/value service.
- Replicate each group's command log with Paxos, using `PaxosServer` as a sub-node.
- Order client operations and reconfiguration steps in the same per-group Paxos log.
- Transfer shard KV data and AMO dedup state during reconfiguration.
- Reject requests for shards this group does not currently own.

### Desired Fault Model

- Tolerate a minority crash within any single replica group (per-group Paxos liveness, identical to Lab 3).
- Tolerate a minority crash within the ShardMaster Paxos group.
- Tolerate arbitrary message delay, drop, reorder, and duplication, both within a group and between groups.
- **Not tolerated:** majority crash of any single group; loss of a majority of ShardMasters; Byzantine failures; server restart with persistent state.

### Challenges

- **Reconfiguration ordering.** Client operations and config changes can race, so both must be ordered in the same Paxos log.
- **Paxos as a sub-node.** Paxos decides commands; the parent `ShardStoreServer` executes them.
- **Shard handoff safety.** The old owner must not delete a shard until the new owner has installed it.
- **At-most-once across handoff.** Retried client operations must not execute twice after a shard moves.
- **Sequential reconfiguration.** Groups apply configs one at a time without skipping intermediate ownership changes.

### Assumptions

- Group membership (the set of `Address`es in each replica group) is fixed at startup. `Join`/`Leave` at the ShardMaster add or remove *whole groups*, not individual servers within a group.
- The ShardMaster (Lab 4 Part 1) is correct and provides linearizable `Query`.
- The Lab 3 `PaxosServer` is correct under the modifications described above (no behavioral change to consensus, only to what it does with decided commands).

---

## Protocol

### Kinds of Nodes

**ShardStoreServer**

- One per replica. Holds `AMOApplication<KVStore>`, shard ownership state, current/target configs, and handoff bookkeeping.
- Embeds a `PaxosServer` as a sub-node at sub-address `"paxos"`.
- Proposes all state-changing events to Paxos and executes only decided commands.
- Periodically polls a ShardMaster for the latest `ShardConfig`.

**PaxosServer (sub-node mode)**

- Constructor: `PaxosServer(selfPaxosAddr, peerPaxosAddrs, parentAddress)`.
- In sub-node mode, Paxos does not execute commands. It forwards decisions to the parent as `PaxosDecision{slot, command}`.
- The parent proposes commands through `Propose{command}`.

**ShardStoreClient**

- Caches the latest known `ShardConfig`. For each operation, computes `keyToShard(operation.key())`, finds the owning group, and broadcasts to that group's members.
- On `WrongGroup{newConfig}`: installs `newConfig` if newer, re-routes the same `AMOCommand` (same sequenceNum) to the new owner.

**ShardMaster** (Part 1, used here only as a queried service)

- Receives `Query` from `ShardStoreServer`s and from `ShardStoreClient`s. Receives `Join`/`Leave`/`Move` from the test harness only.

---

### Auxiliary Types

**ShardConfig** (from Part 1)


| Field       | Type                                             | Meaning                                                  |
| ----------- | ------------------------------------------------ | -------------------------------------------------------- |
| `configNum` | int                                              | Monotonic; starts at `INITIAL_CONFIG_NUM`                |
| `groupInfo` | `Map<Integer, Pair<Set<Address>, Set<Integer>>>` | `groupId -> <group members, shards owned by that group>` |


The design treats the following as helper lookups derived from `groupInfo`, not as stored fields on `ShardConfig`:

- `ownerOf(config, shard)`: scan `config.groupInfo` and return the `groupId` whose shard set contains `shard`; return `null` if no group owns it.
- `membersOf(config, groupId)`: return `config.groupInfo.get(groupId).getLeft()`, the member addresses for that group.
- `shardsOf(config, groupId)`: return `config.groupInfo.get(groupId).getRight()`, or an empty set if the group is absent.

**ShardStatus** (per shard, per server)

```
enum ShardStatus { NOT_OWNED, RECEIVING, OWNED, GIVING_UP }
```

**Commands carried in the per-group Paxos log**


| Command                                               | Source                                     | Meaning at execution                                                                  |
| ----------------------------------------------------- | ------------------------------------------ | ------------------------------------------------------------------------------------- |
| `AMOCommand{op, clientId, seq}`                       | Client request via `ShardStoreServer`      | Execute if `shardStatus[keyToShard(op.key())] == OWNED`; reply `WrongGroup` otherwise |
| `NewConfig{ShardConfig}`                              | Periodic Query reply                       | Begin transitioning from `currentConfig` to this config                               |
| `ShardReceived{configNum, shardNum, kvs, dedupSlice}` | `ShardData` arrival from peer group        | Install this shard's data; mark `OWNED`                                               |
| `ShardSent{configNum, shardNum}`                      | `ShardAck` arrival from peer group         | Free this shard's data; mark `NOT_OWNED`                                              |
| `ConfigComplete{configNum}`                           | Proposed after last pending entry resolves | Finalize the transition: `currentConfig = targetConfig`                               |
| `PaxosNoOp`                                           | Phase-1 hole filler in Paxos               | Skip on execution                                                                     |

---

### State of ShardStoreServer


| Field               | Type                                    | Initial Value               | Meaning                                                                                                                                        |
| ------------------- | --------------------------------------- | --------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| `group`             | `Address[]`                             | from ctor                   | This group's member addresses                                                                                                                  |
| `groupId`           | int                                     | from ctor                   | This group's ID                                                                                                                                |
| `shardMasters`      | `Address[]`                             | from ctor                   | ShardMaster replica addresses                                                                                                                  |
| `paxosAddress`      | `Address`                               | `subAddress(self, "paxos")` | Sub-node address                                                                                                                               |
| `app`               | `AMOApplication<KVStore>`               | empty                       | KV state and AMO dedup table for all currently-owned shards                                                                                    |
| `shardStatus`       | `Map<Integer, ShardStatus>`             | every shard `NOT_OWNED`     | Per-shard ownership                                                                                                                            |
| `currentConfig`     | `ShardConfig`/null                      | null                        | Latest fully installed config; null means no configuration has been installed yet                                                              |
| `targetConfig`      | `ShardConfig`/null                      | null                        | The config being transitioned into                                                                                                             |
| `pendingIncoming`   | `Set<Integer>`                          | empty                       | Shards we expect to receive before `ConfigComplete`                                                                                            |
| `pendingOutgoing`   | `Map<Integer, Integer>`                 | empty                       | Shard → destination groupId we still owe                                                                                                       |
| `outgoingPayload`   | `Map<Integer, ShardData>`               | empty                       | Cached KV+dedup snapshot for each shard we're shipping (captured at the moment of `NewConfig` execution, before any further AMO state changes) |
| `inboxBuffer`       | `Map<(configNum, shardNum), ShardData>` | empty                       | Shards received from a future config we haven't yet reached                                                                                    |
| `nextSlotToExecute` | int                                     | 1                           | Tracks the next Paxos slot to apply (mirrors Lab 3's `slotOut` but lives at the parent now)                                                    |


> `outgoingPayload` is captured when `NewConfig` is executed, so retries send the same deterministic shard snapshot.

> **Constraints on evolution:**
>
> - `currentConfig` is either null or has a monotonically non-decreasing `configNum`.
> - A shard's status transitions only via the cycle described above; status changes only happen during the execution of a Paxos decision.
> - `targetConfig` is non-null iff a transition is in flight. It is set on `NewConfig` execution and cleared on `ConfigComplete` execution.
> - `nextSlotToExecute` is monotonically non-decreasing; Paxos decisions may arrive out of order at the parent, but the parent buffers them and executes in strict slot order.

> Messages between `ShardStoreServer` and its Paxos sub-node use `handleMessage`, not the network.

---

### State of ShardStoreClient


| Field              | Type                 | Initial Value | Meaning                                                                                             |
| ------------------ | -------------------- | ------------- | --------------------------------------------------------------------------------------------------- |
| `currentConfig`    | `ShardConfig`/null   | null          | Latest config known by this client                                                                  |
| `nextSequenceNum`  | int                  | 1             | Sequence number to attach to the next user command                                                  |
| `pendingCommand`   | `AMOCommand`/null    | null          | The one outstanding command for this client; reused unchanged on retries                            |
| `pendingResult`    | `Result`/null        | null          | Result for `pendingCommand`, set when a matching successful reply arrives                           |
| `pendingShard`     | int                  | unset         | Shard for the pending command's key, cached so retries do not recompute from a different operation  |
| `targetGroupId`    | `Integer`/null       | null          | Group currently believed to own `pendingShard`                                                      |


The client follows the standard DSLabs `Client` interface: it has at most one outstanding command at a time. This means `sendCommand` creates one `AMOCommand`, and every retry sends that exact same `AMOCommand` with the same `(client address, sequenceNum)`.

---

### ShardStoreClient Algorithm

#### `init()`

1. Query the ShardMaster for the latest config by broadcasting `PaxosRequest{Query(-1)}` to all ShardMasters.
2. Do not send client operations until a config is known. If a user command arrives before `currentConfig` is known, keep it pending and retry after future `QueryReply`s.

#### `sendCommand(Command command)`

1. Require that `command` is a single-key KV command, so the client can call `operation.key()`.
2. Create `pendingCommand = AMOCommand{command, address(), nextSequenceNum}`.
3. Set `pendingResult = null` and `pendingShard = keyToShard(command.key())`.
4. If `currentConfig == null`, query the ShardMaster and set `ClientTimer`.
5. Otherwise compute `targetGroupId = ownerOf(currentConfig, pendingShard)`.
6. If `targetGroupId == null`, query the ShardMaster and set `ClientTimer`.
7. Otherwise broadcast `ShardStoreRequest{pendingCommand}` to every member of `targetGroupId`, then set `ClientTimer`.

#### `hasResult()` and `getResult()`

- `hasResult()` returns true iff `pendingResult != null`.
- `getResult()` waits until `pendingResult != null`, returns the user-level result inside the matching `AMOResult`, clears `pendingCommand`, and increments `nextSequenceNum`.
- The client accepts only replies whose `AMOResult.address == address()` and `AMOResult.sequenceNum == pendingCommand.sequenceNum`.

#### `handleShardStoreReply(ShardStoreReply reply, Address sender)`

1. If there is no `pendingCommand`, ignore the reply.
2. If `reply` is successful:
   - Ignore it unless the `AMOResult` matches the pending command's client address and sequence number.
   - Store `pendingResult = reply.result.result`.
   - Notify any thread blocked in `getResult()`.
3. If `reply` is `WrongGroup{config}`:
   - If `config != null` and it is newer than `currentConfig`, install it.
   - Recompute `targetGroupId = ownerOf(currentConfig, pendingShard)` if possible.
   - Retry the same `pendingCommand`; never create a new `AMOCommand` for the same user command.
   - If the reply has no useful config, query the ShardMaster for `Query(-1)`.

#### `handlePaxosReply(PaxosReply reply, Address sender)`

ShardMasters are Paxos-replicated, so a client receives ShardMaster query answers as `PaxosReply`.

1. If `reply.result` is not a `ShardConfig`, ignore it.
2. Install it if it is newer than `currentConfig`.
3. If `pendingCommand != null`, retry the pending command using the new config.

#### `onClientTimer(ClientTimer timer)`

1. If `pendingCommand == null` or `pendingResult != null`, discard the timer.
2. If `currentConfig == null`, query the ShardMaster and re-arm the timer.
3. Otherwise recompute `targetGroupId = ownerOf(currentConfig, pendingShard)`.
4. If a target group exists, broadcast `ShardStoreRequest{pendingCommand}` to all members of that group.
5. If no target group exists, query the ShardMaster.
6. Re-arm the timer with the same `pendingCommand`.

---

### Modifications to Lab 3 `PaxosServer`

1. Add `PaxosServer(Address self, Address[] peers, Address parent)`.
2. In sub-node mode, keep consensus unchanged but forward each chosen command to `parent` as `PaxosDecision`.
3. Add `Propose{Command cmd}` so the parent can submit client and reconfiguration commands.
4. Generalize Paxos log payloads from `AMOCommand` to `Command`, since the log now carries reconfiguration commands too.
5. Keep client AMO deduplication in `ShardStoreServer`, not inside the Paxos sub-node.

---

### Messages

#### Client ↔ ShardStoreServer

**ShardStoreRequest (Client → group members)**

- Contents: `{amoCommand}` where the wrapped operation is a `SingleKeyCommand`.
- At `ShardStoreServer`:
  1. Compute `shard = keyToShard(amoCommand.operation.key())`.
  2. If `currentConfig == null`, `shardStatus[shard] != OWNED`, or `ownerOf(currentConfig, shard) != groupId`: reply `WrongGroup{currentConfig}`. Don't propose.
  3. If `app.alreadyExecuted(amoCommand)`: reply with the cached result from `app.execute(amoCommand)`. Don't propose.
  4. Otherwise send `Propose{amoCommand}` to the local Paxos sub-node.

**ShardStoreReply (ShardStoreServer → Client)**

- Contents: either `{amoResult}` on success, or `WrongGroup{currentConfig}` on rejection.
- At client:
  - Success: deliver result, clear pending state.
  - `WrongGroup{config}`: install `config` if newer, re-broadcast the same `amoCommand` to the indicated owning group. Same `sequenceNum` — the new owner will dedup correctly if it already executed.

#### ShardStoreServer ↔ Paxos sub-node (in-process)

**Propose (parent → sub-node)**

- Contents: `{command}`. Carries `AMOCommand`, `NewConfig`, `ShardReceived`, `ShardSent`, or `ConfigComplete`.
- At sub-node: the existing leader-side proposal path (modified per "Modifications" #3 above). If not currently leader, either forward/broadcast the proposal to Paxos peers or keep it pending and retry. Internal commands such as `NewConfig`, `ShardReceived`, `ShardSent`, and `ConfigComplete` must not rely on a single one-shot proposal reaching the current leader.

**PaxosDecision (sub-node → parent)**

- Contents: `{slot, command}`.
- At parent:
  1. Buffer at `slot` if `slot > nextSlotToExecute`.
  2. While there is a buffered decision at `nextSlotToExecute`, execute it (see "Decision Execution" below) and increment `nextSlotToExecute`.

#### Inter-group handoff

**ShardData (sender group → receiver group)**

- Contents: `{configNum, shardNum, kvs, dedupSlice}`.
- Sent by the old owner after executing `NewConfig`; retried until `ShardSent` is decided.
- At receiver `ShardStoreServer`:
  1. If `targetConfig != null && configNum == targetConfig.configNum && shardNum ∈ pendingIncoming`: propose `ShardReceived{configNum, shardNum, kvs, dedupState}`. Do not ack yet.
  2. If `currentConfig != null && configNum <= currentConfig.configNum`: treat it as stale or duplicate data and ack only if the shard has already been installed.
  3. If `targetConfig == null || configNum > targetConfig.configNum`: buffer it in `inboxBuffer[(configNum, shardNum)]`.
  4. Otherwise, ignore or buffer; never install directly outside Paxos.

**ShardAck (receiver group → sender group)**

- Contents: `{configNum, shardNum}`.
- Sent only after the receiver has decided and installed `ShardReceived`.
- At sender `ShardStoreServer`:
  1. If `targetConfig == null || configNum != targetConfig.configNum || shardNum ∉ pendingOutgoing`: drop.
  2. Otherwise propose `ShardSent{configNum, shardNum}` to local Paxos.

#### ShardStoreServer ↔ ShardMaster

**Query (server → ShardMaster)** and **QueryReply (ShardMaster → server)**

- Contents: `Query{configNum: -1}` or `Query{expectedConfigNum}`, plus `QueryReply{ShardConfig}`.
- At server on QueryReply:
  1. Let `c = reply.config`.
  2. If `currentConfig != null && c.configNum <= currentConfig.configNum`: ignore.
  3. If `targetConfig != null` (already transitioning): ignore (we'll poll again later).
  4. Let `expectedConfigNum = currentConfig == null ? INITIAL_CONFIG_NUM : currentConfig.configNum + 1`.
  5. If `c.configNum == expectedConfigNum`, propose `NewConfig{c}`. If `c.configNum > expectedConfigNum`, query `expectedConfigNum` directly.

---

### Decision Execution

This is the central single-threaded dispatch. Every `PaxosDecision` arriving from the sub-node goes through this dispatch, in strict slot order.

#### `AMOCommand{op, clientId, seq}`

1. Let `shard = keyToShard(op.key())`.
2. If `shardStatus[shard] != OWNED`: send `WrongGroup{currentConfig}` to `clientId`. Do not apply or record dedup state.
3. Otherwise, execute through `app.execute(amoCommand)` and reply with the result.

#### `NewConfig{ShardConfig c}`

1. Let `expectedConfigNum = currentConfig == null ? INITIAL_CONFIG_NUM : currentConfig.configNum + 1`. If `c.configNum != expectedConfigNum`, drop.
2. Set `targetConfig = c`.
3. Compute:
  - `oldShards = currentConfig == null ? emptySet : shardsOf(currentConfig, groupId)`.
  - `newShards = shardsOf(c, groupId)`.
  - `pendingOutgoing = { shard -> ownerOf(c, shard) : shard in oldShards && ownerOf(c, shard) != groupId }`.
  - `pendingIncoming = { shard : shard in newShards && (currentConfig != null && ownerOf(currentConfig, shard) != groupId) }`.
  - If `currentConfig == null`, all shards in `newShards` need no transfer and become `OWNED` immediately.
4. For every shard in `pendingOutgoing`:
  - Snapshot `outgoingPayload[shard] = ShardData{c.configNum, shard, kvsFor(shard), dedupSliceFor(shard)}`.
  - Set `shardStatus[shard] = GIVING_UP`. From this slot onward, any `AMOCommand` decided for this shard fails the `OWNED` check and gets a `WrongGroup` reply.
5. For every shard in `pendingIncoming`:
  - Set `shardStatus[shard] = RECEIVING`.
  - If buffered `ShardData` exists for `(c.configNum, shard)`, propose `ShardReceived`.
6. Send each outgoing `ShardData` to every address in `membersOf(c, destGroupId)` and start a `HandoffTimer`.
7. If `pendingIncoming.isEmpty() && pendingOutgoing.isEmpty()`: propose `ConfigComplete{c.configNum}`.

#### `ShardReceived{configNum, shardNum, kvs, dedupSlice}`

1. Idempotence: if `targetConfig == null || configNum != targetConfig.configNum || shardNum ∉ pendingIncoming`, drop.
2. Install KV: for each `(k, v) ∈ kvs`, write into the underlying KVStore inside `app`.
3. Merge dedup entries by keeping the highest sequence number per client.
4. `shardStatus[shardNum] = OWNED`. Remove from `pendingIncoming`.
5. If `pendingIncoming.isEmpty() && pendingOutgoing.isEmpty()`: `Propose{ConfigComplete{targetConfig.configNum}}`.

#### `ShardSent{configNum, shardNum}`

1. Idempotence: if `targetConfig == null || configNum != targetConfig.configNum || shardNum ∉ pendingOutgoing.keySet()`, drop.
2. Remove the shard's KV pairs from `app` and clear `outgoingPayload[shardNum]`.
3. `shardStatus[shardNum] = NOT_OWNED`. Remove from `pendingOutgoing`. Cancel the `HandoffTimer` (it'll discard itself on next fire).
4. If `pendingIncoming.isEmpty() && pendingOutgoing.isEmpty()`: `Propose{ConfigComplete{targetConfig.configNum}}`.

#### `ConfigComplete{configNum}`

1. Idempotence: if `targetConfig == null || configNum != targetConfig.configNum`, drop.
2. `currentConfig = targetConfig; targetConfig = null`.
3. Drop stale `inboxBuffer` entries with `configNum <= currentConfig.configNum`.
4. The next `ConfigPollTimer` may begin the next reconfiguration.

### Handoff

Handoff moves a shard from `G_old` to `G_new` during a reconfiguration.

- **Inputs.** G_old has executed `NewConfig{N+1}` and added `shard → G_new` to `pendingOutgoing`. G_new may or may not have executed `NewConfig{N+1}` yet.
- **Outputs.** G_old has executed `ShardSent{N+1, shard}` and is in `NOT_OWNED` for the shard. G_new has executed `ShardReceived{N+1, shard, kvs, dedup}` and is in `OWNED`. Both transitions are ordered in their respective Paxos logs.

Important rules:

- Ship AMO dedup state with the shard so retries after handoff do not execute twice.
- Snapshot outgoing data at `NewConfig` execution, then retransmit the same payload.
- A receiver acks only after `ShardReceived` is decided and installed.
- Duplicate `ShardData` and `ShardAck` messages are ignored by checking `pendingIncoming` and `pendingOutgoing`.

---

### Timers

#### ConfigPollTimer (every `ShardStoreServer`)

- Set: on `init()`. Reset unconditionally on every fire.
- On fire:
  1. If `targetConfig != null`: reset and return. (We finish one reconfiguration before starting the next.)
  2. Send `Query{configNum = -1}` to every ShardMaster. The `QueryReply` handler decides whether to `Propose{NewConfig}`.

#### HandoffTimer (per pending outgoing shard, per server)

- Contents: `{configNum, shardNum, destGroupId}`.
- Set: when `NewConfig` execution adds `shardNum` to `pendingOutgoing`.
- On fire:
  1. If `currentConfig != null && currentConfig.configNum >= configNum`: discard. (We finished the transition; `ShardSent` was decided.)
  2. If `shardNum ∉ pendingOutgoing` for `configNum`: discard.
  3. Otherwise, broadcast `outgoingPayload[shardNum]` (which is the cached `ShardData`) to every address in `membersOf(targetConfig, destGroupId)`. Re-arm with the same contents.

#### ClientTimer (`ShardStoreClient`)

- Contents: `{clientId, pendingAmoCommand, targetGroupId}`.
- Set: when the client issues a new command.
- On fire: if `pendingAmoCommand` is unresolved, re-broadcast `ShardStoreRequest{pendingAmoCommand}` to every member of `targetGroupId`. `targetGroupId` may have been updated by a previous `WrongGroup` reply.

---

## Correctness / Liveness Analysis

### Failure Table

| Message | Delayed or Dropped | Reordered or Duplicated |
| --- | --- | --- |
| `ShardStoreRequest` | Client retries with `ClientTimer`. | Server AMO dedup handles duplicates; Paxos orders execution. |
| `ShardStoreReply` | Client retries the same `AMOCommand`. | Client only accepts the reply for its pending sequence number. |
| `Propose` | Parent retries or another replica proposes the same command. | Decision handlers are idempotent. |
| `PaxosDecision` | Local in-process delivery; parent buffers by slot. | Parent executes slots in increasing order. |
| `ShardData` | Sender retries with `HandoffTimer`. | Receiver checks `configNum`, `targetConfig`, and `pendingIncoming`. |
| `ShardAck` | Sender continues retrying `ShardData`. | Sender checks `targetConfig` and `pendingOutgoing`. |
| `Query` / `QueryReply` | Next `ConfigPollTimer` queries again. | Server ignores stale configs and back-fills missing config numbers. |
| Paxos internal messages | Handled by Lab 3 Paxos. | Handled by Lab 3 Paxos. |

### Per-Shard Linearizability

**Claim.** For any shard `s`, all `Get`/`Put`/`Append` operations on keys in `s` execute in a single total order observed by every client.

**Proof sketch.**

1. At most one group has `shardStatus[s] == OWNED`.
2. Within the owning group, Paxos gives one total order for all operations on `s`.
3. Across handoff, the receiver serves `s` only after deciding `ShardReceived`, which includes the sender's shard data and AMO state.

### At-Most-Once Across Handoff

**Claim.** For any `(clientId, sequenceNum)`, the command is applied at most once to the KV state across the entire system.

**Cases.**

- **No handoff occurs.** Standard `AMOApplication` argument: once executed, the dedup entry returns the cached result for retries.
- **Command lands on G_old, then shard moves to G_new.** G_old executes, stores `dedup[C] = (seq, result)`. On `NewConfig` execution, G_old snapshots this entry into `outgoingPayload[shard].dedupSlice`. G_new merges it into its own dedup table on `ShardReceived` execution. Retries arriving at G_new are served from the cache, not re-executed. Retries arriving at G_old after `ShardSent` execution find `shardStatus[shard] == NOT_OWNED` and get `WrongGroup`; the client routes to G_new and is served from the cache.
- **Command reaches a non-owner.** The server replies `WrongGroup`, and the client retries with the same `AMOCommand`.

### Reconfiguration Liveness

- **Every group eventually applies the latest config.** Each group's `ConfigPollTimer` fires periodically; `Query` returns the latest config; the server proposes `NewConfig` (one step at a time, back-filling intermediate configs from the ShardMaster if needed). Each step is a Paxos proposal in a majority-alive group, so it is eventually decided.
- **Handoff terminates.** G_old retransmits `ShardData` until `ShardSent` is decided. Receipt at G_new proposes `ShardReceived`; once decided, ack flows back to G_old; G_old proposes `ShardSent`. Each step needs only a per-group Paxos majority on the respective side, which we assume.
- **Boundary.** If a required group loses its Paxos majority, the affected handoff cannot finish.

### Failure Handling

- Client requests and replies are retried by `ClientTimer`; AMO dedup handles duplicates.
- `ShardData` is retried by `HandoffTimer`; `ShardAck` and `ShardReceived` are idempotent.
- Query polling ignores stale configs and back-fills missing config numbers.
- Paxos handles minority failures within each replica group.

---

## Conclusion

### Goals Achieved

- **Linearizability across the sharded store.** Each shard has a single owner at a time, and each owner orders operations through Paxos.
- **Safe reconfiguration.** Client operations, config changes, shard receive, and shard send events are all logged before execution.
- **At-most-once across handoff.** Shard transfer includes AMO dedup state, so retries after movement do not execute twice.
- **Majority availability per group.** Progress depends on the underlying Paxos group retaining a live majority.

### Limitations

- **Reconfiguration blocks affected shards.** Between `GIVING_UP` on G_old and `OWNED` on G_new, the shard is unavailable.
- **One reconfiguration at a time per group.** Sequential application of configs `N → N+1 → N+2` is necessary for correctness but limits throughput when many configurations queue up.
- **Handoff is broadcast-heavy.** Every old-group replica may send to every new-group replica.
- **No persistence.** A majority-down group cannot make progress.
