package dslabs.paxos;

import dslabs.framework.Command;
import dslabs.framework.Message;
import lombok.Data;

@Data
public final class PaxosDecision implements Message {
  private final int slot;
  private final Command command;
}
