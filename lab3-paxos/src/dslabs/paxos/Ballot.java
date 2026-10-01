package dslabs.paxos;

import dslabs.framework.Address;
import java.io.Serializable;
import lombok.Data;

@Data
final class Ballot implements Serializable, Comparable<Ballot> {
  private final int seqNum;
  private final Address address;

  @Override
  public int compareTo(Ballot other) {
    int cmp = Integer.compare(seqNum, other.seqNum);
    return cmp != 0 ? cmp : address.compareTo(other.address);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Ballot && compareTo((Ballot) other) == 0;
  }

  @Override
  public int hashCode() {
    return seqNum * 31 + address.hashCode();
  }
}
