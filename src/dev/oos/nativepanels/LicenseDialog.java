package dev.oos.nativepanels;

import android.app.Dialog;
import android.os.Bundle;
import androidx.fragment.app.DialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class LicenseDialog extends DialogFragment {
  private static final String FULL = "full";

  @Override public Dialog onCreateDialog(Bundle state) {
    MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.license_title)
        .setNegativeButton(R.string.close, null);
    if (getArguments() != null && getArguments().getBoolean(FULL)) {
      dialog.setMessage(fullText());
    } else {
      dialog.setMessage(R.string.license_summary).setPositiveButton(R.string.license_full, (d, which) -> {
        Bundle args = new Bundle();
        args.putBoolean(FULL, true);
        LicenseDialog full = new LicenseDialog();
        full.setArguments(args);
        full.show(getParentFragmentManager(), "license-full");
      });
    }
    return dialog.create();
  }

  private String fullText() {
    try (InputStream stream = LicenseDialog.class.getResourceAsStream("/META-INF/dev.oos.nativepanels/LICENSE")) {
      if (stream != null) {
        InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[4096];
        int count;
        while ((count = reader.read(buffer)) != -1) text.append(buffer, 0, count);
        return text.toString();
      }
    } catch (IOException unavailable) {
      // The summary is still accessible if the installed archive cannot be read.
    }
    return getString(R.string.license_unavailable);
  }
}
