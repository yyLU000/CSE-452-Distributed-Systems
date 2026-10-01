package dslabs.primarybackup;

import dslabs.framework.Timer;
import lombok.Data;

@Data
final class PingCheckTimer implements Timer {
  static final int PING_CHECK_MILLIS = 100;
}

@Data
final class PingTimer implements Timer {
  static final int PING_MILLIS = 25;
  // static final int PING_MILLIS = 50;
}

@Data
final class ClientTimer implements Timer {
  static final int CLIENT_RETRY_MILLIS = 50;

  // Your code here...
  /** Same logical command as {@code PBClient.pendingCommand} while waiting for a reply. */
  // private final AMOCommand command;
}
