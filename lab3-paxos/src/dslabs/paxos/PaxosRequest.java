package dslabs.paxos;

import dslabs.framework.Command;
import dslabs.framework.Message;
import lombok.Data;

@Data
public final class PaxosRequest implements Message {
  // Your code here...
  private final Command command;
}
