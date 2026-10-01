package dslabs.paxos;

import dslabs.framework.Command;
import dslabs.framework.Message;
import dslabs.framework.Result;
import lombok.Data;

@Data
public final class PaxosReply implements Message {
  // Your code here...
  private final Command command;
  private final Result result;
}
