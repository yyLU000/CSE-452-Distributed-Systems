package dslabs.paxos;

import dslabs.atmostonce.AMOApplication;
import dslabs.atmostonce.AMOCommand;
import dslabs.atmostonce.AMOResult;
import dslabs.framework.Address;
import dslabs.framework.Application;
import dslabs.framework.Command;
import dslabs.framework.Node;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class PaxosServer extends Node {
  /** All servers in the Paxos group, including this one. */
  private final Address[] servers;

  // Your code here...
  private final AMOApplication<Application> app;
  private final Address parentAddress;
  private final Map<Integer, LogEntry> log = new HashMap<>();
  private int slotIn = 1;
  private int slotOut = 1;
  private Ballot myBallot;
  private Ballot promisedBallot;

  private LeaderState leaderState = LeaderState.FOLLOWER;

  private static enum LeaderState {
    FOLLOWER,
    ACTIVE,
    CANDIDATE
  }

  private final Map<Address, Map<Integer, LogEntry>> p1bResponses = new HashMap<>();
  private final Map<Integer, Set<Address>> p2bResponses = new HashMap<>();
  private final Set<Command> proposedCommands = new HashSet<>();

  private int missedHeartbeats = 0;
  private final Map<Address, Integer> executedSlots = new HashMap<>();
  private int garbageBoundary = 0;

  private static final Command NO_OP = PaxosNoOp.INSTANCE;

  private static boolean isNoOp(Command c) {
    return c == NO_OP || NO_OP.equals(c);
  }

  /* -----------------------------------------------------------------------------------------------
   *  Construction and Initialization
   * ---------------------------------------------------------------------------------------------*/
  public PaxosServer(Address address, Address[] servers, Application app) {
    super(address);
    this.servers = servers;

    // Your code here...
    this.app = new AMOApplication<>(app);
    this.parentAddress = null;
    this.myBallot = new Ballot(0, address());
    this.promisedBallot = new Ballot(0, address());
    for (Address server : servers) {
      executedSlots.put(server, 0);
    }
  }

  public PaxosServer(Address address, Address[] servers, Address parentAddress) {
    super(address);
    this.servers = servers;

    // Your code here...
    this.app = null;
    this.parentAddress = parentAddress;
    this.myBallot = new Ballot(0, address());
    this.promisedBallot = new Ballot(0, address());
    for (Address server : servers) {
      executedSlots.put(server, 0);
    }
  }

  @Override
  public void init() {
    // Your code here...
    set(new HeartbeatCheckTimer(), HeartbeatCheckTimer.HEARTBEAT_CHECK_MILLIS);
    startElection();
  }

  private void startElection() {
    if (leaderState != LeaderState.FOLLOWER) return;

    leaderState = LeaderState.CANDIDATE;
    myBallot = new Ballot(Math.max(myBallot.seqNum(), promisedBallot.seqNum()) + 1, address());
    promisedBallot = myBallot;
    missedHeartbeats = 0;

    p1bResponses.clear();
    p1bResponses.put(address(), snapshotLocalLog());

    // Singleton case closes here.
    checkP1BQuorum();
    if (leaderState != LeaderState.CANDIDATE) return;

    P1AMessage message = new P1AMessage(myBallot);
    for (Address server : servers) {
      if (!server.equals(address())) send(message, server);
    }
    set(new LeaderElectionTimer(myBallot), LeaderElectionTimer.LEADER_ELECTION_MILLIS);
  }

  /* -----------------------------------------------------------------------------------------------
   *  Interface Methods
   *
   *  Be sure to implement the following methods correctly. The test code uses them to check
   *  correctness more efficiently.
   * ---------------------------------------------------------------------------------------------*/

  /**
   * Return the status of a given slot in the server's local log.
   *
   * <p>If this server has garbage-collected this slot, it should return {@link
   * PaxosLogSlotStatus#CLEARED} even if it has previously accepted or chosen command for this slot.
   * If this server has both accepted and chosen a command for this slot, it should return {@link
   * PaxosLogSlotStatus#CHOSEN}.
   *
   * <p>Log slots are numbered starting with 1.
   *
   * @param logSlotNum the index of the log slot
   * @return the slot's status
   * @see PaxosLogSlotStatus
   */
  public PaxosLogSlotStatus status(int logSlotNum) {
    // Your code here...
    if (logSlotNum <= garbageBoundary) return PaxosLogSlotStatus.CLEARED;
    LogEntry entry = log.get(logSlotNum);
    if (entry == null) return PaxosLogSlotStatus.EMPTY;
    return entry.status();
  }

  /**
   * Return the command associated with a given slot in the server's local log.
   *
   * <p>If the slot has status {@link PaxosLogSlotStatus#CLEARED} or {@link
   * PaxosLogSlotStatus#EMPTY}, this method should return {@code null}. Otherwise, return the
   * command this server has chosen or accepted, according to {@link PaxosServer#status}.
   *
   * <p>If clients wrapped commands in {@link dslabs.atmostonce.AMOCommand}, this method should
   * unwrap them before returning.
   *
   * <p>Log slots are numbered starting with 1.
   *
   * @param logSlotNum the index of the log slot
   * @return the slot's contents or {@code null}
   * @see PaxosLogSlotStatus
   */
  public Command command(int logSlotNum) {
    // Your code here...
    PaxosLogSlotStatus status = status(logSlotNum);
    if (status == PaxosLogSlotStatus.EMPTY || status == PaxosLogSlotStatus.CLEARED) return null;
    LogEntry entry = log.get(logSlotNum);
    Command command = entry.command();
    if (command instanceof AMOCommand) {
      return ((AMOCommand) command).command();
    }
    return command;
  }

  /**
   * Return the index of the first non-cleared slot in the server's local log. The first non-cleared
   * slot is the first slot which has not yet been garbage-collected. By default, the first
   * non-cleared slot is 1.
   *
   * <p>Log slots are numbered starting with 1.
   *
   * @return the index in the log
   * @see PaxosLogSlotStatus
   */
  public int firstNonCleared() {
    // Your code here...
    return garbageBoundary + 1;
  }

  /**
   * Return the index of the last non-empty slot in the server's local log, according to the defined
   * states in {@link PaxosLogSlotStatus}. If there are no non-empty slots in the log, this method
   * should return 0.
   *
   * <p>Log slots are numbered starting with 1.
   *
   * @return the index in the log
   * @see PaxosLogSlotStatus
   */
  public int lastNonEmpty() {
    // Cleared slots are removed from the log map; garbageBoundary marks the prefix end.
    int max = garbageBoundary;
    for (Map.Entry<Integer, LogEntry> e : log.entrySet()) {
      int slot = e.getKey();
      if (slot <= garbageBoundary) {
        continue;
      }
      PaxosLogSlotStatus s = e.getValue().status();
      if (s == PaxosLogSlotStatus.ACCEPTED || s == PaxosLogSlotStatus.CHOSEN) {
        max = Math.max(max, slot);
      }
    }
    return max;
  }

  /* -----------------------------------------------------------------------------------------------
   *  Message Handlers
   * ---------------------------------------------------------------------------------------------*/
  private void handlePaxosRequest(PaxosRequest m, Address sender) {
    // Your code here...
    if (parentAddress == null && !(m.command() instanceof AMOCommand) && m.command().readOnly()) {
      if (leaderState == LeaderState.ACTIVE) {
        send(new PaxosReply(m.command(), app.executeReadOnly(m.command())), sender);
      }
      return;
    }
    proposeCommand(m.command());
  }

  private void handlePropose(Propose m, Address sender) {
    // Your code here...
    proposeCommand(m.command());
  }

  private void proposeCommand(Command command) {
    if (leaderState != LeaderState.ACTIVE) return;

    if (isNoOp(command)) return;

    // already executed
    if (parentAddress == null
        && command instanceof AMOCommand
        && app.alreadyExecuted((AMOCommand) command)) {
      AMOCommand amoCommand = (AMOCommand) command;
      AMOResult cached = app.execute(amoCommand);
      send(new PaxosReply(amoCommand, cached), amoCommand.address());
      return;
    }
    // already in the log
    if (proposedCommands.contains(command)) return;

    int slot = slotIn++;
    log.put(slot, new LogEntry(PaxosLogSlotStatus.ACCEPTED, command, myBallot));
    proposedCommands.add(command);

    Set<Address> votes = new HashSet<>();
    votes.add(address());
    p2bResponses.put(slot, votes);

    P2AMessage message = new P2AMessage(myBallot, slot, command);
    for (Address server : servers) {
      if (!server.equals(address())) send(message, server);
    }
    set(new AcceptTimer(slot, myBallot), AcceptTimer.ACCEPT_RETRY_MILLIS);

    checkP2BQuorum(slot);
  }

  // Your code here...
  private void handleP1AMessage(P1AMessage m, Address sender) {
    Ballot ballot = m.ballot();
    if (ballot.compareTo(promisedBallot) < 0) return;
    promisedBallot = ballot;
    if (leaderState != LeaderState.FOLLOWER) {
      leaderState = LeaderState.FOLLOWER;
      p1bResponses.clear();
    }
    missedHeartbeats = 0;
    send(new P1BMessage(ballot, snapshotLocalLog()), sender);
  }

  private void handleP1BMessage(P1BMessage m, Address sender) {
    if (leaderState != LeaderState.CANDIDATE) return;
    if (!m.ballot().equals(myBallot)) return;
    p1bResponses.put(sender, m.logEntries());
    checkP1BQuorum();
  }

  private void handleP2AMessage(P2AMessage m, Address sender) {
    Ballot ballot = m.ballot();
    int slot = m.slot();
    Command command = m.command();
    if (ballot.compareTo(promisedBallot) < 0) return;
    promisedBallot = ballot;
    missedHeartbeats = 0;

    if (leaderState != LeaderState.FOLLOWER && ballot.compareTo(myBallot) > 0) {
      leaderState = LeaderState.FOLLOWER;
      p1bResponses.clear();
    }

    LogEntry existing = log.get(slot);
    if (existing != null && existing.status() == PaxosLogSlotStatus.CHOSEN) {
      send(new P2BMessage(ballot, slot), sender);
      return;
    }

    if (existing != null && existing.status() == PaxosLogSlotStatus.CLEARED) {
      send(new P2BMessage(ballot, slot), sender);
      return;
    }

    log.put(slot, new LogEntry(PaxosLogSlotStatus.ACCEPTED, command, ballot));
    if (slot >= slotIn) {
      slotIn = slot + 1;
    }
    send(new P2BMessage(ballot, slot), sender);
  }

  private void handleP2BMessage(P2BMessage m, Address sender) {
    if (leaderState != LeaderState.ACTIVE) return;
    if (!m.ballot().equals(myBallot)) return;

    int slot = m.slot();
    LogEntry entry = log.get(slot);
    if (entry == null || entry.status() != PaxosLogSlotStatus.ACCEPTED) return;

    Set<Address> votes = p2bResponses.get(slot);
    if (votes == null) {
      votes = new HashSet<>();
      votes.add(address());
      p2bResponses.put(slot, votes);
    }
    votes.add(sender);

    checkP2BQuorum(m.slot());
  }

  private void handleDecisionMessage(DecisionMessage m, Address sender) {
    int slot = m.slot();
    Command command = m.command();

    if (slot <= garbageBoundary) return;
    LogEntry existing = log.get(slot);
    if (existing != null && existing.status() == PaxosLogSlotStatus.CHOSEN) return;

    log.put(slot, new LogEntry(PaxosLogSlotStatus.CHOSEN, command, null));
    if (slot >= slotIn) slotIn = slot + 1;
    proposedCommands.add(command);
    advanceSlotOut();
  }

  private void handleHeartbeatMessage(HeartbeatMessage m, Address sender) {
    Ballot ballot = m.ballot();
    if (ballot.compareTo(promisedBallot) < 0) return;
    if (ballot.compareTo(promisedBallot) > 0) {
      promisedBallot = ballot;
    }
    if (leaderState != LeaderState.FOLLOWER) {
      leaderState = LeaderState.FOLLOWER;
      p1bResponses.clear();
    }
    missedHeartbeats = 0;
    int safeBoundary = Math.min(m.garbageBoundary(), slotOut - 1);
    garbageCollectThrough(safeBoundary);
    send(new HeartbeatReply(ballot, slotOut), sender);
  }

  private void handleHeartbeatReply(HeartbeatReply m, Address sender) {
    if (leaderState != LeaderState.ACTIVE) return;
    if (!m.ballot().equals(myBallot)) return;

    int reported = m.slotOut();
    Integer existing = executedSlots.get(sender);
    if (existing != null && reported <= existing) {
      return;
    }

    executedSlots.put(sender, reported);
    recomputeGarbageBoundary();

    if (reported < slotOut) {
      int catchUpFrom = Math.max(reported, garbageBoundary + 1);
      for (int slot = catchUpFrom; slot < slotOut; slot++) {
        LogEntry entry = log.get(slot);
        if (entry != null && entry.status() == PaxosLogSlotStatus.CHOSEN) {
          send(new DecisionMessage(slot, entry.command()), sender);
        }
      }
    }
  }

  /* -----------------------------------------------------------------------------------------------
   *  Timer Handlers
   * ---------------------------------------------------------------------------------------------*/
  // Your code here...
  private void onHeartbeatTimer(HeartbeatTimer t) {
    if (leaderState != LeaderState.ACTIVE) return;
    sendHeartbeat();
    set(new HeartbeatTimer(), HeartbeatTimer.HEARTBEAT_MILLIS);
  }

  private void onHeartbeatCheckTimer(HeartbeatCheckTimer t) {
    if (leaderState == LeaderState.ACTIVE) {
      set(new HeartbeatCheckTimer(), HeartbeatCheckTimer.HEARTBEAT_CHECK_MILLIS);
      return;
    }
    missedHeartbeats++;
    if (missedHeartbeats >= 2) startElection();
    set(new HeartbeatCheckTimer(), HeartbeatCheckTimer.HEARTBEAT_CHECK_MILLIS);
  }

  private void onLeaderElectionTimer(LeaderElectionTimer t) {
    if (leaderState != LeaderState.CANDIDATE || !t.ballot().equals(myBallot)) return;
    P1AMessage message = new P1AMessage(myBallot);
    for (Address server : servers) {
      if (server.equals(address())) continue;
      if (p1bResponses.containsKey(server)) continue;
      send(message, server);
    }
    set(new LeaderElectionTimer(myBallot), LeaderElectionTimer.LEADER_ELECTION_MILLIS);
  }

  private void onAcceptTimer(AcceptTimer t) {
    if (leaderState != LeaderState.ACTIVE) return;
    if (!t.ballot().equals(myBallot)) return;
    int slot = t.slot();
    LogEntry entry = log.get(slot);
    if (entry == null || entry.status() != PaxosLogSlotStatus.ACCEPTED) return;
    Set<Address> votes = p2bResponses.get(slot);
    P2AMessage message = new P2AMessage(myBallot, slot, entry.command());
    for (Address server : servers) {
      if (server.equals(address())) continue;
      if (votes != null && votes.contains(server)) continue;
      send(message, server);
    }
    set(new AcceptTimer(slot, myBallot), AcceptTimer.ACCEPT_RETRY_MILLIS);
  }

  /* -----------------------------------------------------------------------------------------------
   *  Utils
   * ---------------------------------------------------------------------------------------------*/
  private Map<Integer, LogEntry> snapshotLocalLog() {
    Map<Integer, LogEntry> snapshot = new HashMap<>();
    for (Map.Entry<Integer, LogEntry> entry : log.entrySet()) {
      PaxosLogSlotStatus status = entry.getValue().status();
      if (status == PaxosLogSlotStatus.ACCEPTED || status == PaxosLogSlotStatus.CHOSEN) {
        snapshot.put(entry.getKey(), entry.getValue());
      }
    }
    return snapshot;
  }

  private void checkP1BQuorum() {
    if (leaderState != LeaderState.CANDIDATE) return;
    if (p1bResponses.size() < servers.length / 2 + 1) return;

    mergeP1BLogs(); // build merged log per the merge rules
    becomeActiveLeader(); // transition + send first heartbeat + arm HeartbeatTimer
  }

  private void checkP2BQuorum(int slot) {
    if (leaderState != LeaderState.ACTIVE) return;

    LogEntry entry = log.get(slot);
    if (entry == null || entry.status() != PaxosLogSlotStatus.ACCEPTED) return;

    Set<Address> votes = p2bResponses.get(slot);
    if (votes == null || votes.size() < servers.length / 2 + 1) return;

    // Promote to CHOSEN and broadcast the decision.
    log.put(slot, new LogEntry(PaxosLogSlotStatus.CHOSEN, entry.command(), null));
    proposedCommands.remove(entry.command()); // added
    broadcastDecision(slot, entry.command());

    // Execute any newly-stable prefix of CHOSEN slots.
    advanceSlotOut();
  }

  private void advanceSlotOut() {
    while (true) {
      LogEntry entry = log.get(slotOut);
      if (entry == null || entry.status() != PaxosLogSlotStatus.CHOSEN) break;
      Command command = entry.command();
      if (!isNoOp(command)) {
        if (parentAddress == null) {
          AMOCommand amoCommand = (AMOCommand) command;
          AMOResult result = app.execute(amoCommand);
          send(new PaxosReply(amoCommand, result), amoCommand.address());
        } else {
          handleMessage(new PaxosDecision(slotOut, command), parentAddress);
        }
      }
      slotOut++;
    }
    executedSlots.put(address(), slotOut);
    if (leaderState == LeaderState.ACTIVE) {
      recomputeGarbageBoundary();
    }
  }

  private void recomputeGarbageBoundary() {
    int minExecuted = Integer.MAX_VALUE;
    for (int s : executedSlots.values()) minExecuted = Math.min(minExecuted, s);
    garbageCollectThrough(minExecuted - 1);
  }

  /** Drop slots ≤ newBoundary from the log so GC actually frees memory. */
  private void garbageCollectThrough(int newBoundary) {
    if (newBoundary <= garbageBoundary) {
      return;
    }
    for (int slot = garbageBoundary + 1; slot <= newBoundary; slot++) {
      LogEntry entry = log.remove(slot);
      if (entry != null && entry.command() != null) {
        proposedCommands.remove(entry.command());
      }
      p2bResponses.remove(slot);
    }
    garbageBoundary = newBoundary;
  }

  private void mergeP1BLogs() {
    // ---- Pass 1: for each slot, find the best entry across all P1Bs. ----
    // "Best" = CHOSEN beats anything, otherwise highest acceptedAt ballot.
    Map<Integer, LogEntry> merged = new HashMap<>();

    for (Map<Integer, LogEntry> response : p1bResponses.values()) {
      for (Map.Entry<Integer, LogEntry> e : response.entrySet()) {
        int slot = e.getKey();
        LogEntry incoming = e.getValue();

        // Skip slots already GC'd locally.
        if (slot < slotOut) continue;

        LogEntry current = merged.get(slot);
        if (current == null || beats(incoming, current)) {
          merged.put(slot, incoming);
        }
      }
    }

    // Also fold in any CHOSEN entries already in our live log that weren't
    // reported in the P1Bs (shouldn't happen since we included self, but
    // belt-and-suspenders).
    for (Map.Entry<Integer, LogEntry> e : log.entrySet()) {
      int slot = e.getKey();
      if (slot < slotOut) continue;
      if (e.getValue().status() != PaxosLogSlotStatus.CHOSEN) continue;
      LogEntry current = merged.get(slot);
      if (current == null || current.status() != PaxosLogSlotStatus.CHOSEN) {
        merged.put(slot, e.getValue());
      }
    }

    if (merged.isEmpty()) {
      // No previous proposals to inherit. Just set slotIn = slotOut and return.
      slotIn = slotOut;
      return;
    }

    int maxSlot = 0;
    for (int s : merged.keySet()) maxSlot = Math.max(maxSlot, s);

    // ---- Pass 2: fill holes with NO_OPs. ----
    // Every slot in [slotOut, maxSlot] that isn't in merged becomes a NO_OP
    // proposed under myBallot. We mark them ACCEPTED so phase 2 will confirm.
    for (int slot = slotOut; slot <= maxSlot; slot++) {
      if (!merged.containsKey(slot)) {
        merged.put(slot, new LogEntry(PaxosLogSlotStatus.ACCEPTED, NO_OP, myBallot));
      }
    }

    // ---- Pass 3: install the merged entries into the live log. ----
    // For ACCEPTED entries, take ownership by re-stamping the ballot to myBallot.
    // For CHOSEN entries, copy them through unchanged.
    proposedCommands.clear();
    for (Map.Entry<Integer, LogEntry> e : merged.entrySet()) {
      int slot = e.getKey();
      LogEntry entry = e.getValue();

      if (entry.status() == PaxosLogSlotStatus.CHOSEN) {
        log.put(slot, entry);
        // CHOSEN commands don't go in proposedCommands — they're past the
        // proposal stage.
      } else {
        // ACCEPTED (either re-stamped from a prior leader, or a fresh NO_OP).
        log.put(slot, new LogEntry(PaxosLogSlotStatus.ACCEPTED, entry.command(), myBallot));
        proposedCommands.add(entry.command());
        // Reset per-slot accept tracking and seed self-acceptance.
        Set<Address> votes = new HashSet<>();
        votes.add(address());
        p2bResponses.put(slot, votes);
      }
    }

    slotIn = maxSlot + 1;
  }

  /** Comparator: does the incoming entry beat the current one in the merge? */
  private static boolean beats(LogEntry incoming, LogEntry current) {
    // CHOSEN beats anything not CHOSEN.
    boolean iChosen = incoming.status() == PaxosLogSlotStatus.CHOSEN;
    boolean cChosen = current.status() == PaxosLogSlotStatus.CHOSEN;
    if (iChosen && !cChosen) return true;
    if (cChosen && !iChosen) return false;
    if (iChosen && cChosen) return false; // both CHOSEN; first wins (commands equal by invariant)

    // Both ACCEPTED: higher acceptedAt ballot wins.
    return incoming.acceptedAt().compareTo(current.acceptedAt()) > 0;
  }

  /**
   * Transition from CANDIDATE to ACTIVE leader after winning phase 1. Sends an initial Heartbeat
   * (so followers reset missedHeartbeats), arms the HeartbeatTimer, broadcasts Decisions for any
   * CHOSEN slots, and broadcasts P2As for every ACCEPTED slot under our new ballot.
   *
   * <p>Precondition: mergeP1BLogs() has already run; the live log reflects the merge.
   */
  private void becomeActiveLeader() {
    leaderState = LeaderState.ACTIVE;
    p1bResponses.clear();

    // Tell everyone we're alive immediately, so followers don't time out
    // during the post-election broadcast burst.
    sendHeartbeat();
    set(new HeartbeatTimer(), HeartbeatTimer.HEARTBEAT_MILLIS);

    // Re-propose every ACCEPTED slot under myBallot, and re-broadcast every
    // CHOSEN slot as a Decision (helps lagging followers catch up).
    for (Map.Entry<Integer, LogEntry> e : log.entrySet()) {
      int slot = e.getKey();
      LogEntry entry = e.getValue();

      switch (entry.status()) {
        case ACCEPTED:
          broadcastP2A(slot, entry.command());
          set(new AcceptTimer(slot, myBallot), AcceptTimer.ACCEPT_RETRY_MILLIS);
          break;
        case CHOSEN:
          broadcastDecision(slot, entry.command());
          break;
        case EMPTY:
        case CLEARED:
          // Nothing to do.
          break;
      }
    }
    advanceSlotOut();
  }

  private void broadcastP2A(int slot, Command command) {
    P2AMessage message = new P2AMessage(myBallot, slot, command);
    for (Address s : servers) {
      if (!s.equals(address())) send(message, s);
    }
  }

  private void broadcastDecision(int slot, Command command) {
    DecisionMessage message = new DecisionMessage(slot, command);
    for (Address s : servers) {
      if (!s.equals(address())) send(message, s);
    }
  }

  private void sendHeartbeat() {
    HeartbeatMessage message = new HeartbeatMessage(myBallot, garbageBoundary);
    for (Address s : servers) {
      if (!s.equals(address())) send(message, s);
    }
  }
}
