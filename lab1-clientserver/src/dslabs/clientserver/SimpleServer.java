package dslabs.clientserver;

import dslabs.atmostonce.AMOApplication;
import dslabs.atmostonce.AMOResult;
import dslabs.framework.Address;
import dslabs.framework.Application;
import dslabs.framework.Node;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * Simple server that receives requests and returns responses.
 *
 * <p>See the documentation of {@link Node} for important implementation notes.
 */
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
class SimpleServer extends Node {
  // Your code here...
  private AMOApplication<Application> app;

  // private Map<Integer, Command> commands = new HashMap<>();
  // private Map<Integer, Result> results = new HashMap<>();

  /* -----------------------------------------------------------------------------------------------
   *  Construction and Initialization
   * ---------------------------------------------------------------------------------------------*/
  public SimpleServer(Address address, Application app) {
    super(address);
    // Your code here...
    this.app = new AMOApplication<>(app);
    // commands.clear();
    // results.clear();
  }

  @Override
  public void init() {
    // No initialization necessary
  }

  /* -----------------------------------------------------------------------------------------------
   *  Message Handlers
   * ---------------------------------------------------------------------------------------------*/
  private void handleRequest(Request m, Address sender) {
    // Your code here...
    AMOResult result = app.execute(m.command());
    send(new Reply(result), sender);
  }
}
