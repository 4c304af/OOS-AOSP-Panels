package dev.oos.nativepanels;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
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

  /** Simulates root-manager I/O. Never launches su or evaluates the shell payload. */
  private static final class FakeShell extends Process {
    final PipedInputStream input = new PipedInputStream();
    final PipedOutputStream replies = new PipedOutputStream(input);
    final ByteArrayOutputStream received = new ByteArrayOutputStream();
    final String uid, result;
    boolean probed, destroyed;
    int restartRequests;
    final OutputStream output =
        new OutputStream() {
          @Override
          public void write(int b) {
            received.write(b);
          }

          @Override
          public void flush() throws IOException {
            if (!probed) {
              probed = true;
              if (uid != null) reply("OOSNP_UID=" + uid);
            } else if (commands().contains("killall") && restartRequests == 0) {
              restartRequests++;
              reply("OOSNP_RESTART=" + result);
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
