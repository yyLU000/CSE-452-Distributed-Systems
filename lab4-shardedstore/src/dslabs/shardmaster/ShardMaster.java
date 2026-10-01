package dslabs.shardmaster;

import dslabs.framework.Address;
import dslabs.framework.Application;
import dslabs.framework.Command;
import dslabs.framework.Result;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.apache.commons.lang3.tuple.Pair;

@ToString
@EqualsAndHashCode
public final class ShardMaster implements Application {
  public static final int INITIAL_CONFIG_NUM = 0;

  private final int numShards;

  // Your code here...
  ShardConfig currentConfig = null;
  Map<Integer, ShardConfig> configs = new HashMap<>();

  public ShardMaster(int numShards) {
    this.numShards = numShards;
  }

  public interface ShardMasterCommand extends Command {}

  @Data
  public static final class Join implements ShardMasterCommand {
    private final int groupId;
    private final Set<Address> servers;
  }

  @Data
  public static final class Leave implements ShardMasterCommand {
    private final int groupId;
  }

  @Data
  public static final class Move implements ShardMasterCommand {
    private final int groupId;
    private final int shardNum;
  }

  @Data
  public static final class Query implements ShardMasterCommand {
    private final int configNum;

    @Override
    public boolean readOnly() {
      return true;
    }
  }

  public interface ShardMasterResult extends Result {}

  @Data
  public static final class Ok implements ShardMasterResult {}

  @Data
  public static final class Error implements ShardMasterResult {}

  @Data
  public static final class ShardConfig implements ShardMasterResult {
    private final int configNum;

    // groupId -> <group members, shard numbers>
    private final Map<Integer, Pair<Set<Address>, Set<Integer>>> groupInfo;
  }

  @Override
  public Result execute(Command command) {
    if (command instanceof Join) {
      Join join = (Join) command;

      // Your code here...
      if (currentConfig != null && currentConfig.groupInfo.containsKey(join.groupId())) {
        return new Error();
      }
      Map<Integer, Pair<Set<Address>, Set<Integer>>> nextGroupInfo = copyGroupInfo();
      nextGroupInfo.put(join.groupId(), Pair.of(new HashSet<>(join.servers()), new HashSet<>()));

      if (currentConfig == null) {
        Set<Integer> allShards = nextGroupInfo.get(join.groupId()).getRight();
        for (int shard = 1; shard <= numShards; shard++) {
          allShards.add(shard);
        }
        currentConfig = new ShardConfig(INITIAL_CONFIG_NUM, nextGroupInfo);
        configs.put(currentConfig.configNum(), currentConfig);
      } else {
        rebalance(nextGroupInfo, new HashSet<>(), join.groupId());
        currentConfig = new ShardConfig(currentConfig.configNum() + 1, nextGroupInfo);
        configs.put(currentConfig.configNum(), currentConfig);
      }
      return new Ok();
    }

    if (command instanceof Leave) {
      Leave leave = (Leave) command;

      // Your code here...
      if (currentConfig == null
          || !currentConfig.groupInfo.containsKey(leave.groupId())
          || currentConfig.groupInfo.size() == 1) {
        return new Error();
      }
      Map<Integer, Pair<Set<Address>, Set<Integer>>> nextGroupInfo = copyGroupInfo();
      Set<Integer> shardsToReassign = new HashSet<>(nextGroupInfo.get(leave.groupId()).getRight());
      nextGroupInfo.remove(leave.groupId());
      rebalance(nextGroupInfo, shardsToReassign, null);
      currentConfig = new ShardConfig(currentConfig.configNum() + 1, nextGroupInfo);
      configs.put(currentConfig.configNum(), currentConfig);
      return new Ok();
    }

    if (command instanceof Move) {
      Move move = (Move) command;

      // Your code here...
      if (currentConfig == null || !currentConfig.groupInfo.containsKey(move.groupId()))
        return new Error();
      Integer currentGroupId = null;
      for (Map.Entry<Integer, Pair<Set<Address>, Set<Integer>>> entry :
          currentConfig.groupInfo().entrySet()) {
        if (entry.getValue().getRight().contains(move.shardNum())) {
          currentGroupId = entry.getKey();
          break;
        }
      }
      if (currentGroupId == null || currentGroupId.equals(move.groupId())) return new Error();

      Map<Integer, Pair<Set<Address>, Set<Integer>>> nextGroupInfo = copyGroupInfo();
      nextGroupInfo.get(currentGroupId).getRight().remove(move.shardNum());
      nextGroupInfo.get(move.groupId()).getRight().add(move.shardNum());

      currentConfig = new ShardConfig(currentConfig.configNum() + 1, nextGroupInfo);
      configs.put(currentConfig.configNum(), currentConfig);
      return new Ok();
    }

    if (command instanceof Query) {
      Query query = (Query) command;

      // Your code here...
      if (currentConfig == null) {
        return new Error();
      }
      if (query.configNum() == -1 || query.configNum() > currentConfig.configNum()) {
        return currentConfig;
      }
      if (configs.containsKey(query.configNum())) {
        return configs.get(query.configNum());
      }
      return new Error();
    }

    throw new IllegalArgumentException();
  }

