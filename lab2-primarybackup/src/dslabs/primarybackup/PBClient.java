package dslabs.primarybackup;

import dslabs.atmostonce.AMOCommand;
import dslabs.atmostonce.AMOResult;
import dslabs.framework.Address;
import dslabs.framework.Client;
import dslabs.framework.Command;
import dslabs.framework.Node;
import dslabs.framework.Result;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
class PBClient extends Node implements Client {
  private final Address viewServer;

  private View cachedView = new View(ViewServer.STARTUP_VIEWNUM, null, null);

  /**
   * Absent when no RPC is in flight; otherwise the AMO-wrapped command awaiting a {@link Reply}.
   */
  private AMOCommand pendingCommand;

  /** Non-null once a matching {@link Reply} has arrived and before {@link #getResult()} returns. */
  private AMOResult result;

  private int sequenceNum;

  /* -----------------------------------------------------------------------------------------------
   *  Construction and Initialization
   * ---------------------------------------------------------------------------------------------*/

  public PBClient(Address address, Address viewServer) {
    super(address);
    this.viewServer = viewServer;
  }

  @Override
  public synchronized void init() {
    pendingCommand = null;
    result = null;
    sequenceNum = 0;
    cachedView = new View(ViewServer.STARTUP_VIEWNUM, null, null);
    send(new GetView(), viewServer);
    set(new ClientTimer(), ClientTimer.CLIENT_RETRY_MILLIS);
  }

  /* -----------------------------------------------------------------------------------------------
   *  Client Methods
   * ---------------------------------------------------------------------------------------------*/

  @Override
  public synchronized void sendCommand(Command command) {
    pendingCommand = new AMOCommand(command, address(), sequenceNum);
    result = null;

    if (cachedView.primary() == null) {
      // Primary not yet known — fetch view immediately instead of waiting for ClientTimer
      send(new GetView(), viewServer);
    } else {
      send(new Request(pendingCommand), cachedView.primary());
    }
  }

  @Override
  public synchronized boolean hasResult() {
    // Your code here...
    return result != null;
  }

  @Override
  public synchronized Result getResult() throws InterruptedException {
    while (result == null) {
      wait();
    }
    return result.result();
  }

  /* -----------------------------------------------------------------------------------------------
   *  Message Handlers
   * ---------------------------------------------------------------------------------------------*/
  private synchronized void handleReply(Reply m, Address sender) {
    AMOResult r = m.result();
    // Accept only the reply matching the current pending command's sequence number
    if (pendingCommand != null && r.sequenceNum() == pendingCommand.sequenceNum()) {
      result = r;
      sequenceNum++; // advance so the next command gets a fresh sequence number
      pendingCommand = null;
      notifyAll(); // wake up getResult()
    }
    // Stale replies (sequence number mismatch) are silently ignored
  }

  private synchronized void handleViewReply(ViewReply m, Address sender) {
    if (m.view().viewNum() < cachedView.viewNum()) {
      return;
    }
    cachedView = m.view();
    // If a command is waiting and we now have a primary, dispatch it immediately
    if (pendingCommand != null && cachedView.primary() != null) {
      send(new Request(pendingCommand), cachedView.primary());
    }
  }

  /* -----------------------------------------------------------------------------------------------
   *  Timer Handlers
   * ---------------------------------------------------------------------------------------------*/
  private synchronized void onClientTimer(ClientTimer t) {
    if (pendingCommand != null) {
      // Refresh the view (primary may have changed) and retry
      send(new GetView(), viewServer);
      if (cachedView.primary() != null) {
        send(new Request(pendingCommand), cachedView.primary());
      }
    }
    set(t, ClientTimer.CLIENT_RETRY_MILLIS);
  }
}
