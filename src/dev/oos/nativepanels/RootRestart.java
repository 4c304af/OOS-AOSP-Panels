package dev.oos.nativepanels;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** A one-shot interactive root shell; no destructive command is sent until UID 0 is confirmed. */
final class RootRestart {
  enum Result {
    REQUESTED,
    DENIED,
    TIMEOUT,
    NOT_RUNNING,
    FAILED
  }

  private static final String PROBE = "printf '\\nOOSNP_UID=%s\\n' \"$(/system/bin/id -u)\"\n";
  private static final String RESTART =
      "if ! /system/bin/pidof com.android.systemui >/dev/null; then\n"
          + "  echo OOSNP_RESTART=NOT_RUNNING\n"
          + "  exit 11\n"
          + "fi\n"
          + "if /system/bin/killall -TERM com.android.systemui; then\n"
          + "  echo OOSNP_RESTART=OK\n"
          + "  exit 0\n"
          + "fi\n"
          + "echo OOSNP_RESTART=FAILED\n"
          + "exit 12\n";
  private static final String PERMISSION_RESTART =
      "if ! /system/bin/pidof com.google.android.permissioncontroller >/dev/null; then\n"
          + "  echo OOSNP_RESTART=NOT_RUNNING\n"
          + "  exit 11\n"
          + "fi\n"
          + "if /system/bin/killall -TERM com.google.android.permissioncontroller; then\n"
          + "  echo OOSNP_RESTART=OK\n"
          + "  exit 0\n"
          + "fi\n"
          + "echo OOSNP_RESTART=FAILED\n"
          + "exit 12\n";

  private RootRestart() {}

  static Result restart() throws IOException {
    return run(new ProcessBuilder("su").redirectErrorStream(true).start(), 60_000, 10_000);
  }

  /** Ends the controller's main process; Android starts it for the next permission request. */
  static Result restartPermissionController(BooleanSupplier allowed) throws IOException {
    if (!allowed.getAsBoolean()) return Result.FAILED;
    return runPermissionController(
        new ProcessBuilder("su").redirectErrorStream(true).start(), 60_000, 10_000, allowed);
  }

  static Result run(Process process, long authTimeoutMs, long commandTimeoutMs) {
    return run(process, authTimeoutMs, commandTimeoutMs, RESTART, () -> true);
  }

  static Result runPermissionController(Process process, long authTimeoutMs,
      long commandTimeoutMs, BooleanSupplier allowed) {
    return run(process, authTimeoutMs, commandTimeoutMs, PERMISSION_RESTART, allowed);
  }

  private static Result run(Process process, long authTimeoutMs, long commandTimeoutMs,
      String command, BooleanSupplier allowed) {
    CompletableFuture<String> uid = new CompletableFuture<>();
    CompletableFuture<String> outcome = new CompletableFuture<>();
    Thread reader =
        new Thread(
            () -> {
              try (BufferedReader in =
                  new BufferedReader(
                      new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                  if (line.startsWith("OOSNP_UID=")) uid.complete(line.substring(10).trim());
                  else if (line.startsWith("OOSNP_RESTART="))
                    outcome.complete(line.substring(14).trim());
                }
              } catch (IOException ignored) {
              } finally {
                uid.complete("");
                outcome.complete("FAILED");
              }
            },
            "RootRestart-output");
    reader.setDaemon(true);
    reader.start();
    try (Writer out = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
      if (!allowed.getAsBoolean()) return Result.FAILED;
      out.write(PROBE);
      out.flush();
      if (!"0".equals(uid.get(authTimeoutMs, TimeUnit.MILLISECONDS))) return Result.DENIED;
      // The user may turn the feature off while the root manager is asking for approval.
      if (!allowed.getAsBoolean()) return Result.FAILED;
      out.write(command);
      out.flush();
      String status = outcome.get(commandTimeoutMs, TimeUnit.MILLISECONDS);
      if ("OK".equals(status)) return Result.REQUESTED;
      if ("NOT_RUNNING".equals(status)) return Result.NOT_RUNNING;
      return Result.FAILED;
    } catch (TimeoutException e) {
      return Result.TIMEOUT;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Result.FAILED;
    } catch (IOException | ExecutionException e) {
      return Result.FAILED;
    } finally {
      process.destroy();
      if (process.isAlive()) process.destroyForcibly();
      reader.interrupt();
    }
  }
}
