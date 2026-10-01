package dslabs.shardkv;

import dslabs.atmostonce.AMOCommand;
import dslabs.framework.Timer;
import lombok.Data;

@Data
final class ClientTimer implements Timer {
  static final int CLIENT_RETRY_MILLIS = 50;

  // Your code here...
  private final AMOCommand pendingAmoCommand;
}

// Your code here...
@Data
final class ConfigPollTimer implements Timer {
  static final int CONFIG_POLL_MILLIS = 50;
}

@Data
final class HandoffTimer implements Timer {
  static final int HANDOFF_RETRY_MILLIS = 50;

  private final int configNum;
  private final int shardNum;
  private final int destinationGroupId;
}

@Data
final class IncomingHandoffTimer implements Timer {
  private final int configNum;
  private final int shardNum;
}

@Data
final class TransactionTimer implements Timer {
  static final int TRANSACTION_RETRY_MILLIS = 25;

  private final AMOCommand command;
  private final int attempt;
}
