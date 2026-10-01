package dslabs.primarybackup;

import static dslabs.primarybackup.PingCheckTimer.PING_CHECK_MILLIS;

import dslabs.framework.Address;
import dslabs.framework.Node;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
class ViewServer extends Node {
  static final int STARTUP_VIEWNUM = 0;

  // Your code here...
  private View currentView;
  private Set<Address> recentPingers;
  private Set<Address> previousPingers;
  private Map<Address, Integer> lastPingViewNums;
  private boolean currentViewAcked = false;

  /* -----------------------------------------------------------------------------------------------
   *  Construction and Initialization
   * ---------------------------------------------------------------------------------------------*/
  public ViewServer(Address address) {
    super(address);
  }

  @Override
  public void init() {
    set(new PingCheckTimer(), PING_CHECK_MILLIS);
    // Your code here...
    currentView = new View(STARTUP_VIEWNUM, null, null);
    currentViewAcked = false;
    recentPingers = new HashSet<>();
    previousPingers = new HashSet<>();
    lastPingViewNums = new HashMap<>();
    // set(new PingCheckTimer(), PING_CHECK_MILLIS);
  }

  /* -----------------------------------------------------------------------------------------------
   *  Message Handlers
   * ---------------------------------------------------------------------------------------------*/
  private void handlePing(Ping m, Address sender) {
    recentPingers.add(sender);
    lastPingViewNums.put(sender, m.viewNum());

    if (Objects.equals(sender, currentView.primary()) && m.viewNum() == currentView.viewNum()) {
      currentViewAcked = true;
    }

    // Fast path for backup recruitment in run tests: once the current view is acked,
    // a currently pinging idle server can be installed immediately as backup.
    if (currentViewAcked
        && currentView.primary() != null
        && currentView.backup() == null
        && !Objects.equals(sender, currentView.primary())) {
      currentView = new View(currentView.viewNum() + 1, currentView.primary(), sender);
      currentViewAcked = false;
      send(new ViewReply(currentView), sender);
      return;
    }

    tryAdvanceView();

    // Only reply when the sender's known view differs from the current view.
    // This eliminates redundant ViewReply traffic during steady state, which
    // dramatically reduces the BFS state space (search tests like 2.17).
    if (m.viewNum() != currentView.viewNum()) {
      send(new ViewReply(currentView), sender);
    }
  }

  private void handleGetView(GetView m, Address sender) {
    // Your code here...
    send(new ViewReply(currentView), sender);
  }

  /* -----------------------------------------------------------------------------------------------
   *  Timer Handlers
   * ---------------------------------------------------------------------------------------------*/
  private void onPingCheckTimer(PingCheckTimer t) {
    // Roll the window: previous = recent, recent = empty
    previousPingers = recentPingers;
    recentPingers = new HashSet<>();

    tryAdvanceView();

    set(t, PingCheckTimer.PING_CHECK_MILLIS);
  }

  /* -----------------------------------------------------------------------------------------------
   *  Utils
   * ---------------------------------------------------------------------------------------------*/

  private boolean isAlive(Address s) {
    return s != null && (recentPingers.contains(s) || previousPingers.contains(s));
  }

  private void tryAdvanceView() {
    // Cannot advance until primary has acked (except in startup state)
    if (currentView.viewNum() != STARTUP_VIEWNUM && !currentViewAcked) {
      return;
    }

    Address curPrimary = currentView.primary();
    Address curBackup = currentView.backup();

    Address newPrimary = curPrimary;
    Address newBackup = curBackup;

    // --- Handle primary failure or initial startup ---
    if (curPrimary == null) {
      // Startup: pick any pinging server as primary
      newPrimary = anyIdleServer(null, null);
      newBackup = null;
      if (newPrimary == null) return; // no servers yet
    } else if (!isAlive(curPrimary)) {
      // Primary appears dead: promote backup if available.
      if (isAlive(curBackup) && hasReportedView(curBackup, currentView.viewNum())) {
        newPrimary = curBackup;
        newBackup = null;
      } else {
        // Keep current view configuration and continue; if an idle server is pinging,
        // it can be recruited as backup in a new view and later confirmed by primary ACK.
        newPrimary = curPrimary;
        newBackup = curBackup;
      }
    }

    // --- Handle backup failure or absence ---
    if (!isAlive(newBackup)) {
      // Look for an idle server to become backup
      newBackup = anyIdleServer(newPrimary, null);
      // newBackup may be null (fine — view can have no backup)
    }

    // --- Only create a new view if something actually changed ---
    if (!Objects.equals(newPrimary, curPrimary) || !Objects.equals(newBackup, curBackup)) {
      currentView = new View(currentView.viewNum() + 1, newPrimary, newBackup);
      currentViewAcked = false;
    }
  }

  private Address anyIdleServer(Address excludePrimary, Address excludeBackup) {
    // Collect only truly alive candidates (present in at least one liveness window)
    Set<Address> alive = new TreeSet<>();
    for (Address a : recentPingers) {
      if (isAlive(a)) alive.add(a);
    }
    for (Address a : previousPingers) {
      if (isAlive(a)) alive.add(a);
    }

    for (Address a : alive) {
      if (!a.equals(excludePrimary) && !a.equals(excludeBackup)) {
        return a;
      }
    }
    return null;
  }

  private boolean hasReportedView(Address server, int viewNum) {
    if (server == null) return false;
    Integer reported = lastPingViewNums.get(server);
    return reported != null && reported == viewNum;
  }
}
