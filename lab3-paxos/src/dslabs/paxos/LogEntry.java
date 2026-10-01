package dslabs.paxos;

import dslabs.framework.Command;
import java.io.Serializable;
import lombok.Data;

@Data
final class LogEntry implements Serializable {
  private final PaxosLogSlotStatus status;
  private final Command command;
  private final Ballot acceptedAt;
}
