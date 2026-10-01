package dslabs.paxos;

import dslabs.atmostonce.AMOCommand;
import dslabs.framework.Timer;
import lombok.Data;

@Data
final class ClientTimer implements Timer {
  static final int CLIENT_RETRY_MILLIS = 100;

  // Your code here...
  private final AMOCommand command;
}

// Your code here...

@Data
final class HeartbeatCheckTimer implements Timer {
  static final int HEARTBEAT_CHECK_MILLIS = 100;
}

@Data
final class HeartbeatTimer implements Timer {
  static final int HEARTBEAT_MILLIS = 100;
}

@Data
final class LeaderElectionTimer implements Timer {
  static final int LEADER_ELECTION_MILLIS = 100;

  private final Ballot ballot;
}

@Data
final class AcceptTimer implements Timer {
  static final int ACCEPT_RETRY_MILLIS = 100;

  private final int slot;
  private final Ballot ballot;
}
