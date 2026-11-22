package dev.oos.nativepanels;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class UpdateCheckDialog extends DialogFragment {
  public static final class Check extends ViewModel {
    final MutableLiveData<GitHubUpdates.Result> result = new MutableLiveData<>();
    private Thread worker;

    void start() {
      GitHubUpdates.Result previous = result.getValue();
      if (previous != null && previous.status() == GitHubUpdates.Status.CHECKING) return;
      result.setValue(new GitHubUpdates.Result(GitHubUpdates.Status.CHECKING, null));
      worker = new Thread(() -> result.postValue(GitHubUpdates.check(BuildConfig.VERSION_NAME)),
          "github-release-check");
      worker.start();
    }

    @Override protected void onCleared() {
      if (worker != null) worker.interrupt();
    }
  }

  private Check check;

  @Override public Dialog onCreateDialog(Bundle state) {
    check = new ViewModelProvider(this).get(Check.class);
    AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.check_updates)
        .setMessage(R.string.update_checking)
        .setNegativeButton(R.string.close, null)
        .setPositiveButton(R.string.update_view_release, null)
        .create();
    if (check.result.getValue() == null) check.start();
    check.result.observe(this, result -> render(dialog, result));
    return dialog;
  }

  @Override public void onStart() {
    super.onStart();
    render((AlertDialog) requireDialog(), check.result.getValue());
  }

  private void render(AlertDialog dialog, GitHubUpdates.Result result) {
    Button action = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
    if (action == null || result == null) return;
    action.setVisibility(View.GONE);
    switch (result.status()) {
      case AVAILABLE -> {
        dialog.setMessage(getString(R.string.update_available, result.release().version().name(),
            BuildConfig.VERSION_NAME));
        action.setText(R.string.update_view_release);
        action.setOnClickListener(v -> {
          try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(result.release().page())));
            dismiss();
          } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(requireContext(), R.string.update_no_browser, Toast.LENGTH_LONG).show();
          }
        });
        action.setVisibility(View.VISIBLE);
      }
      case FAILED, RATE_LIMIT -> {
        dialog.setMessage(getString(result.status() == GitHubUpdates.Status.RATE_LIMIT
            ? R.string.update_rate_limit : R.string.update_failed));
        action.setText(R.string.update_retry);
        action.setOnClickListener(v -> check.start());
        action.setVisibility(View.VISIBLE);
      }
      case CURRENT -> dialog.setMessage(getString(R.string.update_current, BuildConfig.VERSION_NAME));
      case EMPTY -> dialog.setMessage(getString(R.string.update_empty));
      case CHECKING -> dialog.setMessage(getString(R.string.update_checking));
    }
  }
}
