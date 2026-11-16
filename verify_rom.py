#!/usr/bin/env python3
"""Check reflection boundaries against a supplied decompiled ROM, not just Java compilation."""
from pathlib import Path
import re
import sys

base = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent/'analysis'
count = 0
def source(cls, root='systemui'):
    paths = list((base/root).glob('smali*/'+cls.replace('.', '/')+'.smali'))
    assert len(paths) == 1, (cls, paths)
    return paths[0].read_text()

def method(cls, signature, root='systemui'):
    global count
    text = source(cls, root)
    assert any(line.startswith('.method ') and line.endswith(signature) for line in text.splitlines()), (cls, signature)
    count += 1

def field(cls, name):
    global count
    assert re.search(r'^\.field .*\b'+re.escape(name)+r':', source(cls), re.M), (cls, name)
    count += 1

graph = 'com.oplus.systemui.dagger.DaggerOplusReferenceGlobalRootComponent$OplusReferenceSysUIComponentImpl'
for f in ['provideAccessPointControllerImplProvider', 'internetDialogManagerProvider', 'bluetoothControllerImplProvider', 'bluetoothDetailsContentViewModelProvider', 'provideUserTrackerProvider', 'mediaOutputDialogManagerProvider']:
    field(graph, f)
method('com.android.systemui.application.impl.SystemUIApplicationImpl', 'onCreate()V')
field('com.android.systemui.application.impl.SystemUIApplicationImpl', 'mSysUIComponent')
method('com.oplus.systemui.qs.tiles.OplusWifiTile', 'showOplusQSDetailDialogProvider(Lcom/android/systemui/animation/Expandable;)V')
method('com.oplus.systemui.qs.detail.interactor.OplusQSDetailInteractor', 'show(Landroid/view/View;)Z')
field('com.oplus.systemui.qs.detail.interactor.OplusQSDetailInteractor', 'adapter')
for adapter in ['OplusBluetoothTile$ColorBluetoothDetailAdapter', 'OplusCellularTile$4']:
    source('com.oplus.systemui.qs.tiles.'+adapter); count += 1
for m in ['canConfigWifi()Z', 'canConfigMobileData()Z']:
    method('com.android.systemui.statusbar.connectivity.AccessPointControllerImpl', m)
method('com.android.systemui.qs.tiles.dialog.InternetDialogManager', 'create(ZZZLcom/android/systemui/animation/Expandable;)V')
method('com.android.systemui.qs.tiles.dialog.InternetDialogManager', 'destroyDialog()V')
field('com.android.systemui.qs.tiles.dialog.InternetDialogManager', 'dialog')
method('com.android.systemui.statusbar.policy.BluetoothControllerImpl', 'canConfigBluetooth()Z')
bt = 'com.android.systemui.bluetooth.ui.viewModel.BluetoothDetailsContentViewModel'
method(bt, 'showDialog(Lcom/android/systemui/animation/Expandable;)V')
method(bt, 'cancelJob()V')
java = (Path(__file__).parent/'src/dev/oos/nativepanels/SafeScope.java').read_text()
fields = re.findall(r'"([a-zA-Z]+)"', java.split('private static final String[] FIELDS = {')[1].split('};')[0])
assert len(fields) == 16
for f in fields: field(bt, f)
for name, sig in [('getUserHandle','()Landroid/os/UserHandle;')]:
    method('com.android.systemui.settings.UserTrackerImpl', name+sig)
