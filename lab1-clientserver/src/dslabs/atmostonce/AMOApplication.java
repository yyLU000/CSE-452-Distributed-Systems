package dslabs.atmostonce;

import dslabs.framework.Address;
import dslabs.framework.Application;
import dslabs.framework.Command;
import dslabs.framework.Result;
import java.util.HashMap;
import java.util.Map;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

@EqualsAndHashCode
@ToString
@RequiredArgsConstructor
public final class AMOApplication<T extends Application> implements Application {
  @Getter @NonNull private final T application;

  // Your code here...
  private Map<Address, Integer> lastSequenceNums = new HashMap<>();
  private Map<Address, AMOResult> executedResults = new HashMap<>();

  @Override
  public AMOResult execute(Command command) {
    if (!(command instanceof AMOCommand)) {
      throw new IllegalArgumentException();
    }

    // Your code here...
    AMOCommand amoCommand = (AMOCommand) command;
    Address client = amoCommand.address();

    if (alreadyExecuted(amoCommand)) {
      return executedResults.get(client);
    }

    Result result = application.execute(amoCommand.command());
    AMOResult amoResult = new AMOResult(result, client, amoCommand.sequenceNum());

    lastSequenceNums.put(client, amoCommand.sequenceNum());
    executedResults.put(client, amoResult);
    return amoResult;
  }

  public Result executeReadOnly(Command command) {
    if (!command.readOnly()) {
      throw new IllegalArgumentException();
    }

    if (command instanceof AMOCommand) {
      return execute(command);
    }

    return application.execute(command);
  }

  public boolean alreadyExecuted(AMOCommand amoCommand) {
    // Your code here...
    Integer lastSequenceNum = lastSequenceNums.get(amoCommand.address());
    return lastSequenceNum != null && lastSequenceNum >= amoCommand.sequenceNum();
  }
}
