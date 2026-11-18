package dev.oos.nativepanels;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class RootRestartTest {
  @Test
  public void sendsRestartOnlyAfterRootHandshake() throws Exception {
    FakeShell shell = new FakeShell("0", "OK");
    assertEquals(RootRestart.Result.REQUESTED, RootRestart.run(shell, 1000, 1000));
    assertTrue(shell.commands().contains("/system/bin/killall -TERM com.android.systemui"));
    assertEquals(1, shell.restartRequests);
    assertTrue(shell.destroyed);
  }

  @Test
  public void denialDoesNotQueueDestructiveCommand() throws Exception {
    FakeShell shell = new FakeShell("2000", "OK");
    assertEquals(RootRestart.Result.DENIED, RootRestart.run(shell, 1000, 1000));
    assertFalse(shell.commands().contains("killall"));
    assertEquals(0, shell.restartRequests);
  }

  @Test
  public void timedOutAuthorizationCannotRunRestartLater() throws Exception {
    FakeShell shell = new FakeShell(null, "OK");
    assertEquals(RootRestart.Result.TIMEOUT, RootRestart.run(shell, 25, 1000));
    assertFalse(shell.commands().contains("killall"));
    assertTrue(shell.destroyed);
  }

  @Test
  public void missingSystemUiIsReportedWithoutRetry() throws Exception {
    FakeShell shell = new FakeShell("0", "NOT_RUNNING");
    assertEquals(RootRestart.Result.NOT_RUNNING, RootRestart.run(shell, 1000, 1000));
    assertEquals(1, shell.restartRequests);
  }

  @Test
  public void permissionRestartTargetsOnlyTheGoogleControllerWithoutForceStoppingIt() throws Exception {
    FakeShell shell = new FakeShell("0", "OK");
    assertEquals(RootRestart.Result.REQUESTED,
        RootRestart.runPermissionController(shell, 1000, 1000, () -> true));
    String command = shell.commands();
    assertTrue(command.contains("/system/bin/pidof com.google.android.permissioncontroller"));
    assertTrue(command.contains("/system/bin/killall -TERM com.google.android.permissioncontroller"));
    assertFalse(command.contains("com.android.systemui"));
    assertFalse(command.contains("com.android.permissioncontroller"));
    assertFalse(command.contains("force-stop"));
    assertFalse(command.contains("reboot"));
    assertFalse(command.contains("pm grant"));
    assertFalse(command.contains("pm revoke"));
    assertEquals(1, shell.restartRequests);
    assertTrue(shell.destroyed);
  }

  @Test
  public void permissionRestartDenialDoesNotSendAnyTargetCommand() throws Exception {
    FakeShell shell = new FakeShell("2000", "OK");
    assertEquals(RootRestart.Result.DENIED,
        RootRestart.runPermissionController(shell, 1000, 1000, () -> true));
    assertFalse(shell.commands().contains("permissioncontroller"));
    assertFalse(shell.commands().contains("killall"));
    assertEquals(0, shell.restartRequests);
    assertTrue(shell.destroyed);
  }

  @Test
  public void permissionRestartAuthorizationTimeoutCannotQueueALaterRestart() throws Exception {
    FakeShell shell = new FakeShell(null, "OK");
    assertEquals(RootRestart.Result.TIMEOUT,
        RootRestart.runPermissionController(shell, 25, 1000, () -> true));
    assertFalse(shell.commands().contains("permissioncontroller"));
    assertFalse(shell.commands().contains("killall"));
    assertEquals(0, shell.restartRequests);
    assertTrue(shell.destroyed);
  }

  @Test
  public void permissionRestartRechecksCancellationAfterRootApproval() throws Exception {
    AtomicBoolean allowed = new AtomicBoolean(true);
    FakeShell shell = new FakeShell("0", "OK");
    shell.beforeAuthorizationReply = () -> allowed.set(false);
    assertEquals(RootRestart.Result.FAILED,
        RootRestart.runPermissionController(shell, 1000, 1000, allowed::get));
    assertTrue(shell.probed);
    assertFalse(shell.commands().contains("permissioncontroller"));
    assertFalse(shell.commands().contains("killall"));
    assertEquals(0, shell.restartRequests);
    assertTrue(shell.destroyed);
  }

  @Test
  public void alreadyCancelledPermissionRestartDoesNotEvenSendTheRootProbe() throws Exception {
    FakeShell shell = new FakeShell("0", "OK");
    assertEquals(RootRestart.Result.FAILED,
        RootRestart.runPermissionController(shell, 1000, 1000, () -> false));
    assertEquals("", shell.commands());
    assertFalse(shell.probed);
    assertEquals(0, shell.restartRequests);
    assertTrue(shell.destroyed);
  }

  @Test
  public void permissionRestartCommandTimeoutEndsTheShellWithoutRetry() throws Exception {
    FakeShell shell = new FakeShell("0", null);
    assertEquals(RootRestart.Result.TIMEOUT,
        RootRestart.runPermissionController(shell, 1000, 25, () -> true));
    assertEquals(1, shell.restartRequests);
    assertTrue(shell.destroyed);
  }

  @Test
  public void permissionControllerAlreadyStoppedIsReportedWithoutRetry() throws Exception {
    FakeShell shell = new FakeShell("0", "NOT_RUNNING");
    assertEquals(RootRestart.Result.NOT_RUNNING,
        RootRestart.runPermissionController(shell, 1000, 1000, () -> true));
    assertEquals(1, shell.restartRequests);
    assertTrue(shell.destroyed);
  }

  @Test
  public void failedPermissionRestartDoesNotReportSuccessOrRetry() throws Exception {
    FakeShell shell = new FakeShell("0", "FAILED");
    assertEquals(RootRestart.Result.FAILED,
        RootRestart.runPermissionController(shell, 1000, 1000, () -> true));
    assertEquals(1, shell.restartRequests);
    assertTrue(shell.destroyed);
  }

  @Test
  public void bluetoothSettingsRestartTargetsOnlyTheTwoSettingsHosts() throws Exception {
    FakeShell shell = new FakeShell("0", "OK");
    assertEquals(RootRestart.Result.REQUESTED,
        RootRestart.runBluetoothSettings(shell, 1000, 1000, () -> true));
    String command = shell.commands();
    assertTrue(command.contains("for oosnp_target in com.android.settings com.oplus.wirelesssettings; do"));
    assertTrue(command.contains("/system/bin/pidof \"$oosnp_target\""));
    assertTrue(command.contains("/system/bin/killall -TERM \"$oosnp_target\""));
    assertFalse(command.contains("com.android.bluetooth"));
    assertFalse(command.contains("com.android.systemui"));
    assertFalse(command.contains("permissioncontroller"));
    assertFalse(command.contains("force-stop"));
    assertFalse(command.contains("reboot"));
    assertEquals(1, shell.restartRequests);
    assertTrue(shell.destroyed);
  }

  @Test
  public void bluetoothSettingsRestartDenialNeverSendsTheProcessList() throws Exception {
    FakeShell shell = new FakeShell("2000", "OK");
    assertEquals(RootRestart.Result.DENIED,
        RootRestart.runBluetoothSettings(shell, 1000, 1000, () -> true));
    assertFalse(shell.commands().contains("com.android.settings"));
    assertFalse(shell.commands().contains("killall"));
    assertEquals(0, shell.restartRequests);
  }

  @Test
  public void bluetoothSettingsRestartCancelledDuringRootApprovalCannotRunLater() throws Exception {
    AtomicBoolean allowed = new AtomicBoolean(true);
    FakeShell shell = new FakeShell("0", "OK");
    shell.beforeAuthorizationReply = () -> allowed.set(false);
    assertEquals(RootRestart.Result.FAILED,
        RootRestart.runBluetoothSettings(shell, 1000, 1000, allowed::get));
    assertTrue(shell.probed);
    assertFalse(shell.commands().contains("com.android.settings"));
    assertFalse(shell.commands().contains("killall"));
    assertEquals(0, shell.restartRequests);
    assertTrue(shell.destroyed);
  }

  @Test
  public void bluetoothSettingsAlreadyStoppedDoesNotCauseASecondAttempt() throws Exception {
    FakeShell shell = new FakeShell("0", "NOT_RUNNING");
    assertEquals(RootRestart.Result.NOT_RUNNING,
        RootRestart.runBluetoothSettings(shell, 1000, 1000, () -> true));
    assertEquals(1, shell.restartRequests);
    assertTrue(shell.destroyed);
  }

  /** Simulates root-manager I/O. Never launches su or evaluates the shell payload. */
  private static final class FakeShell extends Process {
    final PipedInputStream input = new PipedInputStream();
    final PipedOutputStream replies = new PipedOutputStream(input);
    final ByteArrayOutputStream received = new ByteArrayOutputStream();
    final String uid, result;
    boolean probed, destroyed;
    int restartRequests;
    Runnable beforeAuthorizationReply = () -> {};
    final OutputStream output =
        new OutputStream() {
          @Override
          public void write(int b) {
            received.write(b);
          }

          @Override
          public void flush() throws IOException {
            if (!probed && commands().contains("OOSNP_UID=")) {
              probed = true;
              beforeAuthorizationReply.run();
              if (uid != null) reply("OOSNP_UID=" + uid);
            } else if (commands().contains("killall") && restartRequests == 0) {
              restartRequests++;
              if (result != null) reply("OOSNP_RESTART=" + result);
            }
          }
        };

    FakeShell(String uid, String result) throws IOException {
      this.uid = uid;
      this.result = result;
    }

    String commands() {
      return new String(received.toByteArray(), StandardCharsets.UTF_8);
    }

    void reply(String text) throws IOException {
      replies.write((text + "\n").getBytes(StandardCharsets.UTF_8));
      replies.flush();
    }

    @Override
    public OutputStream getOutputStream() {
      return output;
    }

    @Override
    public InputStream getInputStream() {
      return input;
    }

    @Override
    public InputStream getErrorStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public boolean isAlive() {
      return !destroyed;
    }

    @Override
    public void destroy() {
      destroyed = true;
      try {
        replies.close();
      } catch (IOException ignored) {
      }
    }

    @Override
    public Process destroyForcibly() {
      destroy();
      return this;
    }
  }
}
