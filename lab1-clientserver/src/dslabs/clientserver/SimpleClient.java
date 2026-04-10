package dslabs.clientserver;

import dslabs.atmostonce.AMOCommand;
import dslabs.framework.Address;
import dslabs.framework.Client;
import dslabs.framework.Command;
import dslabs.framework.Node;
import dslabs.framework.Result;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * Simple client that sends requests to a single server and returns responses.
 *
 * <p>See the documentation of {@link Client} and {@link Node} for important implementation notes.
 */
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
class SimpleClient extends Node implements Client {
  private final Address serverAddress;

  // Your code here...
  private Request request;
  private Reply reply;
  private int sequenceNum = 0;

  /* -----------------------------------------------------------------------------------------------
   *  Construction and Initialization
   * ---------------------------------------------------------------------------------------------*/
  public SimpleClient(Address address, Address serverAddress) {
    super(address);
    this.serverAddress = serverAddress;
  }

  @Override
  public synchronized void init() {
    // No initialization necessary
  }

  /* -----------------------------------------------------------------------------------------------
   *  Client Methods
   * ---------------------------------------------------------------------------------------------*/
  @Override
  public synchronized void sendCommand(Command command) {
    // Your code here...
    request = new Request(new AMOCommand(command, address(), sequenceNum));
    reply = null;
    send(request, serverAddress);
    set(new ClientTimer(request), ClientTimer.CLIENT_RETRY_MILLIS);
    sequenceNum++;
  }

  @Override
  public synchronized boolean hasResult() {
    // Your code here...
    return reply != null;
  }

  @Override
  public synchronized Result getResult() throws InterruptedException {
    // Your code here...
    while (reply == null) {
      wait();
    }
    return reply.result().result();
  }

  /* -----------------------------------------------------------------------------------------------
   *  Message Handlers
   * ---------------------------------------------------------------------------------------------*/
  private synchronized void handleReply(Reply m, Address sender) {
    // Your code here...
    if (request != null
        && m.result().address().equals(address())
        && m.result().sequenceNum() == request.command().sequenceNum()) {
      reply = m;
      notify();
    }
  }

  /* -----------------------------------------------------------------------------------------------
   *  Timer Handlers
   * ---------------------------------------------------------------------------------------------*/
  private synchronized void onClientTimer(ClientTimer t) {
    // Your code here...
    if (request != null
        && t.request().command().sequenceNum() == request.command().sequenceNum()
        && reply == null) {
      send(request, serverAddress);
      set(t, ClientTimer.CLIENT_RETRY_MILLIS);
    }
  }
}
