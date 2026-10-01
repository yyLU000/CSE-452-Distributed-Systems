package dslabs.paxos;

import dslabs.framework.Command;
import dslabs.framework.Message;
import java.util.Map;
import lombok.Data;

// Your code here...

@Data
final class P1AMessage implements Message {
  private final Ballot ballot;
}

@Data
final class P1BMessage implements Message {
  private final Ballot ballot;
  private final Map<Integer, LogEntry> logEntries;
}

@Data
final class P2AMessage implements Message {
  private final Ballot ballot;
  private final int slot;
  private final Command command;
}

@Data
final class P2BMessage implements Message {
  private final Ballot ballot;
  private final int slot;
}

@Data
final class DecisionMessage implements Message {
  private final int slot;
  private final Command command;
}

@Data
final class HeartbeatMessage implements Message {
  private final Ballot ballot;
  private final int garbageBoundary;
}

@Data
final class HeartbeatReply implements Message {
  private final Ballot ballot;
  private final int slotOut;
}
