package dslabs.primarybackup;

import dslabs.atmostonce.AMOApplication;
import dslabs.atmostonce.AMOCommand;
import dslabs.atmostonce.AMOResult;
import dslabs.framework.Address;
import dslabs.framework.Application;
import dslabs.framework.Node;
import java.util.LinkedList;
import java.util.Queue;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
class PBServer extends Node {
  private final Address viewServer;

  // Your code here...
  private View currentView;
  private AMOApplication<Application> app;
  private boolean backupInitialized = false;
  private PendingForward pendingForward;
  private int lastTransferViewNum = -1;

  private final Queue<PendingRequest> pendingQueue = new LinkedList<>();

  private static final class PendingForward {
    private final AMOCommand command;
    private final Address sender;
    private final Address backup;
    private final int viewNum;

    public PendingForward(AMOCommand command, Address sender, Address backup, int viewNum) {
      this.command = command;
      this.sender = sender;
      this.backup = backup;
      this.viewNum = viewNum;
    }
  }

  private static final class PendingRequest {
    final Address client;
    final AMOCommand command;

    PendingRequest(Address client, AMOCommand command) {
      this.client = client;
      this.command = command;
    }
  }

  /* -----------------------------------------------------------------------------------------------
   *  Construction and Initialization
   * ---------------------------------------------------------------------------------------------*/
  PBServer(Address address, Address viewServer, Application app) {
    super(address);
    this.viewServer = viewServer;
    // Your code here...
    this.app = new AMOApplication<>(app);
  }

  @Override
  public void init() {
    // Your code here...
    currentView = new View(ViewServer.STARTUP_VIEWNUM, null, null);
    backupInitialized = false;
    pendingForward = null;
    lastTransferViewNum = -1;
    send(new Ping(ViewServer.STARTUP_VIEWNUM), viewServer);
    set(new PingTimer(), PingTimer.PING_MILLIS);
    // #region agent log
    debugLog(
        "PBServer.init",
        "{\"address\":\""
            + address()
            + "\",\"variant\":\"primary-defer+vs-skip-match+readonly-fastpath\"}",
        "HH-readonly-fastpath");
    // #endregion
  }

  // #region agent log
  private static void debugLog(String location, String dataJson, String hypothesisId) {
    try {
      String line =
          "{\"sessionId\":\"69c256\",\"location\":\""
              + location
              + "\",\"hypothesisId\":\""
              + hypothesisId
              + "\",\"timestamp\":"
              + System.currentTimeMillis()
              + ",\"data\":"
              + dataJson
              + "}\n";
      java.nio.file.Files.write(
          java.nio.file.Paths.get("/Users/lawu/dslabs-handout/.cursor/debug-69c256.log"),
          line.getBytes(),
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND);
    } catch (Exception ignored) {
    }
  }

  // #endregion

  /* -----------------------------------------------------------------------------------------------
   *  Message Handlers
   * ---------------------------------------------------------------------------------------------*/

  // ---- From Clients ----
  private void handleRequest(Request m, Address sender) {
    if (!isPrimary()) {
      // Not primary — drop silently; client retries via ClientTimer + GetView
      return;
    }

    AMOCommand cmd = m.command();

    // Fast path: command already fully committed on both replicas; safe to reply immediately
    if (app.alreadyExecuted(cmd)) {
      send(new Reply(app.execute(cmd)), sender);
      return;
    }

    // Read-only fast path: skip the forward+ack roundtrip entirely.
    // Linearizability: every committed write is applied to primary's app before its Reply
    // is sent (handleForwardAck), so reading primary's app reflects all completed writes.
    // Writes still in pendingForward/pendingQueue are not yet "complete" from the client's
    // perspective (no Reply), so a read may legally linearize before them.
    // Note: we bypass AMO bookkeeping (no executedResults entry); idempotent reads tolerate
    // re-execution on retry.
    if (cmd.readOnly()) {
      AMOResult result =
          new AMOResult(app.application().execute(cmd.command()), cmd.address(), cmd.sequenceNum());
      send(new Reply(result), sender);
      return;
    }

    // Dedup: a client retries every CLIENT_RETRY_MILLIS until it gets a Reply.
    // While its command is in pendingForward or pendingQueue, repeatedly enqueueing
    // the same (client, seq) bloats the queue with duplicates that each cost a forward+ack
    // RTT (the backup just returns the cached AMOResult). AMO guarantees one outstanding
    // command per client, so dedup by (address, sequenceNum).
    if (pendingForward != null
        && pendingForward.command.address().equals(cmd.address())
        && pendingForward.command.sequenceNum() == cmd.sequenceNum()) {
      return;
    }
    for (PendingRequest pr : pendingQueue) {
      if (pr.command.address().equals(cmd.address())
          && pr.command.sequenceNum() == cmd.sequenceNum()) {
        return;
      }
    }
    pendingQueue.add(new PendingRequest(sender, cmd));
    drainQueue();
  }

