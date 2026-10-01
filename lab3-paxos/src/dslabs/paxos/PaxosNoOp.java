package dslabs.paxos;

import dslabs.framework.Command;

/** Log hole-filler: handled by {@link PaxosNoOpApplication} without touching the real app. */
enum PaxosNoOp implements Command {
  INSTANCE;

  @Override
  public boolean readOnly() {
    return true;
  }
}
