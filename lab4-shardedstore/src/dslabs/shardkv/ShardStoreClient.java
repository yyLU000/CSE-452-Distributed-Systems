package dslabs.shardkv;

import dslabs.atmostonce.AMOCommand;
import dslabs.atmostonce.AMOResult;
import dslabs.framework.Address;
import dslabs.framework.Client;
import dslabs.framework.Command;
import dslabs.framework.Result;
import dslabs.kvstore.KVStore.SingleKeyCommand;
import dslabs.kvstore.TransactionalKVStore.Transaction;
import dslabs.paxos.PaxosReply;
import dslabs.paxos.PaxosRequest;
import dslabs.shardmaster.ShardMaster.Query;
import dslabs.shardmaster.ShardMaster.ShardConfig;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.apache.commons.lang3.tuple.Pair;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class ShardStoreClient extends ShardStoreNode implements Client {
  // Your code here...
  private ShardConfig currentConfig = null;
  private int nextSequenceNum = 1;

  private AMOCommand pendingCommand = null;
  private Result pendingResult = null;
  private Set<Integer> pendingShards = new HashSet<>();
  private Integer targetGroupId = null;

  /* -----------------------------------------------------------------------------------------------
   *  Construction and Initialization
   * ---------------------------------------------------------------------------------------------*/
  public ShardStoreClient(Address address, Address[] shardMasters, int numShards) {
    super(address, shardMasters, numShards);
  }

  @Override
  public synchronized void init() {
    // Your code here...
    queryShardMasters(-1);
  }

  /* -----------------------------------------------------------------------------------------------
   *  Client Methods
   * ---------------------------------------------------------------------------------------------*/
  @Override
  public synchronized void sendCommand(Command command) {
    // Your code here...
    Set<Integer> shards = shardsFor(command);
    if (shards == null) {
      throw new IllegalArgumentException();
    }
    pendingCommand = new AMOCommand(command, address(), nextSequenceNum);
    pendingResult = null;
    pendingShards = shards;
    targetGroupId = null;
    retryPendingCommand();
    set(new ClientTimer(pendingCommand), ClientTimer.CLIENT_RETRY_MILLIS);
    nextSequenceNum++;
  }

  @Override
  public synchronized boolean hasResult() {
    // Your code here...
    return pendingResult != null;
  }

  @Override
  public synchronized Result getResult() throws InterruptedException {
    // Your code here...
    while (!hasResult()) {
      wait();
    }
    pendingCommand = null;
    return pendingResult;
  }

  /* -----------------------------------------------------------------------------------------------
   *  Message Handlers
   * ---------------------------------------------------------------------------------------------*/
  private synchronized void handleShardStoreReply(ShardStoreReply m, Address sender) {
    // Your code here...
    if (pendingCommand == null) {
      return;
    }
    if (m.wrongGroup()) {
      ShardConfig config = m.config();
      if (config == null) {
        queryShardMasters(-1);
        return;
      }
      currentConfig = config;
      return;
    } else {
      AMOResult result = m.result();
      if (result == null
          || !result.address().equals(address())
          || result.sequenceNum() != pendingCommand.sequenceNum()) {
        return;
      }
      pendingResult = result.result();
      notifyAll();
    }
  }

  // Your code here...
  private synchronized void handlePaxosReply(PaxosReply m, Address sender) {
    Result result = m.result();
    if (result instanceof AMOResult) {
      result = ((AMOResult) result).result();
    }
    if (result instanceof ShardConfig) {
      ShardConfig config = (ShardConfig) result;
      if (currentConfig == null || config.configNum() > currentConfig.configNum()) {
        currentConfig = config;
      }
      retryPendingCommand();
    }
  }

  /* -----------------------------------------------------------------------------------------------
   *  Timer Handlers
   * ---------------------------------------------------------------------------------------------*/
  private synchronized void onClientTimer(ClientTimer t) {
    // Your code here...
    if (pendingCommand == null
        || pendingResult != null
        || !t.pendingAmoCommand().equals(pendingCommand)) {
      return;
    }
    retryPendingCommand();
    set(t, ClientTimer.CLIENT_RETRY_MILLIS);
  }

  private void retryPendingCommand() {
    if (pendingCommand == null) {
      return;
    }
    if (currentConfig == null) {
      queryShardMasters(-1);
      return;
    }

    targetGroupId = coordinatorFor(currentConfig, pendingShards);
    if (targetGroupId == null) {
      queryShardMasters(-1);
      return;
    }
    broadcast(new ShardStoreRequest(pendingCommand), membersOf(currentConfig, targetGroupId));
  }

  private void queryShardMasters(int configNum) {
    broadcastToShardMasters(new PaxosRequest(new Query(configNum)));
  }

  private Integer ownerOf(ShardConfig config, int shard) {
    for (Map.Entry<Integer, Pair<Set<Address>, Set<Integer>>> entry :
        config.groupInfo().entrySet()) {
      if (entry.getValue().getRight().contains(shard)) {
        return entry.getKey();
      }
    }
    return null;
  }

  private Set<Integer> shardsFor(Command command) {
    Set<Integer> shards = new HashSet<>();
    if (command instanceof SingleKeyCommand) {
      shards.add(keyToShard(((SingleKeyCommand) command).key()));
      return shards;
    }
    if (command instanceof Transaction) {
      for (String key : ((Transaction) command).keySet()) {
        shards.add(keyToShard(key));
      }
      return shards;
    }
    return null;
  }

  private Integer coordinatorFor(ShardConfig config, Set<Integer> shards) {
    Integer coordinator = null;
    for (int shard : shards) {
      Integer owner = ownerOf(config, shard);
      if (owner == null) {
        return null;
      }
      if (coordinator == null || owner > coordinator) {
        coordinator = owner;
      }
    }
    return coordinator;
  }

  private Set<Address> membersOf(ShardConfig config, int gid) {
    if (config == null || !config.groupInfo().containsKey(gid)) {
      return new HashSet<>();
    }
    return new HashSet<>(config.groupInfo().get(gid).getLeft());
  }
}