  private Map<Integer, Pair<Set<Address>, Set<Integer>>> copyGroupInfo() {
    Map<Integer, Pair<Set<Address>, Set<Integer>>> copy = new HashMap<>();
    if (currentConfig == null) {
      return copy;
    }

    for (Map.Entry<Integer, Pair<Set<Address>, Set<Integer>>> entry :
        currentConfig.groupInfo().entrySet()) {
      copy.put(
          entry.getKey(),
          Pair.of(
              new HashSet<>(entry.getValue().getLeft()),
              new HashSet<>(entry.getValue().getRight())));
    }
    return copy;
  }

  private void rebalance(
      Map<Integer, Pair<Set<Address>, Set<Integer>>> groupInfo,
      Set<Integer> unassignedShards,
      Integer groupWithoutExtraShard) {

    int groupCount = groupInfo.size();
    int minShards = numShards / groupCount;
    int extraShardGroups = numShards % groupCount;

    // reassign shards to each group
    Map<Integer, Integer> targetSizes = new HashMap<>();
    List<Integer> groupIds = new ArrayList<>(groupInfo.keySet());
    groupIds.sort(
        Comparator.comparingInt(
                (Integer gid) ->
                    gid.equals(groupWithoutExtraShard)
                        ? Integer.MIN_VALUE
                        : groupInfo.get(gid).getRight().size())
            .reversed()
            .thenComparingInt(Integer::intValue));
    for (Integer gid : groupIds) {
      int targetSize = minShards;
      if (!gid.equals(groupWithoutExtraShard) && extraShardGroups > 0) {
        targetSize++;
        extraShardGroups--;
      }
      targetSizes.put(gid, targetSize);
    }

    List<Integer> receivers = new ArrayList<>();
    List<Integer> donors = new ArrayList<>();

    for (Integer gid : groupInfo.keySet()) {
      int size = groupInfo.get(gid).getRight().size();
      int target = targetSizes.get(gid);

      if (size < target) {
        receivers.add(gid);
      } else if (size > target) {
        donors.add(gid);
      }
    }
    receivers.sort(Integer::compareTo);
    donors.sort(
        Comparator.comparingInt(
                (Integer gid) -> groupInfo.get(gid).getRight().size() - targetSizes.get(gid))
            .reversed()
            .thenComparingInt(Integer::intValue));

    int receiverIndex = 0;
    int donorIndex = 0;
    while (receiverIndex < receivers.size()) {
      Integer receiver = receivers.get(receiverIndex);
      Integer shardToMove;
      if (!unassignedShards.isEmpty()) {
        shardToMove = smallestShard(unassignedShards);
        unassignedShards.remove(shardToMove);
      } else {
        if (donorIndex >= donors.size()) {
          return;
        }
        Integer donor = donors.get(donorIndex);
        shardToMove = smallestShard(groupInfo.get(donor).getRight());
        groupInfo.get(donor).getRight().remove(shardToMove);

        if (groupInfo.get(donor).getRight().size() == targetSizes.get(donor)) {
          donorIndex++;
        }
      }
      groupInfo.get(receiver).getRight().add(shardToMove);

      if (groupInfo.get(receiver).getRight().size() == targetSizes.get(receiver)) {
        receiverIndex++;
      }
    }
  }

  private Integer smallestShard(Set<Integer> shards) {
    return shards.stream().min(Comparator.comparingInt(Integer::intValue)).orElseThrow();
  }
}
