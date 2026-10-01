package dslabs.paxos;

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
public final class PaxosClient extends Node implements Client {
  private final Address[] servers;

  // Your code here...
  private AMOCommand pendingCommand;
  private AMOResult result;
  private int sequenceNum = 0;

  /* -----------------------------------------------------------------------------------------------
   *  Construction and Initialization
   * ---------------------------------------------------------------------------------------------*/
  public PaxosClient(Address address, Address[] servers) {
    super(address);
    this.servers = servers;
  }

  @Override
  public synchronized void init() {
    // No need to initialize
  }

  /* -----------------------------------------------------------------------------------------------
   *  Client Methods
   * ---------------------------------------------------------------------------------------------*/
  @Override
  public synchronized void sendCommand(Command operation) {
    // Your code here...
    pendingCommand = new AMOCommand(operation, address(), sequenceNum);
    result = null;
    PaxosRequest request = new PaxosRequest(pendingCommand);
    for (Address server : servers) {
      send(request, server);
    }
    set(new ClientTimer(pendingCommand), ClientTimer.CLIENT_RETRY_MILLIS);
    sequenceNum++;
  }

  @Override
  public synchronized boolean hasResult() {
    // Your code here...
    return result != null;
  }

  @Override
  public synchronized Result getResult() throws InterruptedException {
    // Your code here...
    while (result == null) {
      wait();
    }
    return result.result();
  }

  /* -----------------------------------------------------------------------------------------------
   * Message Handlers
   * ---------------------------------------------------------------------------------------------*/
  private synchronized void handlePaxosReply(PaxosReply m, Address sender) {
    // Your code here...
    AMOResult result = (AMOResult) m.result();
    if (pendingCommand == null) return;
    if (result.sequenceNum() != pendingCommand.sequenceNum()) return;
    this.result = result;
    pendingCommand = null;
    notifyAll();
  }

  /* -----------------------------------------------------------------------------------------------
   *  Timer Handlers
   * ---------------------------------------------------------------------------------------------*/
  private synchronized void onClientTimer(ClientTimer t) {
    // Your code here...
    if (pendingCommand != null
        && result == null
        && t.command().address().equals(pendingCommand.address())
        && t.command().sequenceNum() == pendingCommand.sequenceNum()) {
      PaxosRequest request = new PaxosRequest(pendingCommand);
      for (Address server : servers) {
        send(request, server);
      }
      set(t, ClientTimer.CLIENT_RETRY_MILLIS);
    }
  }
}