media = 'com.android.systemui.media.dialog.MediaOutputDialogManager'
method(media, 'create(Ljava/lang/String;ZLandroid/view/View;ZZZLandroid/media/session/MediaSession$Token;Z)Landroid/view/View;')
method(media, 'createAndShow(Landroid/os/UserHandle;Ljava/lang/String;ZLcom/android/systemui/animation/DialogTransitionAnimator$Controller;ZLandroid/media/session/MediaSession$Token;Lcom/android/systemui/media/dialog/MediaSwitchingType;Z)V')
method(media, 'dismiss()V')
field('com.android.systemui.media.dialog.MediaSwitchingType', 'OUTPUT')
method('android.media.session.MediaSessionManager', 'getActiveSessionsForUser(Landroid/content/ComponentName;Landroid/os/UserHandle;)Ljava/util/List;', 'framework')
method('kotlinx.coroutines.SupervisorKt', 'SupervisorJob(Lkotlinx/coroutines/Job;)Lkotlinx/coroutines/CompletableJob;')
method('kotlinx.coroutines.CoroutineScopeKt', 'CoroutineScope(Lkotlin/coroutines/CoroutineContext;)Lkotlinx/coroutines/CoroutineScope;')
for cls in ['kotlinx.coroutines.Job', 'kotlinx.coroutines.CoroutineExceptionHandler']: field(cls, 'Key')
for sig in ['fold(Lkotlin/coroutines/CoroutineContext$Element;Ljava/lang/Object;Lkotlin/jvm/functions/Function2;)Ljava/lang/Object;', 'get(Lkotlin/coroutines/CoroutineContext$Element;Lkotlin/coroutines/CoroutineContext$Key;)Lkotlin/coroutines/CoroutineContext$Element;', 'minusKey(Lkotlin/coroutines/CoroutineContext$Element;Lkotlin/coroutines/CoroutineContext$Key;)Lkotlin/coroutines/CoroutineContext;', 'plus(Lkotlin/coroutines/CoroutineContext$Element;Lkotlin/coroutines/CoroutineContext;)Lkotlin/coroutines/CoroutineContext;']:
    method('kotlin.coroutines.CoroutineContext$Element$DefaultImpls', sig)
detail='com.android.systemui.qs.tiles.dialog.InternetDetailsContentController'
method(detail,'onStart(Lcom/android/systemui/qs/tiles/dialog/InternetDetailsContentController$InternetDialogCallback;ZLkotlinx/coroutines/CoroutineScope;)V')
method(detail,'onAccessPointsChanged(Ljava/util/List;)V')
method(detail,'scanWifiAccessPoints()V')
for f in ['mAccessPointController','mContext']:field(detail,f)
legacy='com.android.systemui.qs.tiles.dialog.InternetDialogDelegateLegacy'
method(legacy,'onStop(Landroid/app/Dialog;)V')
for f in ['mInternetDetailsContentController','mInternetDialogManager']:field(legacy,f)
field('com.android.systemui.statusbar.connectivity.AccessPointControllerImpl','mWifiPickerTrackerFactory')
method('com.android.systemui.statusbar.connectivity.WifiPickerTrackerFactory','create(Landroid/content/Context;Landroidx/lifecycle/Lifecycle;Lcom/android/wifitrackerlib/WifiPickerTracker$WifiPickerTrackerCallback;Ljava/lang/String;)Lcom/android/wifitrackerlib/WifiPickerTracker;')
method('androidx.lifecycle.LifecycleRegistry','<init>(Landroidx/lifecycle/LifecycleOwner;)V')
method('androidx.lifecycle.LifecycleRegistry','setCurrentState(Landroidx/lifecycle/Lifecycle$State;)V')
method('com.android.wifitrackerlib.WifiPickerTracker','getWifiEntries()Ljava/util/List;')
method('com.android.wifitrackerlib.WifiPickerTracker','getConnectedWifiEntry()Lcom/android/wifitrackerlib/WifiEntry;')
method('com.android.wifitrackerlib.BaseWifiTracker','close()V')
field('com.android.wifitrackerlib.BaseWifiTracker','mWorkerHandler')
method('com.android.systemui.animation.Expandable','fromView(Landroid/view/View;)Lcom/android/systemui/animation/Expandable;')
method('com.android.systemui.animation.Expandable','dialogTransitionController(Lcom/android/systemui/animation/DialogCuj;)Lcom/android/systemui/animation/DialogTransitionAnimator$Controller;')
method('com.android.systemui.animation.DialogCuj','<init>(ILjava/lang/String;)V')
for cls in [legacy,'com.android.systemui.bluetooth.qsdialog.BluetoothTileDialogDelegate','com.android.systemui.media.dialog.MediaOutputDialogDelegate']:
    method(cls,'createDialog()Lcom/android/systemui/statusbar/phone/SystemUIDialog;')
for cls in ['OplusBluetoothTile','OplusCellularTile']:
    method('com.oplus.systemui.qs.tiles.'+cls,'handleSecondaryClick(Lcom/android/systemui/animation/Expandable;)V')
# Settings navigation in the native controller and OOS external trampoline.
method(detail,'startActivity(Landroid/content/Intent;Landroid/view/View;)V')
field(detail,'mCallback')
method('com.oplus.wirelesssettings.wifi.detail.WifiEditor','onCreate(Landroid/os/Bundle;)V','wireless')
editor=source('com.oplus.wirelesssettings.wifi.detail.WifiEditor','wireless')
for token in ['key_chosen_wifientry_key','wifi.details.extra.config','key_change_data','Lx4/i;']:
    assert token in editor, token
    count += 1
