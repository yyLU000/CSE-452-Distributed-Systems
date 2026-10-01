package dslabs.primarybackup;

import dslabs.atmostonce.AMOApplication;
import dslabs.atmostonce.AMOCommand;
import dslabs.atmostonce.AMOResult;
import dslabs.framework.Application;
import dslabs.framework.Message;
import lombok.Data;

/* -----------------------------------------------------------------------------------------------
 *  ViewServer Messages
 * ---------------------------------------------------------------------------------------------*/
@Data
class Ping implements Message {
  private final int viewNum;
}

@Data
class GetView implements Message {}

@Data
class ViewReply implements Message {
  private final View view;
}

/* -----------------------------------------------------------------------------------------------
 *  Primary-Backup Messages
 * ---------------------------------------------------------------------------------------------*/
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

// Your code here...
@Data
class ForwardRequest implements Message {
  private final AMOCommand command;
}

@Data
class ForwardAck implements Message {
  private final AMOResult result;
}

@Data
class StateTransfer implements Message {
  private final View view;
  private final AMOApplication<Application> app;
}

@Data
class StateTransferAck implements Message {
  private final View view;
}
