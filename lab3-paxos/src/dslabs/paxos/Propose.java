package dslabs.paxos;

import dslabs.framework.Command;
import dslabs.framework.Message;
import lombok.Data;

@Data
public final class Propose implements Message {
  private final Command command;
}