assert '.super Lcom/android/settings/wifi/details/WifiNetworkDetailsFragment;' in source('x4.i','wireless')
count += 1
manifest=(base/'wireless-manifest.txt').read_text()
editor_manifest=manifest.split('E: activity (line=601)')[1].split('E: activity (')[0]
assert 'com.oplus.wirelesssettings.wifi.detail.WifiEditor' in editor_manifest
assert 'android:exported(0x01010010)=true' in editor_manifest
assert 'oplus.permission.OPLUS_COMPONENT_SAFE' in editor_manifest
assert 'wireless.settings.WIFI_EDITOR' in editor_manifest
count += 1
# Carrier-row reads must use the same live tracker as the module's Wi-Fi list.
for sig in ['connectCarrierNetwork()V','isCarrierNetworkActive()Z','getCarrierNetworkLevel()I']:
    method(detail,sig)
method('com.android.systemui.statusbar.connectivity.AccessPointControllerImpl','getMergedCarrierEntry()Lcom/android/wifitrackerlib/MergedCarrierEntry;')
method('com.android.wifitrackerlib.WifiPickerTracker','getMergedCarrierEntry()Lcom/android/wifitrackerlib/MergedCarrierEntry;')
method('com.android.wifitrackerlib.MergedCarrierEntry','connect(Lcom/android/wifitrackerlib/WifiEntry$ConnectCallback;Z)V')
assert 'Landroid/net/wifi/WifiManager;->startRestrictingAutoJoinToSubscriptionId(I)V' in source('com.android.wifitrackerlib.MergedCarrierEntry')
count += 1
modes = 'com.android.systemui.statusbar.policy.ui.dialog.ModesDialogDelegate'
field(graph, 'modesDialogDelegateProvider')
field(graph, 'modesDialogViewModelProvider')
field(graph, 'zenModeInteractorProvider')
for name in ['dialogTransitionAnimator', 'dialogEventLogger']:
    field(modes, name)
for sig in [
    'createDialog()Lcom/android/systemui/statusbar/phone/SystemUIDialog;',
    'getCurrentDialog()Lcom/android/systemui/statusbar/phone/ComponentSystemUIDialog;',
    'getContext()Landroid/content/Context;',
    'openSettings(Lcom/android/systemui/statusbar/phone/SystemUIDialog;)V',
    'launchFromDialog(Landroid/content/Intent;)V',
    'makeDndDurationDialog()Landroid/app/Dialog;',
    'access$actualLaunchFromDialog(Lcom/android/systemui/statusbar/policy/ui/dialog/ModesDialogDelegate;Landroid/content/Intent;)V',
]:
    method(modes, sig)
method('com.oplus.systemui.qs.tiles.OplusDndTile',
       'handleSecondaryClick(Lcom/android/systemui/animation/Expandable;)V')
method('com.android.systemui.animation.DialogTransitionAnimator',
       'show(Landroid/app/Dialog;Lcom/android/systemui/animation/DialogTransitionAnimator$Controller;Z)Z')
method('com.android.systemui.statusbar.policy.ui.dialog.ModesDialogEventLogger', 'logDialogSettings()V')
interactor = 'com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor'
for sig in ['activateMode(Lcom/android/settingslib/notification/modes/ZenMode;)V',
            'deactivateMode(Lcom/android/settingslib/notification/modes/ZenMode;)V',
            'shouldAskForZenDuration(Lcom/android/settingslib/notification/modes/ZenMode;)Z']:
    method(interactor, sig)
settings = source('com.oplus.settings.feature.zenmode.controller.OplusZenModePreferenceController', 'settings')
intent_method = settings.split('.method public static getZenModeIntent()')[1].split('.end method')[0]
assert 'android.settings.ZEN_MODE_SETTINGS' in intent_method
assert 'com.oplus.notificationmanager' in intent_method
assert 'setPackage(Ljava/lang/String;)' in intent_method
count += 1
plugin = source('com.oplus.settings.SettingsActivityPlugin$ZenModeSettings', 'settings')
assert 'com.oplus.notificationmanager' in plugin
assert 'com.oplus.zenmode.zenmodesettings.ZenModeSettingsActivity' in plugin
count += 1
print(f'PASS: {count} class, field and method boundaries match the supplied ROM.')
print('This is a static compatibility check, not a device/runtime test.')
