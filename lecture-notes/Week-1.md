# Week1 Lecture Note

## Deterministic State Machine

A **deterministic state machine** is a system in which the next state and output are completely determined by the current state and the input.

Formally:

`(next_state, output) = transition(current_state, input)`

Deterministic means that if two machines start in the same state and receive the same sequence of inputs in the same order, they will always produce the same outputs and end in the same final state.

This is important in distributed systems because replication only works if all replicas behave identically. If replicas process the same requests but produce different results, then replication cannot preserve consistency.

---

## Why Replication Helps with Node Failure

Replication is used to improve fault tolerance. If one node fails, another replica can continue serving requests, as long as it has the same state.

The main benefit is that the service does not stop working when a single machine crashes.

However, simply copying data to multiple machines is not enough. Replication requires that replicas remain consistent over time. That means the system must ensure:

- replicas process the same operations
- replicas process them in the same order
- the system can decide which replica should serve clients
- duplicate client requests do not cause duplicate execution

So replication helps with node failure, but only when coordination and consistency are handled correctly.

---

## Primary-Backup Replication

One common replication strategy is **primary-backup replication**.

In this model:

- the **primary** receives and coordinates client requests
- the **backup** maintains a synchronized copy of the state

The usual execution flow is:

1. The client sends a request to the primary.
2. The primary processes the request or prepares the state update.
3. The primary forwards the operation or update to the backup.
4. The backup applies the update and sends an acknowledgment.
5. The primary replies to the client.

This ensures that both replicas stay synchronized.

---

## Main Takeaways

- A deterministic state machine always produces the same result from the same state and input.
- Replication relies on determinism to keep replicas consistent.
- Replication helps tolerate node failures by maintaining multiple copies of service state.
- Primary-backup replication is a common approach in which one node handles clients and another tracks the same state.
