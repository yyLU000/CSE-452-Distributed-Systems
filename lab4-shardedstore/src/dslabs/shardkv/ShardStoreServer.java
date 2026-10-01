package dslabs.shardkv;

import static dslabs.shardmaster.ShardMaster.INITIAL_CONFIG_NUM;

import dslabs.atmostonce.AMOApplication;
import dslabs.atmostonce.AMOCommand;
import dslabs.atmostonce.AMOResult;
import dslabs.framework.Address;
import dslabs.framework.Command;
import dslabs.framework.Result;
import dslabs.kvstore.KVStore.SingleKeyCommand;
import dslabs.kvstore.TransactionalKVStore;
import dslabs.kvstore.TransactionalKVStore.Transaction;
import dslabs.paxos.PaxosDecision;
import dslabs.paxos.PaxosReply;
import dslabs.paxos.PaxosRequest;
import dslabs.paxos.PaxosServer;
import dslabs.paxos.Propose;
import dslabs.shardmaster.ShardMaster.Query;
import dslabs.shardmaster.ShardMaster.ShardConfig;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.apache.commons.lang3.tuple.Pair;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class ShardStoreServer extends ShardStoreNode {
  private final Address[] group;
  private final int groupId;

  // Your code here...
  private static final String PAXOS_ADDRESS_ID = "paxos";
  private Address paxosAddress;

  private ShardConfig currentConfig = null;
  private ShardConfig targetConfig = null;

  private int nextSlotToExecute = 1;
  private final Map<Integer, Command> decisions = new HashMap<>();

  private enum ShardStatus {
    NOT_OWNED,
    RECEIVING,
    OWNED,
    GIVING_UP
  }

  private final Map<Integer, ShardStatus> shardStatus = new HashMap<>();
  private final Map<Integer, AMOApplication<TransactionalKVStore>> shardApps = new HashMap<>();
  private final Map<Address, Integer> lastTransactionSequenceNums = new HashMap<>();
  private final Map<Address, AMOResult> transactionResults = new HashMap<>();
  private final Map<String, Integer> transactionAttempts = new HashMap<>();
  private final Map<String, CoordinatorState> coordinatorStates = new HashMap<>();
  private final Map<String, PreparedState> preparedTransactions = new HashMap<>();
  private final Map<String, String> keyLocks = new HashMap<>();
  private final Set<String> committedTransactions = new HashSet<>();
  private final Map<String, Map<String, String>> committedTransactionValues = new HashMap<>();
  private final Set<String> abortedTransactions = new HashSet<>();
  private final Map<String, CommitTransaction> pendingCommits = new HashMap<>();

  private final Set<Integer> pendingIncoming = new HashSet<>();
  private final Map<String, ShardData> pendingIncomingData = new HashMap<>();
  private final Map<Integer, Integer> pendingOutgoing = new HashMap<>();
  private final Map<Integer, ShardData> outgoingPayload = new HashMap<>();
  private final Set<String> shardReceivedProposalKeys = new HashSet<>();

  private static final class CoordinatorState {
    private final AMOCommand command;
    private final int configNum;
    private final Set<Integer> participantGroups;
    private final int attempt;
    private final Map<Integer, TxnPrepareReply> prepareReplies = new HashMap<>();
    private final Set<Integer> commitAcks = new HashSet<>();
    private final Set<Integer> abortAcks = new HashSet<>();
    private boolean finalDecisionSent = false;
    private AMOResult result = null;

    private CoordinatorState(
        AMOCommand command, int configNum, Set<Integer> participantGroups, int attempt) {
      this.command = command;
      this.configNum = configNum;
      this.participantGroups = new HashSet<>(participantGroups);
      this.attempt = attempt;
    }
  }

  private static final class PreparedState {
    private final int coordinatorGroupId;
    private final Set<String> lockedKeys;
    private final Map<String, String> values;

    private PreparedState(
        int coordinatorGroupId, Set<String> lockedKeys, Map<String, String> values) {
      this.coordinatorGroupId = coordinatorGroupId;
      this.lockedKeys = new HashSet<>(lockedKeys);
      this.values = new HashMap<>(values);
    }
  }

  private static String incomingDataKey(int configNum, int shardNum) {
    return configNum + ":" + shardNum;
  }

  private ShardData bufferedIncoming(int configNum, int shardNum) {
    return pendingIncomingData.get(incomingDataKey(configNum, shardNum));
  }

  private void bufferIncoming(ShardData data) {
    pendingIncomingData.put(incomingDataKey(data.configNum(), data.shardNum()), data);
  }

  private void removeBufferedIncoming(int configNum, int shardNum) {
    pendingIncomingData.remove(incomingDataKey(configNum, shardNum));
  }

  private void proposeShardReceived(ShardData data) {
    if (targetConfig == null || data.configNum() != targetConfig.configNum()) {
      return;
    }
    if (!pendingIncoming.contains(data.shardNum())) {
      return;
    }
    String proposalKey = incomingDataKey(data.configNum(), data.shardNum());
    if (!shardReceivedProposalKeys.add(proposalKey)) {
      return;
    }
    proposeToPaxosGroup(new ShardReceived(data.configNum(), data.shardNum(), data.shardApp()));
  }

  private void retryProposeShardReceived(ShardData data) {
    if (targetConfig == null || data.configNum() != targetConfig.configNum()) {
      return;
    }
    if (!pendingIncoming.contains(data.shardNum())) {
      return;
    }
    shardReceivedProposalKeys.remove(incomingDataKey(data.configNum(), data.shardNum()));
    proposeShardReceived(data);
  }

  private void startIncomingHandoffTimer(int configNum, int shardNum) {
    set(new IncomingHandoffTimer(configNum, shardNum), HandoffTimer.HANDOFF_RETRY_MILLIS);
  }

  private void sendOutgoingShardData(int shardNum, int destinationGroupId) {
    ShardData shardData = outgoingPayload.get(shardNum);
    if (shardData == null || targetConfig == null) {
      return;
    }
    Set<Address> destinations = membersOf(targetConfig, destinationGroupId);
    for (Address destination : destinations) {
      send(shardData, destination);
    }
  }

  /* -----------------------------------------------------------------------------------------------
   *  Construction and Initialization
   * ---------------------------------------------------------------------------------------------*/
  ShardStoreServer(
      Address address, Address[] shardMasters, int numShards, Address[] group, int groupId) {
    super(address, shardMasters, numShards);
    this.group = group;
    this.groupId = groupId;

    // Your code here...
    for (int shard = 1; shard <= numShards; shard++) {
      shardStatus.put(shard, ShardStatus.NOT_OWNED);
    }
  }

  @Override
  public void init() {
    // Your code here...
    paxosAddress = Address.subAddress(address(), PAXOS_ADDRESS_ID);
    Address[] paxosAddresses = new Address[group.length];
    for (int i = 0; i < paxosAddresses.length; i++) {
      paxosAddresses[i] = Address.subAddress(group[i], PAXOS_ADDRESS_ID);
    }
    PaxosServer paxosServer = new PaxosServer(paxosAddress, paxosAddresses, address());
    addSubNode(paxosServer);
    paxosServer.init();

    queryShardMasters(-1);
    set(new ConfigPollTimer(), ConfigPollTimer.CONFIG_POLL_MILLIS);
  }

  /* -----------------------------------------------------------------------------------------------
   *  Message Handlers
   * ---------------------------------------------------------------------------------------------*/
  private void handleShardStoreRequest(ShardStoreRequest m, Address sender) {
    // Your code here...
    AMOCommand amoCommand = m.command();
    Set<Integer> shards = shardsFor(amoCommand.command());
    ShardConfig routingConfig = bestKnownConfig();
    Integer coordinator = routingConfig == null ? null : coordinatorFor(routingConfig, shards);
    Set<Integer> participantGroups =
        routingConfig == null ? null : participantGroupsFor(routingConfig, shards);

    if (routingConfig == null
        || shards == null
        || coordinator == null
        || participantGroups == null
        || !coordinator.equals(groupId)) {
      queryShardMasters(-1);
      send(new ShardStoreReply(null, bestKnownConfig(), true), sender);
      return;
    }

    if (amoCommand.command() instanceof Transaction) {
      if (alreadyExecutedTransaction(amoCommand)) {
        send(
            new ShardStoreReply(transactionResults.get(amoCommand.address()), null, false), sender);
        return;
      }
      boolean use2PC =
          participantGroups.size() > 1
              || transactionAttempts.containsKey(transactionRequestKey(amoCommand))
              || activeTransactionKey(amoCommand) != null;
      if (use2PC) {
        String active = activeTransactionKey(amoCommand);
        if (active == null) {
          int attempt = nextTransactionAttempt(amoCommand);
          String attemptKey = transactionKey(amoCommand, attempt);
          CoordinatorState attemptState = coordinatorStates.get(attemptKey);
          if (attemptState == null) {
            proposeToPaxosGroup(
                new StartTransaction(
                    amoCommand, routingConfig.configNum(), participantGroups, attempt));
          } else if (attemptState.result != null) {
            resendCommit(attemptState);
          } else if (attemptState.finalDecisionSent) {
            sendAbort(attemptState);
          } else {
            resendCoordinatorMessages(attemptState);
          }
        } else {
          CoordinatorState activeState = coordinatorStates.get(active);
          if (activeState != null && activeState.result != null) {
            resendCommit(activeState);
          } else if (activeState != null && activeState.finalDecisionSent) {
            sendAbort(activeState);
          } else {
            resendCoordinatorMessages(activeState);
          }
        }
        return;
      }
    } else {
      if (!ownsAll(shards)) {
        send(new ShardStoreReply(null, bestKnownConfig(), true), sender);
        return;
      }
      int shard = shards.iterator().next();
      AMOApplication<TransactionalKVStore> shardApp = shardApps.get(shard);
      if (shardApp == null) {
        send(new ShardStoreReply(null, bestKnownConfig(), true), sender);
        return;
      }
      if (shardApp.alreadyExecuted(amoCommand)) {
        AMOResult result = shardApp.execute(amoCommand);
        send(new ShardStoreReply(result, null, false), sender);
        return;
      }
    }

    proposeToPaxosGroup(amoCommand);
  }

  // Your code here...
  private void handlePaxosDecision(PaxosDecision m, Address sender) {
    if (m.slot() >= nextSlotToExecute) {
      decisions.put(m.slot(), m.command());
    }
    while (decisions.containsKey(nextSlotToExecute)) {
      Command command = decisions.remove(nextSlotToExecute);
      executePaxosCommand(command);
      nextSlotToExecute++;
    }
  }

  private void handleQueryReply(QueryReply m, Address sender) {
    ShardConfig c = m.config();
    if (c == null) {
      return;
    }
    if (currentConfig != null && c.configNum() <= currentConfig.configNum()) {
      return;
    }
    if (targetConfig != null) {
      return;
    }
    int expectedConfigNum =
        currentConfig == null ? INITIAL_CONFIG_NUM : currentConfig.configNum() + 1;
    if (c.configNum() == expectedConfigNum) {
      proposeToPaxosGroup(new NewConfig(c));
    } else if (c.configNum() > expectedConfigNum) {
      queryShardMasters(expectedConfigNum);
    }
  }

  private void handlePaxosReply(PaxosReply m, Address sender) {
    Result result = m.result();
    if (result instanceof AMOResult) {
      result = ((AMOResult) result).result();
    }
    if (result instanceof ShardConfig) {
      handleQueryReply(new QueryReply((ShardConfig) result), sender);
    }
  }

  private void handleShardData(ShardData m, Address sender) {
    int expectedConfigNum =
        currentConfig == null ? INITIAL_CONFIG_NUM : currentConfig.configNum() + 1;
    if ((targetConfig == null && m.configNum() >= expectedConfigNum)
        || (targetConfig != null && m.configNum() > targetConfig.configNum())) {
      bufferIncoming(m);
      return;
    }

    if (targetConfig != null
        && m.configNum() == targetConfig.configNum()
        && pendingIncoming.contains(m.shardNum())) {
      boolean firstData = bufferedIncoming(m.configNum(), m.shardNum()) == null;
      bufferIncoming(m);
      if (firstData) {
        proposeShardReceived(m);
        startIncomingHandoffTimer(m.configNum(), m.shardNum());
      }
      return;
    }

    if (targetConfig != null
        && m.configNum() == targetConfig.configNum()
        && shardStatus.get(m.shardNum()) == ShardStatus.OWNED) {
      send(new ShardAck(m.configNum(), m.shardNum()), sender);
      return;
    }

    if (currentConfig != null && m.configNum() <= currentConfig.configNum()) {
      boolean stillWaitingForThisConfig =
          targetConfig != null
              && m.configNum() == targetConfig.configNum()
              && pendingIncoming.contains(m.shardNum());
      if (!stillWaitingForThisConfig) {
        send(new ShardAck(m.configNum(), m.shardNum()), sender);
      }
      return;
    }
  }

  private void handleShardAck(ShardAck m, Address sender) {
    if (targetConfig == null
        || m.configNum() != targetConfig.configNum()
        || !pendingOutgoing.containsKey(m.shardNum())) {
      return;
    }
    proposeToPaxosGroup(new ShardSent(m.configNum(), m.shardNum()));
  }

  private void handleTxnPrepare(TxnPrepare m, Address sender) {
    proposeToPaxosGroup(
        new PrepareTransaction(
            m.command(),
            m.configNum(),
            m.coordinatorGroupId(),
            m.participantGroups(),
            m.attempt()));
  }

  private void handleTxnPrepareReply(TxnPrepareReply m, Address sender) {
    proposeToPaxosGroup(new RecordPrepareReply(m));
  }

  private void handleTxnCommit(TxnCommit m, Address sender) {
    proposeToPaxosGroup(
        new CommitTransaction(
            m.command(), m.configNum(), m.coordinatorGroupId(), m.attempt(), m.writes()));
  }

  private void handleTxnCommitAck(TxnCommitAck m, Address sender) {
    proposeToPaxosGroup(new RecordCommitAck(m));
  }

  private void handleTxnAbort(TxnAbort m, Address sender) {
    proposeToPaxosGroup(
        new AbortTransaction(m.command(), m.configNum(), m.coordinatorGroupId(), m.attempt()));
  }

  private void handleTxnAbortAck(TxnAbortAck m, Address sender) {
    proposeToPaxosGroup(new RecordAbortAck(m));
  }

  /* -----------------------------------------------------------------------------------------------
   *  Timer Handlers
   * ---------------------------------------------------------------------------------------------*/
  // Your code here...
  private void onConfigPollTimer(ConfigPollTimer t) {
    if (targetConfig == null) {
      queryShardMasters(-1);
    } else if (pendingIncoming.isEmpty() && pendingOutgoing.isEmpty()) {
      proposeToPaxosGroup(new ConfigComplete(targetConfig.configNum()));
    } else {
      for (Map.Entry<Integer, Integer> entry : new HashSet<>(pendingOutgoing.entrySet())) {
        sendOutgoingShardData(entry.getKey(), entry.getValue());
      }
    }
    set(t, ConfigPollTimer.CONFIG_POLL_MILLIS);
  }

  private void onIncomingHandoffTimer(IncomingHandoffTimer t) {
    if (targetConfig == null || targetConfig.configNum() != t.configNum()) {
      return;
    }
    if (!pendingIncoming.contains(t.shardNum())) {
      return;
    }
    ShardData shardData = bufferedIncoming(t.configNum(), t.shardNum());
    if (shardData != null) {
      retryProposeShardReceived(shardData);
    }
    set(t, HandoffTimer.HANDOFF_RETRY_MILLIS);
  }

  private void onHandoffTimer(HandoffTimer t) {
    if (currentConfig != null && currentConfig.configNum() >= t.configNum()) {
      return;
    }
    if (targetConfig == null || t.configNum() != targetConfig.configNum()) {
      return;
    }
    if (!pendingOutgoing.containsKey(t.shardNum())
        || !pendingOutgoing.get(t.shardNum()).equals(t.destinationGroupId())) {
      return;
    }

    ShardData shardData = outgoingPayload.get(t.shardNum());
    if (shardData == null) {
      return;
    }
    sendOutgoingShardData(t.shardNum(), t.destinationGroupId());
    set(t, HandoffTimer.HANDOFF_RETRY_MILLIS);
  }

  private void onTransactionTimer(TransactionTimer t) {
    String key = transactionKey(t.command(), t.attempt());
    Integer latestAttempt = transactionAttempts.get(transactionRequestKey(t.command()));
    if (latestAttempt != null && t.attempt() < latestAttempt) {
      CoordinatorState staleState = coordinatorStates.get(key);
      if (staleState != null && staleState.result == null) {
        sendAbort(staleState);
      }
      coordinatorStates.remove(key);
      return;
    }
    CoordinatorState state = coordinatorStates.get(key);
    if (state == null) {
      return;
    }
    if (state.result != null) {
      resendCommit(state);
    } else if (state.finalDecisionSent) {
      sendAbort(state);
    } else {
      resendCoordinatorMessages(state);
    }
    set(t, TransactionTimer.TRANSACTION_RETRY_MILLIS);
  }

  /* -----------------------------------------------------------------------------------------------
   *  Utils
   * ---------------------------------------------------------------------------------------------*/
  // Your code here...
  private Integer ownerOf(ShardConfig config, int shard) {
    for (Map.Entry<Integer, Pair<Set<Address>, Set<Integer>>> entry :
        config.groupInfo().entrySet()) {
      if (entry.getValue().getRight().contains(shard)) {
        return entry.getKey();
      }
    }
    return null;
  }

  private void proposeToPaxosGroup(Command command) {
    Propose propose = new Propose(command);
    for (Address server : group) {
      Address destination = Address.subAddress(server, PAXOS_ADDRESS_ID);
      if (destination.equals(paxosAddress)) {
        handleMessage(propose, paxosAddress);
      } else {
        send(propose, destination);
      }
    }
  }

  private void queryShardMasters(int configNum) {
    broadcastToShardMasters(new PaxosRequest(new Query(configNum)));
  }

  private void executePaxosCommand(Command command) {
    if (command instanceof AMOCommand) {
      AMOCommand amoCommand = (AMOCommand) command;
      Set<Integer> shards = shardsFor(amoCommand.command());
      ShardConfig routingConfig = bestKnownConfig();
      Integer coordinator = routingConfig == null ? null : coordinatorFor(routingConfig, shards);
      if (routingConfig == null
          || shards == null
          || coordinator == null
          || !coordinator.equals(groupId)
          || !ownsAll(shards)) {
        send(new ShardStoreReply(null, bestKnownConfig(), true), amoCommand.address());
        return;
      }

      AMOResult result;
      if (amoCommand.command() instanceof Transaction) {
        if (transactionAttempts.containsKey(transactionRequestKey(amoCommand))) {
          send(new ShardStoreReply(null, bestKnownConfig(), true), amoCommand.address());
          return;
        }
        result = executeLocalTransaction(amoCommand);
      } else {
        int shard = shards.iterator().next();
        result = shardApps.get(shard).execute(amoCommand);
      }
      send(new ShardStoreReply(result, null, false), amoCommand.address());

      return;
    }
    if (command instanceof StartTransaction) {
      StartTransaction st = (StartTransaction) command;
      if (alreadyExecutedTransaction(st.command())) {
        send(
            new ShardStoreReply(transactionResults.get(st.command().address()), null, false),
            st.command().address());
        return;
      }
      ShardConfig routingConfig = bestKnownConfig();
      if (routingConfig == null || st.configNum() != routingConfig.configNum()) {
        send(new ShardStoreReply(null, bestKnownConfig(), true), st.command().address());
        return;
      }

      String requestKey = transactionRequestKey(st.command());
      int latestAttempt = transactionAttempts.getOrDefault(requestKey, 0);
      if (st.attempt() < latestAttempt) {
        return;
      }

      String key = transactionKey(st.command(), st.attempt());
      if (st.attempt() > latestAttempt) {
        removeOtherCoordinatorAttempts(st.command(), st.attempt());
        transactionAttempts.put(requestKey, st.attempt());
      }
      CoordinatorState state = coordinatorStates.get(key);
      if (state == null) {
        state =
            new CoordinatorState(
                st.command(), st.configNum(), st.participantGroups(), st.attempt());
        coordinatorStates.put(key, state);
        set(
            new TransactionTimer(st.command(), st.attempt()),
            TransactionTimer.TRANSACTION_RETRY_MILLIS);
      }
      resendCoordinatorMessages(state);
      return;
    }
    if (command instanceof PrepareTransaction) {
      PrepareTransaction pt = (PrepareTransaction) command;
      TxnPrepareReply reply = prepareTransaction(pt);
      for (Address server : membersOfConfig(pt.configNum(), pt.coordinatorGroupId())) {
        send(reply, server);
      }
      String key = transactionKey(pt.command(), pt.attempt());
      if (reply.ok()) {
        CommitTransaction pending = pendingCommits.remove(key);
        if (pending != null) {
          commitTransaction(pending);
        }
      } else {
        pendingCommits.remove(key);
      }
      return;
    }
    if (command instanceof RecordPrepareReply) {
      recordPrepareReply(((RecordPrepareReply) command).reply());
      return;
    }
    if (command instanceof CommitTransaction) {
      CommitTransaction ct = (CommitTransaction) command;
      commitTransaction(ct);
      return;
    }
    if (command instanceof RecordCommitAck) {
      TxnCommitAck ack = ((RecordCommitAck) command).ack();
      recordCommitAck(ack);
      return;
    }
    if (command instanceof AbortTransaction) {
      AbortTransaction at = (AbortTransaction) command;
      abortTransaction(at.command(), at.configNum(), at.coordinatorGroupId(), at.attempt());
      return;
    }
    if (command instanceof RecordAbortAck) {
      recordAbortAck(((RecordAbortAck) command).ack());
      return;
    }
    if (command instanceof NewConfig) {
      int expectedConfigNum =
          currentConfig == null ? INITIAL_CONFIG_NUM : currentConfig.configNum() + 1;
      ShardConfig c = ((NewConfig) command).config();
      if (c.configNum() != expectedConfigNum || targetConfig != null || !keyLocks.isEmpty()) {
        return;
      }
      targetConfig = c;
      pendingIncoming.clear();
      pendingOutgoing.clear();
      outgoingPayload.clear();
      shardReceivedProposalKeys.clear();

      Set<Integer> oldShards =
          currentConfig == null ? new HashSet<>() : shardsOf(currentConfig, groupId);
      Set<Integer> newShards = shardsOf(c, groupId);

      for (int shard : oldShards) {
        Integer newOwner = ownerOf(c, shard);
        if (newOwner == null) {
          shardApps.remove(shard);
          shardStatus.put(shard, ShardStatus.NOT_OWNED);
        } else if (!newOwner.equals(groupId)) {
          pendingOutgoing.put(shard, newOwner);
          shardStatus.put(shard, ShardStatus.GIVING_UP);
          outgoingPayload.put(shard, new ShardData(c.configNum(), shard, shardApps.get(shard)));
        }
      }

      for (int shard : newShards) {
        if (currentConfig == null && c.configNum() == INITIAL_CONFIG_NUM) {
          shardStatus.put(shard, ShardStatus.OWNED);
          if (!shardApps.containsKey(shard)) {
            shardApps.put(shard, new AMOApplication<>(new TransactionalKVStore()));
          }
          continue;
        }
        Integer oldOwner = currentConfig == null ? null : ownerOf(currentConfig, shard);
        if (currentConfig == null) {
          pendingIncoming.add(shard);
          shardStatus.put(shard, ShardStatus.RECEIVING);
          startIncomingHandoffTimer(c.configNum(), shard);
        } else if (oldOwner == null) {
          shardStatus.put(shard, ShardStatus.OWNED);
          if (!shardApps.containsKey(shard)) {
            shardApps.put(shard, new AMOApplication<>(new TransactionalKVStore()));
          }
        } else if (!oldOwner.equals(groupId)) {
          pendingIncoming.add(shard);
          shardStatus.put(shard, ShardStatus.RECEIVING);
          startIncomingHandoffTimer(c.configNum(), shard);
        }
      }

      pendingIncomingData.entrySet().removeIf(e -> e.getValue().configNum() < c.configNum());
      for (int shard : new HashSet<>(pendingIncoming)) {
        ShardData buffered = bufferedIncoming(c.configNum(), shard);
        if (buffered != null) {
          proposeShardReceived(buffered);
          startIncomingHandoffTimer(c.configNum(), shard);
        }
      }

      for (Map.Entry<Integer, Integer> entry : pendingOutgoing.entrySet()) {
        sendOutgoingShardData(entry.getKey(), entry.getValue());
        set(
            new HandoffTimer(c.configNum(), entry.getKey(), entry.getValue()),
            HandoffTimer.HANDOFF_RETRY_MILLIS);
      }

      if (pendingIncoming.isEmpty() && pendingOutgoing.isEmpty()) {
        completeConfig(c.configNum());
      }

      return;
    }
    if (command instanceof ShardReceived) {
      ShardReceived sr = (ShardReceived) command;
      if (targetConfig == null
          || sr.configNum() != targetConfig.configNum()
          || !pendingIncoming.contains(sr.shardNum())) {
        return;
      }

      shardApps.put(sr.shardNum(), sr.shardApp());
      shardStatus.put(sr.shardNum(), ShardStatus.OWNED);
      pendingIncoming.remove(sr.shardNum());
      shardReceivedProposalKeys.remove(incomingDataKey(sr.configNum(), sr.shardNum()));
      removeBufferedIncoming(sr.configNum(), sr.shardNum());

      Integer oldOwner = ownerOf(currentConfig, sr.shardNum());
      if (oldOwner != null) {
        for (Address server : membersOf(currentConfig, oldOwner)) {
          send(new ShardAck(sr.configNum(), sr.shardNum()), server);
        }
      }

      if (pendingIncoming.isEmpty() && pendingOutgoing.isEmpty()) {
        proposeToPaxosGroup(new ConfigComplete(targetConfig.configNum()));
      }

      return;
    }
    if (command instanceof ShardSent) {
      ShardSent ss = (ShardSent) command;
      if (targetConfig == null
          || ss.configNum() != targetConfig.configNum()
          || !pendingOutgoing.containsKey(ss.shardNum())) {
        return;
      }

      shardApps.remove(ss.shardNum());
      outgoingPayload.remove(ss.shardNum());
      shardStatus.put(ss.shardNum(), ShardStatus.NOT_OWNED);
      pendingOutgoing.remove(ss.shardNum());

      if (pendingIncoming.isEmpty() && pendingOutgoing.isEmpty()) {
        proposeToPaxosGroup(new ConfigComplete(targetConfig.configNum()));
      }

      return;
    }
    if (command instanceof ConfigComplete) {
      ConfigComplete cc = (ConfigComplete) command;
      if (targetConfig == null || cc.configNum() != targetConfig.configNum()) {
        return;
      }
      completeConfig(cc.configNum());
      return;
    }

    throw new IllegalArgumentException();
  }

  private Set<Integer> shardsOf(ShardConfig config, int gid) {
    if (config == null || !config.groupInfo().containsKey(gid)) {
      return new HashSet<>();
    }
    return new HashSet<>(config.groupInfo().get(gid).getRight());
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
    if (config == null || shards == null) {
      return null;
    }
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

  private boolean ownsAll(Set<Integer> shards) {
    if (shards == null) {
      return false;
    }
    for (int shard : shards) {
      Integer owner = currentConfig == null ? null : ownerOf(currentConfig, shard);
      if (owner == null
          || !owner.equals(groupId)
          || shardStatus.get(shard) != ShardStatus.OWNED
          || !shardApps.containsKey(shard)) {
        return false;
      }
    }
    return true;
  }

  private Set<Integer> participantGroupsFor(ShardConfig config, Set<Integer> shards) {
    if (config == null || shards == null) {
      return null;
    }
    Set<Integer> groups = new HashSet<>();
    for (int shard : shards) {
      Integer owner = ownerOf(config, shard);
      if (owner == null) {
        return null;
      }
      groups.add(owner);
    }
    return groups;
  }

  private Set<String> localKeys(Transaction transaction) {
    Set<String> keys = new HashSet<>();
    for (String key : transaction.keySet()) {
      Integer owner = currentConfig == null ? null : ownerOf(currentConfig, keyToShard(key));
      if (owner != null && owner.equals(groupId)) {
        keys.add(key);
      }
    }
    return keys;
  }

  private Set<String> localWriteKeys(Transaction transaction) {
    Set<String> keys = new HashSet<>();
    for (String key : transaction.writeSet()) {
      Integer owner = currentConfig == null ? null : ownerOf(currentConfig, keyToShard(key));
      if (owner != null && owner.equals(groupId)) {
        keys.add(key);
      }
    }
    return keys;
  }

  private boolean alreadyExecutedTransaction(AMOCommand amoCommand) {
    Integer lastSequenceNum = lastTransactionSequenceNums.get(amoCommand.address());
    return lastSequenceNum != null && lastSequenceNum >= amoCommand.sequenceNum();
  }

  private AMOResult executeLocalTransaction(AMOCommand amoCommand) {
    if (alreadyExecutedTransaction(amoCommand)) {
      return transactionResults.get(amoCommand.address());
    }

    Transaction transaction = (Transaction) amoCommand.command();
    Map<String, String> transactionDb = new HashMap<>();
    for (String key : transaction.keySet()) {
      TransactionalKVStore app = shardApps.get(keyToShard(key)).application();
      if (app.containsKey(key)) {
        transactionDb.put(key, app.get(key));
      }
    }

    Result result = transaction.run(transactionDb);
    for (String key : transaction.writeSet()) {
      TransactionalKVStore app = shardApps.get(keyToShard(key)).application();
      if (transactionDb.containsKey(key)) {
        app.put(key, transactionDb.get(key));
      } else {
        app.remove(key);
      }
    }

    AMOResult amoResult = new AMOResult(result, amoCommand.address(), amoCommand.sequenceNum());
    lastTransactionSequenceNums.put(amoCommand.address(), amoCommand.sequenceNum());
    transactionResults.put(amoCommand.address(), amoResult);
    return amoResult;
  }

  private String transactionRequestKey(AMOCommand command) {
    return command.address() + ":" + command.sequenceNum();
  }

  private String transactionKey(AMOCommand command, int attempt) {
    return transactionRequestKey(command) + ":" + attempt;
  }

  private int nextTransactionAttempt(AMOCommand command) {
    return transactionAttempts.getOrDefault(transactionRequestKey(command), 0) + 1;
  }

  private void removeOtherCoordinatorAttempts(AMOCommand command, int attempt) {
    String prefix = transactionRequestKey(command) + ":";
    for (String key : new HashSet<>(coordinatorStates.keySet())) {
      if (!key.startsWith(prefix) || key.equals(transactionKey(command, attempt))) {
        continue;
      }
      CoordinatorState state = coordinatorStates.get(key);
      if (state != null) {
        if (state.result == null) {
          sendAbort(state);
        }
        coordinatorStates.remove(key);
      }
    }
  }

  private void removeAllCoordinatorAttempts(AMOCommand command) {
    String prefix = transactionRequestKey(command) + ":";
    for (String key : new HashSet<>(coordinatorStates.keySet())) {
      if (key.startsWith(prefix)) {
        coordinatorStates.remove(key);
      }
    }
  }

  private String activeTransactionKey(AMOCommand command) {
    String prefix = transactionRequestKey(command) + ":";
    for (String key : coordinatorStates.keySet()) {
      CoordinatorState state = coordinatorStates.get(key);
      if (key.startsWith(prefix) && !(state.finalDecisionSent && state.result == null)) {
        return key;
      }
    }
    return null;
  }

  private void resendCoordinatorMessages(CoordinatorState state) {
    if (state == null || state.finalDecisionSent) {
      return;
    }
    Integer participantGroupId = nextPrepareGroup(state);
    if (participantGroupId == null) {
      return;
    }
    TxnPrepare prepare =
        new TxnPrepare(
            state.command, state.configNum, groupId, state.participantGroups, state.attempt);
    for (Address server : membersOfConfig(state.configNum, participantGroupId)) {
      send(prepare, server);
    }
  }

  private Integer nextPrepareGroup(CoordinatorState state) {
    Integer next = null;
    for (int participantGroupId : state.participantGroups) {
      if (!state.prepareReplies.containsKey(participantGroupId)
          && (next == null || participantGroupId < next)) {
        next = participantGroupId;
      }
    }
    return next;
  }

  private TxnPrepareReply prepareTransaction(PrepareTransaction pt) {
    String key = transactionKey(pt.command(), pt.attempt());
    if (abortedTransactions.contains(key)) {
      return new TxnPrepareReply(
          pt.command(), pt.configNum(), groupId, pt.attempt(), false, new HashMap<>());
    }
    if (committedTransactions.contains(key)) {
      return new TxnPrepareReply(
          pt.command(),
          pt.configNum(),
          groupId,
          pt.attempt(),
          true,
          new HashMap<>(committedTransactionValues.getOrDefault(key, new HashMap<>())));
    }

    PreparedState existing = preparedTransactions.get(key);
    if (existing != null) {
      return new TxnPrepareReply(
          pt.command(), pt.configNum(), groupId, pt.attempt(), true, existing.values);
    }

    if (targetConfig != null
        || currentConfig == null
        || pt.configNum() != currentConfig.configNum()
        || !pt.participantGroups().contains(groupId)) {
      return new TxnPrepareReply(
          pt.command(), pt.configNum(), groupId, pt.attempt(), false, new HashMap<>());
    }

    Transaction transaction = (Transaction) pt.command().command();
    Set<String> keys = localKeys(transaction);
    for (String localKey : keys) {
      String owner = keyLocks.get(localKey);
      if (owner != null && !owner.equals(key)) {
        if (!stealOlderAttemptLock(owner, pt.command())) {
          return new TxnPrepareReply(
              pt.command(), pt.configNum(), groupId, pt.attempt(), false, new HashMap<>());
        }
      }
      int shard = keyToShard(localKey);
      Integer shardOwner = ownerOf(currentConfig, shard);
      if (shardOwner == null
          || !shardOwner.equals(groupId)
          || shardStatus.get(shard) != ShardStatus.OWNED
          || !shardApps.containsKey(shard)) {
        return new TxnPrepareReply(
            pt.command(), pt.configNum(), groupId, pt.attempt(), false, new HashMap<>());
      }
    }

    Map<String, String> values = new HashMap<>();
    for (String localKey : keys) {
      keyLocks.put(localKey, key);
      TransactionalKVStore app = shardApps.get(keyToShard(localKey)).application();
      if (app.containsKey(localKey)) {
        values.put(localKey, app.get(localKey));
      }
    }
    preparedTransactions.put(key, new PreparedState(pt.coordinatorGroupId(), keys, values));
    return new TxnPrepareReply(pt.command(), pt.configNum(), groupId, pt.attempt(), true, values);
  }

  private boolean stealOlderAttemptLock(String owner, AMOCommand command) {
    String prefix = transactionRequestKey(command) + ":";
    if (!owner.startsWith(prefix)) {
      return false;
    }
    PreparedState old = preparedTransactions.remove(owner);
    if (old != null) {
      releaseLocks(owner, old.lockedKeys);
    } else {
      releaseAllLocksForTransaction(owner);
    }
    abortedTransactions.add(owner);
    return true;
  }

  private void recordPrepareReply(TxnPrepareReply reply) {
    String key = transactionKey(reply.command(), reply.attempt());
    CoordinatorState state = coordinatorStates.get(key);
    if (state == null
        || state.finalDecisionSent
        || state.result != null
        || state.configNum != reply.configNum()
        || !state.participantGroups.contains(reply.participantGroupId())) {
      return;
    }

    state.prepareReplies.put(reply.participantGroupId(), reply);
    if (!reply.ok()) {
      state.finalDecisionSent = true;
      sendAbort(state);
      send(new ShardStoreReply(null, bestKnownConfig(), true), state.command.address());
      return;
    }

    if (state.prepareReplies.keySet().containsAll(state.participantGroups)) {
      Map<String, String> transactionDb = new HashMap<>();
      for (TxnPrepareReply r : state.prepareReplies.values()) {
        transactionDb.putAll(r.values());
      }
      Transaction transaction = (Transaction) state.command.command();
      Result result = transaction.run(transactionDb);
      state.result = new AMOResult(result, state.command.address(), state.command.sequenceNum());
      state.finalDecisionSent = true;
      resendCommit(state);
    } else {
      resendCoordinatorMessages(state);
    }
  }

  private void commitTransaction(CommitTransaction ct) {
    String key = transactionKey(ct.command(), ct.attempt());
    if (committedTransactions.contains(key)) {
      sendCommitAck(ct);
      return;
    }

    PreparedState prepared = preparedTransactions.get(key);
    if (currentConfig == null || ct.configNum() != currentConfig.configNum()) {
      if (prepared != null) {
        abortTransaction(ct.command(), ct.configNum(), ct.coordinatorGroupId(), ct.attempt());
      } else {
        pendingCommits.remove(key);
      }
      return;
    }
    if (prepared == null) {
      if (!abortedTransactions.contains(key)) {
        pendingCommits.put(key, ct);
      }
      return;
    }

    Transaction transaction = (Transaction) ct.command().command();
    for (String localKey : localWriteKeys(transaction)) {
      TransactionalKVStore app = shardApps.get(keyToShard(localKey)).application();
      if (ct.writes().containsKey(localKey)) {
        app.put(localKey, ct.writes().get(localKey));
      } else {
        app.remove(localKey);
      }
    }
    releaseLocks(key, prepared.lockedKeys);
    preparedTransactions.remove(key);
    committedTransactions.add(key);
    committedTransactionValues.put(key, new HashMap<>(prepared.values));
    pendingCommits.remove(key);
    sendCommitAck(ct);
  }

  private void recordCommitAck(TxnCommitAck ack) {
    String key = transactionKey(ack.command(), ack.attempt());
    CoordinatorState state = coordinatorStates.get(key);
    if (state == null
        || state.result == null
        || state.configNum != ack.configNum()
        || !state.participantGroups.contains(ack.participantGroupId())) {
      return;
    }
    state.commitAcks.add(ack.participantGroupId());
    if (state.commitAcks.containsAll(state.participantGroups)) {
      lastTransactionSequenceNums.put(ack.command().address(), ack.command().sequenceNum());
      transactionResults.put(ack.command().address(), state.result);
      send(new ShardStoreReply(state.result, null, false), ack.command().address());
      removeAllCoordinatorAttempts(ack.command());
    }
  }

  private void abortTransaction(
      AMOCommand command, int configNum, int coordinatorGroupId, int attempt) {
    String key = transactionKey(command, attempt);
    if (committedTransactions.contains(key)) {
      TxnAbortAck ack = new TxnAbortAck(command, configNum, groupId, attempt);
      for (Address server : membersOfConfig(configNum, coordinatorGroupId)) {
        send(ack, server);
      }
      return;
    }
    pendingCommits.remove(key);
    PreparedState prepared = preparedTransactions.remove(key);
    abortedTransactions.add(key);
    if (prepared != null) {
      releaseLocks(key, prepared.lockedKeys);
    } else {
      releaseAllLocksForTransaction(key);
    }
    TxnAbortAck ack = new TxnAbortAck(command, configNum, groupId, attempt);
    for (Address server : membersOfConfig(configNum, coordinatorGroupId)) {
      send(ack, server);
    }
  }

  private void sendAbort(CoordinatorState state) {
    TxnAbort abort = new TxnAbort(state.command, state.configNum, groupId, state.attempt);
    for (int participantGroupId : state.participantGroups) {
      for (Address server : membersOfConfig(state.configNum, participantGroupId)) {
        send(abort, server);
      }
    }
  }

  private void resendCommit(CoordinatorState state) {
    Transaction transaction = (Transaction) state.command.command();
    Map<String, String> transactionDb = new HashMap<>();
    for (TxnPrepareReply r : state.prepareReplies.values()) {
      transactionDb.putAll(r.values());
    }
    transaction.run(transactionDb);
    Map<String, String> writes = new HashMap<>();
    for (String key : transaction.writeSet()) {
      if (transactionDb.containsKey(key)) {
        writes.put(key, transactionDb.get(key));
      }
    }
    TxnCommit commit =
        new TxnCommit(state.command, state.configNum, groupId, state.attempt, writes);
    for (int participantGroupId : state.participantGroups) {
      if (state.commitAcks.contains(participantGroupId)) {
        continue;
      }
      for (Address server : membersOfConfig(state.configNum, participantGroupId)) {
        send(commit, server);
      }
    }
  }

  private void recordAbortAck(TxnAbortAck ack) {
    String key = transactionKey(ack.command(), ack.attempt());
    CoordinatorState state = coordinatorStates.get(key);
    if (state == null
        || !state.finalDecisionSent
        || state.result != null
        || state.configNum != ack.configNum()
        || !state.participantGroups.contains(ack.participantGroupId())) {
      return;
    }
    state.abortAcks.add(ack.participantGroupId());
    if (state.abortAcks.containsAll(state.participantGroups)) {
      if (alreadyExecutedTransaction(ack.command())) {
        send(
            new ShardStoreReply(transactionResults.get(ack.command().address()), null, false),
            ack.command().address());
      } else {
        send(new ShardStoreReply(null, bestKnownConfig(), true), ack.command().address());
      }
      coordinatorStates.remove(key);
    }
  }

  private void sendCommitAck(CommitTransaction ct) {
    TxnCommitAck ack = new TxnCommitAck(ct.command(), ct.configNum(), groupId, ct.attempt());
    for (Address server : membersOfConfig(ct.configNum(), ct.coordinatorGroupId())) {
      send(ack, server);
    }
  }

  private void releaseLocks(String transactionKey, Set<String> keys) {
    for (String key : keys) {
      if (transactionKey.equals(keyLocks.get(key))) {
        keyLocks.remove(key);
      }
    }
  }

  private void releaseAllLocksForTransaction(String transactionKey) {
    for (String key : new HashSet<>(keyLocks.keySet())) {
      if (transactionKey.equals(keyLocks.get(key))) {
        keyLocks.remove(key);
      }
    }
  }

  private void completeConfig(int configNum) {
    if (targetConfig == null || targetConfig.configNum() != configNum) {
      return;
    }
    currentConfig = targetConfig;
    targetConfig = null;
    queryShardMasters(-1);
    set(new ConfigPollTimer(), ConfigPollTimer.CONFIG_POLL_MILLIS);
  }

  private Set<Address> membersOf(ShardConfig config, int gid) {
    if (config == null || !config.groupInfo().containsKey(gid)) {
      return new HashSet<>();
    }
    return new HashSet<>(config.groupInfo().get(gid).getLeft());
  }

  private ShardConfig bestKnownConfig() {
    return targetConfig != null ? targetConfig : currentConfig;
  }

  private Set<Address> membersOfConfig(int configNum, int gid) {
    if (currentConfig != null && currentConfig.configNum() == configNum) {
      return membersOf(currentConfig, gid);
    }
    if (targetConfig != null && targetConfig.configNum() == configNum) {
      return membersOf(targetConfig, gid);
    }
    return membersOf(currentConfig, gid);
  }
}
