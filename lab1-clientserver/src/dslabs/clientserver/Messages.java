package dslabs.clientserver;

import dslabs.atmostonce.AMOCommand;
import dslabs.atmostonce.AMOResult;
import dslabs.framework.Message;
import lombok.Data;

@Data
class Request implements Message {
  // Your code here...
  private final AMOCommand command;
}

@Data
class Reply implements Message {
  // Your code here...
  private final AMOResult result;
}
