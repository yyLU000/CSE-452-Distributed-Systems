package dslabs.shardkv;

import dslabs.atmostonce.AMOApplication;
import dslabs.atmostonce.AMOCommand;
import dslabs.atmostonce.AMOResult;
import dslabs.framework.Command;
import dslabs.framework.Message;
import dslabs.kvstore.TransactionalKVStore;
import dslabs.shardmaster.ShardMaster.ShardConfig;
import java.util.Map;
import java.util.Set;
import lombok.Data;

@Data
final class ShardStoreRequest implements Message {
  // Your code here...
  private final AMOCommand command;
}

@Data
final class ShardStoreReply implements Message {
  // Your code here...
  private final AMOResult result;
  private final ShardConfig config;
  private final boolean wrongGroup;
}

// Your code here...
@Data
final class QueryReply implements Message {
  private final ShardConfig config;
}

@Data
final class ShardData implements Message {
  private final int configNum;
  private final int shardNum;
  private final AMOApplication<TransactionalKVStore> shardApp;
}

@Data
final class ShardAck implements Message {
  private final int configNum;
  private final int shardNum;
}

@Data
final class TxnPrepare implements Message {
  private final AMOCommand command;
  private final int configNum;
  private final int coordinatorGroupId;
  private final Set<Integer> participantGroups;
  private final int attempt;
}

@Data
final class TxnPrepareReply implements Message {
  private final AMOCommand command;
  private final int configNum;
  private final int participantGroupId;
  private final int attempt;
  private final boolean ok;
  private final Map<String, String> values;
}

@Data
final class TxnCommit implements Message {
  private final AMOCommand command;
  private final int configNum;
  private final int coordinatorGroupId;
  private final int attempt;
  private final Map<String, String> writes;
}

@Data
final class TxnCommitAck implements Message {
  private final AMOCommand command;
  private final int configNum;
  private final int participantGroupId;
  private final int attempt;
}

@Data
final class TxnAbort implements Message {
  private final AMOCommand command;
  private final int configNum;
  private final int coordinatorGroupId;
  private final int attempt;
}

@Data
final class TxnAbortAck implements Message {
  private final AMOCommand command;
  private final int configNum;
  private final int participantGroupId;
  private final int attempt;
}

interface ShardStoreCommand extends Command {}

@Data
final class NewConfig implements ShardStoreCommand {
  private final ShardConfig config;
}

@Data
final class ShardReceived implements ShardStoreCommand {
  private final int configNum;
  private final int shardNum;
  private final AMOApplication<TransactionalKVStore> shardApp;
}

@Data
final class ShardSent implements ShardStoreCommand {
  private final int configNum;
  private final int shardNum;
}

@Data
final class ConfigComplete implements ShardStoreCommand {
  private final int configNum;
}

@Data
final class StartTransaction implements ShardStoreCommand {
  private final AMOCommand command;
  private final int configNum;
  private final Set<Integer> participantGroups;
  private final int attempt;
}

@Data
final class PrepareTransaction implements ShardStoreCommand {
  private final AMOCommand command;
  private final int configNum;
  private final int coordinatorGroupId;
  private final Set<Integer> participantGroups;
  private final int attempt;
}

@Data
final class RecordPrepareReply implements ShardStoreCommand {
  private final TxnPrepareReply reply;
}

@Data
final class CommitTransaction implements ShardStoreCommand {
  private final AMOCommand command;
  private final int configNum;
  private final int coordinatorGroupId;
  private final int attempt;
  private final Map<String, String> writes;
}

@Data
final class RecordCommitAck implements ShardStoreCommand {
  private final TxnCommitAck ack;
}

@Data
final class AbortTransaction implements ShardStoreCommand {
  private final AMOCommand command;
  private final int configNum;
  private final int coordinatorGroupId;
  private final int attempt;
}

@Data
final class RecordAbortAck implements ShardStoreCommand {
  private final TxnAbortAck ack;
}
