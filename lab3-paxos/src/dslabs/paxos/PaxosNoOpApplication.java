package dslabs.paxos;

import dslabs.framework.Application;
import dslabs.framework.Command;
import dslabs.framework.Result;
import lombok.RequiredArgsConstructor;

/** Delegates to the user {@link Application}, but handles {@link PaxosNoOp} without forwarding. */
@RequiredArgsConstructor
final class PaxosNoOpApplication implements Application {
  private final Application delegate;

  @Override
  public Result execute(Command command) {
    if (command == PaxosNoOp.INSTANCE) {
      return PaxosNoOpResult.INSTANCE;
    }
    return delegate.execute(command);
  }
}
