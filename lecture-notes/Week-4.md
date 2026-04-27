# Week 4 Lecture Notes
**Keywords: Consensus, Paxos, Log-based SMR**

---

## 1. Consensus Problem

**Goal:**  
A set of distributed nodes must **agree on a single value**, despite failures and unreliable networks.

- Nodes may **propose different values**
- The system must **choose exactly one**

### Core Safety Requirements

1. **Validity**  
   The chosen value must have been **proposed by some node**

2. **Agreement (Safety)**  
   **At most one value** is chosen

3. **Accurate Learning**  
   No node believes a value was chosen unless it **actually was chosen**

> Key distinction:
> - **chosen** = accepted by a majority (quorum)
> - **learned** = a node discovers the chosen value

---

## 2. Paxos Overview

**Paxos** is a protocol that solves the consensus problem under:
- message delay, loss, duplication, reordering
- node crashes

### Roles

- **Proposer**: suggests values
- **Acceptor**: votes (accepts proposals)
- **Learner**: learns the chosen value

---

## 3. Single-Decree Paxos (SDP)

**Definition:**  
Paxos that decides **one value (one slot)**.

### Two Phases

#### Phase 1: Prepare / Promise
- Proposer sends `Prepare(n)`
- Acceptors:
  - promise not to accept proposals `< n`
  - return highest accepted proposal (if any)

#### Phase 2: Accept / Accepted
- Proposer sends `Accept(n, v)`
  - may need to reuse an earlier value
- Acceptors accept if no higher promise exists
- If **majority accepts**, value is **chosen**

---

## 4. Key Concepts

### Value
- The **data being agreed on**
- Example: a command like `put(x,5)`

### Vote
- Informal term for **acceptor accepting (ballot, value)**
- A vote is really:
  **acceptor accepts a proposal**

### Chosen Condition
A value is **chosen** when:
> it is accepted by a **majority of acceptors**

---

## 5. Ballots (Rounds)

A **ballot** = one attempt to decide a value

Each ballot includes:
- **ballot number**
- **proposed value**

### Properties of Ballot Numbers

- **unique**
- **totally ordered**
- **monotonically increasing**

### Example Allocation

- proposer A: 0, 2, 4, ...
- proposer B: 1, 3, 5, ...

Or:
- `(counter, proposerID)`

---

## 6. Why Multiple Ballots?

Multiple ballots may occur because:
- multiple proposers compete
- failures interrupt progress
- messages are delayed

**Important:**
- Ballot changes, but **slot stays the same**
- Value may be **overwritten to preserve safety**

---

## 7. From Consensus → Replicated Systems

### Log-Based State Machine Replication (SMR)

Core idea:

> **Replicate commands (log), not state**

Each replica:
1. maintains a **log of commands**
2. executes commands **in order**
3. reaches the same state

### Requirement

**Determinism**:
- same input log → same output state

---

## 8. Multi-Paxos (Unoptimized)

Goal:
Extend Paxos to decide **multiple values (log slots)**

### Approach

- Run **Single-Decree Paxos per slot**

#### Workflow

1. Client sends request
2. Any server can act as proposer
3. Server:
   - finds an unchosen slot
   - proposes request
4. Wait until a **prefix of log is chosen**
5. Execute in order
6. Reply to client

### Key Constraint

Execution requires:
> **contiguous prefix of chosen slots**

---

## 9. Problems with Unoptimized Multi-Paxos

- Repeats Phase 1 for every slot
- Multiple competing proposers
- High message overhead
- Poor performance

---

## 10. Distinguished Proposer Optimization (Leader)

### Idea

Use **one stable leader** to avoid repeated Phase 1

### Step 1: Leader Election via Phase 1

- Proposer runs Phase 1
- Gains majority promises
- Becomes **leader (distinguished proposer)**

### Step 2: Reuse Phase 1 Across Slots

- Conceptually **remove slot from Phase 1**
- Leader keeps same ballot
- For each slot:
  → go directly to **Phase 2 (Accept)**

### Effect

- reduces latency
- reduces message complexity
- avoids contention

---

## 11. Heartbeats (Liveness Mechanism)

### Purpose

Detect leader failure and maintain stability

### Mechanism

- Leader sends periodic **heartbeats**
- Followers maintain a **timeout**

#### Behavior

- if heartbeat received → reset timer
- if timeout expires → start new election (Phase 1)

---

## 12. Overall Flow (Optimized Multi-Paxos)

1. Leader is established
2. Client sends request to leader
3. Leader assigns log slot
4. Leader sends `Accept` (Phase 2 only)
5. Majority accepts → value chosen
6. Replicas execute in order
7. Leader replies to client

---

## 13. Key Takeaways

- **Consensus = agree on one value**
- **Paxos ensures safety via ballots + majority intersection**
- **Single-Decree Paxos = one decision**
- **Multi-Paxos = sequence of decisions (log)**
- **SMR = execute agreed log to replicate state**
- **Leader optimization = performance improvement**
- **Heartbeats = liveness + failure detection**

---

## 14. Mental Model (Most Important)

**Consensus → Log → State Machine**

- Paxos ensures **agreement**
- Multi-Paxos builds a **log**
- SMR executes the log to maintain **replicated state**
