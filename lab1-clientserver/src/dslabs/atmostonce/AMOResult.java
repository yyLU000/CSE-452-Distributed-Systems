package dslabs.atmostonce;

import dslabs.framework.Address;
import dslabs.framework.Result;
import lombok.Data;
import lombok.NonNull;

@Data
public final class AMOResult implements Result {
  // Your code here...
  @NonNull private final Result result;
  @NonNull private final Address address;
  private final int sequenceNum;
}