  // ---- From ViewServer ----
  private void handleViewReply(ViewReply m, Address sender) {
    View newView = m.view();

    // Ignore stale or equal-numbered views
    if (newView.viewNum() <= currentView.viewNum()) {
      return;
    }

    currentView = newView;

    if (isPrimary()) {
      if (pendingForward != null) {
        ((LinkedList<PendingRequest>) pendingQueue)
            .addFirst(new PendingRequest(pendingForward.sender, pendingForward.command));
        pendingForward = null;
      }
      Address backup = currentView.backup();
      if (backup == null) {
        // No backup — proceed immediately
        backupInitialized = true;
        lastTransferViewNum = currentView.viewNum();
        drainQueue();
      } else {
        // Always reset and re-initiate transfer when (re-)becoming primary.
        // The backup could be a freshly assigned idle server with empty state,
        // regardless of whether its address matches a previous backup.
        backupInitialized = false;
        send(new StateTransfer(currentView, app), backup);
      }
    } else {
      // Backup or idle — discard any leftover primary state
      if (lastTransferViewNum != currentView.viewNum()) {
        backupInitialized = false;
      }
      pendingForward = null;
      pendingQueue.clear();
    }
  }

  // ---- From Primary ----
  private void handleForwardRequest(ForwardRequest m, Address sender) {
    if (!isBackup()) return;
    if (!sender.equals(currentView.primary())) return;

    // Guard against reordered messages: a ForwardRequest could arrive before StateTransfer
    // under an adversarial network. Drop it — the primary retries after StateTransferAck.
    if (!backupInitialized) return;

    AMOResult result = app.execute(m.command());
    send(new ForwardAck(result), sender);
  }

  // ---- From Backup ----
  private void handleForwardAck(ForwardAck m, Address sender) {
    if (!isPrimary()) return;
    if (pendingForward == null) return;
    if (!sender.equals(pendingForward.backup)) return;
    if (pendingForward.viewNum != currentView.viewNum()) return;
    if (!m.result().address().equals(pendingForward.command.address())
        || m.result().sequenceNum() != pendingForward.command.sequenceNum()) {
      return;
    }

    // Backup has applied the operation. Execute on the primary NOW — deferred until this point
    // so that a primary crash before this line leaves the backup in the correct state.
    AMOResult result = app.execute(pendingForward.command);
    send(new Reply(result), pendingForward.sender);

    pendingForward = null;
    drainQueue();
  }

  // ---- From Primary ----
  private void handleStateTransfer(StateTransfer m, Address sender) {
    View transferView = m.view();
    if (!address().equals(transferView.backup())) return;
    if (!sender.equals(transferView.primary())) return;
    if (transferView.viewNum() < currentView.viewNum()) return;

    // Already installed state for this (or a newer) view via prior transfer.
    // Subsequent forwarded operations are the source of truth; do not regress
    // app state. But always RE-ACK so a primary whose original ack was lost
    // can learn that the backup is initialized and resume forwarding.
    if (transferView.viewNum() <= lastTransferViewNum) {
      send(new StateTransferAck(transferView), sender);
      return;
    }

    if (transferView.viewNum() > currentView.viewNum()) {
      currentView = transferView;
      pendingForward = null;
      pendingQueue.clear();
    }

    app = m.app();
    backupInitialized = true;
    lastTransferViewNum = transferView.viewNum();
    send(new StateTransferAck(transferView), sender);
  }

  // ---- From Backup ----
  private void handleStateTransferAck(StateTransferAck m, Address sender) {
    if (!isPrimary()) return;
    if (!sender.equals(currentView.backup())) return;
    if (m.view().viewNum() != currentView.viewNum()) return;
    if (backupInitialized) return; // duplicate ack — ignore

    backupInitialized = true;
    lastTransferViewNum = currentView.viewNum();
    drainQueue();
  }

  /* -----------------------------------------------------------------------------------------------
   *  Timer Handlers
   * ---------------------------------------------------------------------------------------------*/
  private void onPingTimer(PingTimer t) {
    int pingViewNum = currentView.viewNum();
    if (isPrimary() && currentView.backup() != null && !backupInitialized && pingViewNum > 0) {
      // Defer ack-of-current-view until backup is initialized, so ViewServer can't
      // advance views (and recruit/promote) past an uninitialized backup
      // (linearizability bugs in tests 2.19/2.20).
      pingViewNum = pingViewNum - 1;
    }
    send(new Ping(pingViewNum), viewServer);
    if (isPrimary()) {
      Address backup = currentView.backup();
      if (backup != null) {
        if (!backupInitialized) {
          send(new StateTransfer(currentView, app), backup); // retry
        } else if (pendingForward != null) {
          send(new ForwardRequest(pendingForward.command), backup); // retry
        }
      }
    }
    set(t, PingTimer.PING_MILLIS);
  }

  /* -----------------------------------------------------------------------------------------------
   *  Utils
   * ---------------------------------------------------------------------------------------------*/

  private boolean isPrimary() {
    return address().equals(currentView.primary());
  }

  private boolean isBackup() {
    return address().equals(currentView.backup());
  }

  private void drainQueue() {
    if (!isPrimary()) return;
    if (pendingForward != null) return;
    if (pendingQueue.isEmpty()) return;

    Address backup = currentView.backup();
    if (backup != null && !backupInitialized) {
      return;
    }

    PendingRequest next = pendingQueue.poll();

    if (backup == null) {
      AMOResult result = app.execute(next.command);
      send(new Reply(result), next.client);
      drainQueue();
    } else {
      pendingForward = new PendingForward(next.command, next.client, backup, currentView.viewNum());
      send(new ForwardRequest(next.command), backup);
    }
  }
}
