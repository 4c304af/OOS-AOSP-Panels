#!/usr/bin/env python3
"""Check the OOS SystemUI paths used by the v0.4.0 detail panels."""
import re
import sys
from pathlib import Path

base = Path(sys.argv[1])
checks = 0


def read(name, root="systemui"):
    paths = list((base / root).glob("smali*/" + name.replace(".", "/") + ".smali"))
    assert len(paths) == 1, f"Expected one ROM class for {name}, found {len(paths)}"
    return paths[0].read_text()


def check(text, *needles):
    global checks
    for needle in needles:
        assert needle in text, needle
        checks += 1


def body(text, method):
    match = re.search(r"^\.method [^\n]* " + re.escape(method) + r"\([^\n]*\n(.*?)^\.end method", text, re.M | re.S)
    assert match, method
    return match[1]


rows = "com.android.systemui.statusbar.notification.row."
menu = "com.oplus.systemui.notification.row.menupanel.OplusNotificationPopupMenuImpl"
actions = "$r8$lambda$fNxQdPMIg70ELy1_UZZUEDoi9GI"
check(body(read(menu + "$init$1"), "onLongPress"), "->tryShowPopupMenu(")
check(body(read(menu), "tryShowPopupMenu"), "->isNoPopmenuByPkg(", "->post(Ljava/lang/Runnable;)")
check(body(read(menu + "$$ExternalSyntheticLambda1"), "invoke"), "->" + actions + "(")
check(body(read(menu + "$$ExternalSyntheticLambda0"), "invoke"), "->" + "$r8$lambda$wUVF2Ixub1VO_nR6fzx0G3onhEk(")
check(read(menu), actions + "(Lcom/android/systemui/statusbar/notification/row/ExpandableNotificationRow;Lcom/oplus/systemui/notification/row/menupanel/OplusNotificationPopupMenuImpl;ZLcom/oplus/systemui/notification/row/menupanel/OplusNotificationPopupMenuImpl$$ExternalSyntheticLambda0;)")
check(read(rows + "ExpandableNotificationRow"), "mShowingPublic:Z", " isSummaryWithChildren()Z", " getEntry()")
check(read("com.android.systemui.statusbar.notification.collection.NotificationEntry"), "mBlockable:Z", " getChannel()", " getSbn()")
check(read("android.service.notification.StatusBarNotification", "framework"), " getUid()I")
check(read("android.content.Context", "framework"), " createContextAsUser(Landroid/os/UserHandle;I)Landroid/content/Context;")
editor = read(rows + "ChannelEditorDialogController")
check(editor, "shadeDialogContextInteractor:", "noMan:", "dialog:Lcom/android/systemui/statusbar/notification/row/ChannelEditorDialog;",
      "prepareDialogForApp(Ljava/lang/String;Ljava/lang/String;ILandroid/app/NotificationChannel;Landroid/graphics/drawable/Drawable;Lcom/android/systemui/statusbar/notification/row/NotificationInfo$OnSettingsClickListener;)V")
check(read(rows + "ChannelEditorDialog$Builder"), " <init>()V")
check(body(editor, "launchSettings"), "->onClick(Landroid/app/NotificationChannel;I)V")
check(body(read(rows + "ChannelEditorDialogController$initDialog$1$4"), "onClick"), "->apply()V", "->done()V")
check(body(read(rows + "NotificationGutsManager"), "startAppNotificationSettingsActivity"), "android.settings.APP_NOTIFICATION_SETTINGS", "->startNotificationGutsIntent(")

graph_name = "com.oplus.systemui.dagger.DaggerOplusReferenceGlobalRootComponent$OplusReferenceSysUIComponentImpl"
graph = read(graph_name)
check(graph, "channelEditorDialogControllerProvider:", "notificationGutsManagerProvider:", "flashlightRepositoryImplProvider:", "flashlightDialogDelegateProvider:", "flashlightControllerImplProvider:", "superPowerSaveRepositoryImplProvider:")
torch = read("com.oplus.systemui.qs.tiles.OplusFlashlightTile")
for m in ["handleLongClick", "handleSecondaryClick"]:
    check(body(torch, m), "->setExpandableView(", "->showDialog(Lcom/oplusos/systemui/common/viewdialog/model/ViewDialogAdapter;)V")
check(read("com.oplus.systemui.qs.detail.flashlight.OplusQSFlashlightDetailProvider"), " getExpandableView()Landroid/view/View;")
check(read("com.oplusos.systemui.viewdialog.domain.SysUIViewDialogInteractor"), " showDialog(Lcom/oplusos/systemui/common/viewdialog/model/ViewDialogAdapter;)V")
repo_name = "com.android.systemui.flashlight.data.repository.FlashlightRepositoryImpl"
repo = read(repo_name)
check(repo, " start()V", " getDeviceSupportsFlashlight()Z", "cameraManager:Landroid/hardware/camera2/CameraManager;")
check(body(repo, "loadFlashlightInfo"), "->FLASH_INFO_AVAILABLE:", "->LENS_FACING:", "->FLASH_INFO_STRENGTH_DEFAULT_LEVEL:", "->FLASH_INFO_STRENGTH_MAXIMUM_LEVEL:")
check(body(read(repo_name + "$setLevel$1"), "invokeSuspend"), "->turnOnTorchWithStrengthLevel(Ljava/lang/String;I)V")
check(read("com.android.systemui.statusbar.policy.FlashlightControllerImpl"), "mTorchLimited:Z")
check(read("com.oplus.systemui.qs.pipeline.data.repository.SuperPowerSaveRepositoryImpl"), " getInSuperPowerSaveMode()Z")
vm = read("com.android.systemui.flashlight.ui.viewmodel.FlashlightSliderViewModel")
check(body(vm, "setFlashlightLevel$1"), "->setEnabled(Z)V", "->setTemporaryLevel(I)V", "->setLevel(I)V")
dialog = "com.android.systemui.flashlight.ui.dialog.FlashlightDialogDelegate"
check(read(dialog), "currentDialog:", " createDialog()", "dialogTransitionAnimator:")
check(body(read(dialog + "$showDialog$2"), "invokeSuspend"), "->dialogTransitionController(", "->show(Landroid/app/Dialog;", "->access$setCurrentDialog$p(")
popup = read("com.oplus.systemui.statusbar.notification.row.menupanel.NotificationPopupMenuControllerImpl$showPopupMenu$1")
assert re.search(r"const/4 v9, 0x0\s+const/4 v11, 0x1\s+invoke-virtual \{v10, v9, v11\}, Landroid/widget/FrameLayout;->performHapticFeedback\(II\)Z", popup)
checks += 1
check(read("android.view.HapticFeedbackConstants", "framework"), "LONG_PRESS:I = 0x0", "FLAG_IGNORE_VIEW_SETTING:I = 0x1")
check(read("android.provider.Settings$Global", "framework"), '"DELETE_global"')
check(body(read("android.view.inputmethod.InputMethodManager", "framework"), "showInputMethodPickerLocked"), "->showInputMethodPickerFromClient(")
check((base / "systemui/AndroidManifest.xml").read_text(), 'android:name="android.permission.WRITE_SECURE_SETTINGS"')
# Both layouts route indicator taps here. The separate layout can also open Safety Center.
privacy = "com.android.systemui.privacy.PrivacyDialogControllerV2"
separate = read("com.oplus.systemui.separate.OplusSeparateQSPrivacyManager$QSPrivaryImpl")
oem = read("com.oplus.systemui.privacy.dialog.OplusQSPrivacyDialogController")
check(body(separate, "showDialog"), "->showDialog(Landroid/view/View;)V", "->showSafetyCenter()V")
check(body(separate, "showV2PrivacyDialog"),
      "PrivacyDialogControllerV2;->showDialog(Landroid/content/Context;Lcom/android/systemui/privacy/AbstractOngoingPrivacyChip;)V")
check(oem, " showDialog(Landroid/view/View;)V")
check(body(read("com.oplus.systemui.privacy.dialog.OplusPrivacyDialogExImpl"), "showDialog"),
      "OplusQSPrivacyDialogController;->showDialog(Landroid/view/View;)V")
check(graph, "privacyDialogControllerV2Provider:", "shadeDialogContextInteractorImplProvider:")
check(read("com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractorImpl"),
      " getContext()Landroid/content/Context;")
controller = read(privacy)
check(controller, "dialog:Landroid/app/Dialog;", "->getIndicatorAppOpUsageData(Z)Ljava/util/List;")
check(body(controller, "showDialog"), "->dismissDialog()V", "->$context:Landroid/content/Context;", "->execute(Ljava/lang/Runnable;)V")
check(body(controller, "access$filterAndSelect"), "->isActive:Z", "->getLastActiveTimestamp()J")
for suffix in ("$showDialog$1", "$showDialog$1$1"):
    runner = read(privacy + suffix)
    check(runner, " $context:Landroid/content/Context;", " this$0:Lcom/android/systemui/privacy/PrivacyDialogControllerV2;", " run()V")
check(body(read(privacy + "$showDialog$1"), "run"), "->getUserProfiles()Ljava/util/List;",
      "PrivacyDialogControllerV2$showDialog$1$1;->$context:Landroid/content/Context;")
check(body(read(privacy + "$showDialog$1$1"), "run"), "->access$filterAndSelect(", "->logEmptyDialog()V",
      "->createDialog()", "->access$setDialog$p(", "->getExpandable()", "->show()V")

phone = "com.android.systemui.statusbar.phone."
factory = "createDefaultQSFragment"
check(body(read(phone + "CentralSurfacesImpl$$ExternalSyntheticLambda13"), "get"),
      "->enableSeparateNotificationAndQS()Z", "->createDefaultQSFragment(")
# Resolve the names that the hook actually loads, including its vendor implementation.
hook_file = Path(__file__).resolve().parent / "src/dev/oos/nativepanels/CombinedQs.java"
hook_source = hook_file.read_text()
targets = re.findall(r'runtime\.type\("([^"]+)"\)', hook_source)
assert targets, f"No SystemUI reflection targets in {hook_file}"
for name in targets:
    check(read(name).splitlines()[0], "L" + name.replace(".", "/") + ";")

host_name = re.search(r'Class<\?> host = runtime\.type\("([^"]+)"\)', hook_source)[1]
factory_name = re.search(r'host\.getDeclaredMethod\("([^"]+)"', hook_source)[1]
host = read(host_name)
parent_descriptor = re.search(r"^\.super (L[^;]+;)$", host, re.M)[1]
parent = read(parent_descriptor[1:-1].replace("/", "."))
factory_signature = (factory_name + "(Lcom/android/systemui/fragments/FragmentService;"
                     "Lcom/android/systemui/shade/NotificationShadeWindowView;Z)Lcom/android/systemui/plugins/qs/QS;")
check(host, factory_signature)
check(parent, factory_signature)
factory_oos = body(host, factory_name)
check(factory_oos, "if-eqz p3, :cond_0", "Lcom/oplus/systemui/separate/OplusSimpleQSFragment;",
      "Lcom/android/systemui/qs/deprecated/QSFragmentLegacy;")
check(body(parent, factory_name), "->getFragmentHostManager(",
      "Lcom/android/systemui/qs/composefragment/QSFragmentCompose;", "->instantiate(")
check(body(read("com.android.systemui.qs.QSFragmentStartable"), "start"),
      "Lcom/android/systemui/qs/composefragment/QSFragmentCompose;", "->addFragmentInstantiationProvider(")
check(read(graph_name + "$SwitchingProvider"), "Lcom/android/systemui/qs/composefragment/QSFragmentCompose;-><init>(")
check(body(read("com.android.systemui.qs.composefragment.QSFragmentCompose"), "onCreateView"),
      "Landroidx/compose/ui/platform/ComposeView;", "0x7f0a0bd8", "Lcom/android/systemui/qs/composefragment/FrameLayoutTouchPassthrough;")
check(body(read("com.android.systemui.shade.NotificationsQuickSettingsContainer"), "onFragmentViewCreated"),
      "0x7f0a0bd8", "->findViewById(")
check(body(read("com.android.systemui.shade.QuickSettingsControllerImpl$QsFragmentListener"), "onFragmentViewCreated"),
      "->setPanelView(", "->setQqsHeightListener(", "->updateExpansion()")
check(read("com.android.systemui.qs.composefragment.QSFragmentCompose$$ExternalSyntheticLambda14"),
      "->BrightnessSliderContainer(")
check((base / "systemui/AndroidManifest.xml").read_text(),
      'android:name="android.permission.STATUS_BAR_SERVICE"', 'android:name="android.permission.QUERY_ALL_PACKAGES"')
# OOS supplies panel progress to Compose but its shade doesn't call the controller's
# separate setPanelExpanded setter. The AOSP placement lambda consumes both flags.
bridge = hook_file.with_name("CombinedQsExpansion.java").read_text()
qs_controller = read(re.search(r'runtime\.type\("([^"]+)"\)', bridge)[1])
for field in re.findall(r'Reflect\.field\(controller, "([^"]+)"\)', bridge):
    check(qs_controller, " " + field + ":")
bridge_entry = re.search(r'controller\.getDeclaredMethod\("([^"]+)"\)', bridge)[1]
check(body(qs_controller, bridge_entry), "->mShadeExpandedFraction:F", "->setQsExpansion(FFFF)V")
check(body(read("com.android.systemui.qs.composefragment.QSFragmentCompose"), "setPanelExpanded"),
      "->setPanelExpanded(Z)V")
check(body(read("com.android.systemui.qs.composefragment.viewmodel.QSFragmentComposeViewModel"),
           "isQsVisibleAndAnyShadeExpanded"), "->isQsVisible$delegate:", "->isPanelExpanded$delegate:")
check(read("com.android.systemui.qs.composefragment.QSFragmentCompose$$ExternalSyntheticLambda4"),
      "->isQsVisibleAndAnyShadeExpanded()Z", "->place$default(")
check(body(read("com.android.systemui.qs.composefragment.FrameLayoutTouchPassthrough"), "dispatchDraw"),
      "->qsVisible:Z", "->dispatchDraw(")
# Confirm all Compose and OOS contracts used by the merged shade decoration.
check(body(read("com.android.systemui.qs.composefragment.QSFragmentCompose"),
           "onCreate"), "->viewModel:")
check(read("com.android.systemui.qs.composefragment.viewmodel.QSFragmentComposeViewModel"), " getQqsHeaderHeight()I")
check(body(read("com.android.systemui.qs.composefragment.QSFragmentCompose"), "setQsExpansion"), "->setQsExpansionValue(F)V")
check(read("androidx.compose.runtime.Composer"), " consume(Landroidx/compose/runtime/CompositionLocal;)Ljava/lang/Object;")
check(read("androidx.compose.ui.platform.AndroidCompositionLocals_androidKt"), " getLocalView()")
check(read("androidx.compose.ui.res.PrimitiveResources_androidKt"), " dimensionResource(ILandroidx/compose/runtime/Composer;I)F")
check(read("androidx.compose.ui.geometry.Rect"), *(f" {side}:F" for side in ("left","right","top","bottom")))
check(read("androidx.compose.foundation.layout.PaddingKt"), " padding-qDBjuR0(Landroidx/compose/ui/Modifier;FFFF)")
check(read("com.android.systemui.brightness.ui.compose.BrightnessSliderKt"),
      " BrightnessSliderContainer(Lcom/android/systemui/brightness/ui/viewmodel/BrightnessSliderViewModel;Landroidx/compose/ui/Modifier;"
      "Lcom/android/systemui/brightness/ui/compose/ContainerColors;Lcom/android/systemui/brightness/ui/compose/BrightnessSliderDimensions;Landroidx/compose/runtime/Composer;II)V")
check(read("com.android.systemui.qs.panels.ui.compose.QuickQuickSettingsKt"), "0x7f071b97", "0x7f071b99")
check(body(read("com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults"), "getTileHeight"), "0x7f07035e")
check(graph, "autoBrightnessControllerProvider:", "provideUserTrackerProvider:")
check(read("com.android.systemui.settings.UserTrackerImpl"), " getUserContext()Landroid/content/Context;")
check(read("com.oplus.systemui.qs.slider.detail.tile.brightness.AutoBrightnessDetailTile"), "->setAutoBrightnessOn(Z)V")
check(body(read("com.oplusos.systemui.qs.util.QsSettingsValueProxy$Companion"), "setBrightnessMode"), '"screen_brightness_mode"')
tile = read("com.android.systemui.qs.panels.ui.viewmodel.TileViewModel")
for m, target in [("mainClick","->click("), ("toggleClick","->secondaryClick("), ("settingsClick","->longClick(")]:
    check(body(tile,m), target)
header = read("com.android.systemui.shade.ShadeHeaderController")
check(header, " tintedIconManagerFactory:", " statusBarIconController:", " splitShadeHeaderControllerEx:")
check(body(header, "onInit"), "->QS:", "->create(Landroid/view/ViewGroup;Lcom/android/systemui/statusbar/phone/StatusBarLocation;)")
check(body(header, "onViewAttached"), "->addIconGroup(")
check(body(header, "onViewDetached"), "->removeIconGroup(")
oemHeader = read("com.oplus.systemui.qs.impl.OplusSplitShadeHeaderControllerExImpl")
check(body(oemHeader, "init"), "->batteryViewModel:", "->tunerService:", "StatBatteryMeterViewController;-><init>", "->ignoreTunerUpdates()V")
check(read("com.oplus.systemui.qs.privacy.HeaderPrivacyIconsControllerFactory"), " createHeaderPrivacyIconsController(")
check(graph,"headerPrivacyIconsControllerFactoryProvider:")
check(qs_controller, " mShadeHeaderController:")
check(read("com.android.systemui.shade.NotificationsQuickSettingsContainer"), " mStackScroller:", " dispatchTouchEvent(Landroid/view/MotionEvent;)Z")
check(read("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout"), " getChildAtRawPosition(FF)", " dispatchTouchEvent(Landroid/view/MotionEvent;)Z")
frame = read("com.android.systemui.qs.composefragment.FrameLayoutTouchPassthrough")
check(body(frame,"dispatchDraw"), "->clipOutPath(Landroid/graphics/Path;)", "->currentClippingPath:")
check(body(frame,"isTransformedTouchPointInView"), "->getTop()I", "->isInBottomReservedArea:", "->isTransformedTouchPointInView(")
check(read("androidx.compose.ui.platform.AndroidComposeView"), " getSemanticsOwner()", " dispatchTouchEvent(Landroid/view/MotionEvent;)Z")
check(read("androidx.compose.ui.semantics.SemanticsOwnerKt"), " getAllSemanticsNodes(Landroidx/compose/ui/semantics/SemanticsOwner;ZZ)Ljava/util/List;")
check(read("androidx.compose.ui.semantics.SemanticsNode"), " getBoundsInWindow()", " getUnmergedConfig$ui()")
check(read("androidx.compose.ui.semantics.SemanticsConfiguration"), " contains(Landroidx/compose/ui/semantics/SemanticsPropertyKey;)Z")
check(read("androidx.compose.ui.semantics.SemanticsActions"), " getOnClick()", " getSetProgress()")
check(read("androidx.compose.ui.semantics.SemanticsProperties"), " getHorizontalScrollAxisRange()")
for suffix in (1, 18, 19):
    check(read(f"com.android.systemui.qs.panels.ui.compose.infinitegrid.TileKt$$ExternalSyntheticLambda{suffix}"), " invoke()Ljava/lang/Object;")
check(read("android.app.ActivityThread", "framework"), "->callApplicationOnCreate(Landroid/app/Application;)V")
check(read("android.app.Instrumentation", "framework"), " callApplicationOnCreate(Landroid/app/Application;)V")
check(read("com.android.systemui.dagger.SysUIComponent"), "Lcom/android/systemui/dagger/SysUIComponent;")
check(body(qs_controller, "onIntercept"), "->computeExpansionFraction()F", "QS partially expanded/collapsed")
check(body(read("com.android.systemui.shade.NotificationPanelViewController$TouchHandler"), "onInterceptTouchEvent"), "QuickSettingsControllerImpl;->onIntercept(Landroid/view/MotionEvent;)Z")
check(body(read("com.android.systemui.qs.composefragment.QSFragmentCompose"), "QuickSettingsElement"), "->getQqsHeaderHeight()I")
check((base / "systemui/res/xml/qs_header.xml").read_text(), 'android:scaleX="@dimen/qqs_expand_clock_scale"')
# Brightness mirroring is a presentation-only flag. Verify the actual independent
# write paths and the caller carrying both the beginning and end of each drag.
brightness = read("com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel")
check(read("com.android.systemui.qs.composefragment.viewmodel.QSFragmentComposeViewModel"), " getContainerViewModel()")
check(read("com.android.systemui.qs.ui.viewmodel.QuickSettingsContainerViewModel"), " getBrightnessSliderViewModel()")
drag_flag = body(brightness, "setIsDragging")
check(drag_flag, "->supportsMirroring:Z", "->setMirrorShowing(Z)V")
assert "setTemporaryBrightness" not in drag_flag and "->setBrightness-" not in drag_flag
check(body(brightness, "onDrag"), "Drag$Dragging;", "Drag$Stopped;", "->setTemporaryBrightness-", "->setBrightness-")
slider_pkg = "com.android.systemui.brightness.ui.compose.BrightnessSliderKt"
check(body(read(slider_pkg), "BrightnessSliderContainer"), "->getSupportsMirroring()Z", "->getShowMirror()Z",
      "->getMirrorColor-", "->getIdleColor-", "->drawInOverlay")
drag_callback = body(read(slider_pkg + "$$ExternalSyntheticLambda4"), "invoke")
assert drag_callback.count("->setIsDragging(Z)V") == 2
check(drag_callback, "->setIsDragging(Z)V", "->setValue(Ljava/lang/Object;)")
fragment = "com.android.systemui.qs.composefragment.QSFragmentCompose"
check(body(read(fragment), "QuickSettingsElement"), "0x7f071b0e", "->dimensionResource(", "->getQqsHeaderHeight()I")
check(body(read(fragment + "$$ExternalSyntheticLambda8"), "invoke"), "->roundToPx-", "add-int/2addr")
check((base / "systemui/res/values/public.xml").read_text(), 'name="qs_panel_padding_top" id="0x7f071b0e"')
dims = (base / "systemui/res/values/dimens.xml").read_text()
check(dims, 'name="qs_panel_padding_top">80.0dp', 'name="common_tile_default_active_icon_corner_radius">16.0dp',
      'name="common_tile_default_inactive_icon_corner_radius">50.0dp')
grid = "com.android.systemui.qs.panels.ui.compose.infinitegrid."
mapper = "com.android.systemui.qs.panels.ui.viewmodel.TileUiStateKt"
check(body(read(mapper), "toUiState"), "->handlesSecondaryClick:Z", "->disabledByPolicy:Z")
check(body(read(grid + "TileKt"), "Tile"), "->toUiState(", "->getHandlesToggleClick()Z", "->getColorForState(")
check(body(read(grid + "TileKt$Tile$3$uiState$2$1$1"), "emit"), "->toUiState(")
check(body(read(grid + "TileDefaults"), "getColorForState"), "->getHandlesToggleClick()Z",
      "->activeDualTargetTileColors(", "->inactiveDualTargetTileColors(")
tstate = "com.android.systemui.plugins.qs.QSTile"
for sub in ["State", "AdapterState", "BooleanState"]:
    check(body(read(tstate + "$" + sub), "copy"), "->copyTo(")
check(body(read("com.android.systemui.qs.tileimpl.QSTileImpl"), "setTileSpec"), "->spec:Ljava/lang/String;")
check(read(tstate), " click(Lcom/android/systemui/animation/Expandable;)V")
check(body(read("com.oplus.systemui.qs.tiles.OplusWifiTile"), "handleClick"), "->handleUserOperationInternal")
check(body(read("com.oplus.systemui.qs.tiles.OplusBluetoothTile"), "handleClick"), "->value:Z", "->post(")
# The original slider node must still be in its Box when the companion AndroidView is emitted.
track_sig = (" BrightnessSlider(ILkotlin/ranges/IntRange;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function3;"
             "Lcom/android/systemui/util/policy/PolicyRestriction;" + "Lkotlin/jvm/functions/Function1;" * 3
             + "ZLandroidx/compose/ui/Modifier;ZLkotlin/jvm/functions/Function0;"
             "Lcom/android/systemui/haptics/slider/compose/ui/SliderHapticsViewModel$Factory;"
             "Lcom/android/systemui/brightness/ui/compose/BrightnessSliderDimensions;Landroidx/compose/runtime/Composer;III)V")
check(read(slider_pkg), track_sig)
container = body(read(slider_pkg), "BrightnessSliderContainer")
box_open = container.index("->startReusableNode()")
draw_track = container.index("->BrightnessSlider(")
box_close = container.index("->endNode()", box_open)
assert box_open < draw_track < box_close
check(container, "Landroidx/compose/foundation/layout/BoxKt;")
check(read("androidx.compose.foundation.layout.BoxScopeInstance"), " INSTANCE:", " align(Landroidx/compose/ui/Modifier;Landroidx/compose/ui/Alignment;)")
check(read("androidx.compose.ui.Alignment$Companion"), " getCenterEnd()")
check(read("androidx.compose.foundation.layout.SizeKt"), " size-3ABfNKs(Landroidx/compose/ui/Modifier;F)")
check(read("androidx.compose.ui.Modifier"), " then(Landroidx/compose/ui/Modifier;)")
interop = read("androidx.compose.ui.viewinterop.AndroidView_androidKt")
check(interop, " AndroidView(Lkotlin/jvm/functions/Function1;Landroidx/compose/ui/Modifier;Lkotlin/jvm/functions/Function1;Landroidx/compose/runtime/Composer;II)V")
check(body(interop, "AndroidView"), "0x4", "->NoOpUpdate:")
# Default phone metrics in android-17.0.0_r1, packages/SystemUI/res/values/shade_dimens.xml.
from xml.etree import ElementTree as ET
phone = {n.get('name'): n.text for n in ET.fromstring(dims).findall('dimen')}
native = dict(tile_height=72, toggle_target_size=56, start_padding=8, large_tile_icon_size=28,
              active_icon_corner_radius=16, active_tile_corner_radius=24, inactive_icon_corner_radius=50)
for key, dp in native.items():
    assert float(phone["common_tile_default_" + key].removesuffix("dp")) == dp, key
check(dims, 'name="common_tile_default_inactive_tile_corner_radius">@dimen/common_tile_default_inactive_icon_corner_radius')
check(body(read(grid + "CommonTileKt"), "LargeTileContent"), "->getToggleTargetSize(")
check(body(read(grid + "TileDefaults"), "iconRadius-ccRj1GA"), "->getActiveIconCornerRadius(", "->getInactiveIconCornerRadius(")
check(body(read(grid + "TileDefaults"), "tileRadius-ccRj1GA"), "->getActiveTileCornerRadius(", "->getInactiveTileCornerRadius(")
# Haptics are issued at the grid action and brightness callbacks, after gesture recognition.
tile_haptics = "com.android.systemui.haptics.msdl.qs.TileHapticsViewModel"
check(body(read(tile_haptics), "setTileInteractionState"), "->tileInteractionState:", "->setValue(")
check(body(read(tile_haptics + "$onActivated$2"), "emit"), "->playToken(", "->resetStates$1()V")
check(body(read(grid + "TileKt$$ExternalSyntheticLambda1"), "invoke"), "->CLICKED:", "->toggleClick()V")
check(body(read(grid + "TileKt$$ExternalSyntheticLambda18"), "invoke"), "->LONG_CLICKED:", "->settingsClick(")
check(body(read(grid + "TileKt$$ExternalSyntheticLambda19"), "invoke"), "->CLICKED:", "->mainClick(")
check(body(read(grid + "TileKt"), "Tile"), '"TileHapticsViewModel"', "->rememberViewModel(")
check(body(read(slider_pkg), "BrightnessSlider"), '"SliderHapticsViewModel"', "->rememberViewModel(")
check(body(read(slider_pkg + "$$ExternalSyntheticLambda9"), "invoke"), "SliderHapticsViewModel$Factory;->create(")
check(body(read(slider_pkg + "$$ExternalSyntheticLambda13"), "invoke"), "->onValueChange(F)V")
check(body(read(slider_pkg + "$$ExternalSyntheticLambda14"), "invoke"), "->onValueChangeEnded()V")
slider_haptics = read("com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel")
check(body(slider_haptics, "normalize"), "->getStart()", "->getEndInclusive()", "->coerceIn(FFF)")
tracker = read("com.android.systemui.haptics.slider.SliderStateTracker")
check(body(tracker, "executeOnState"), "->onProgress(F)V")
check(body(tracker, "executeOnBookend"), "->onUpperBookend()V", "->onLowerBookend()V")
# Both startup hooks reach the same idempotent post-onCreate registration with the correct receiver.
entry = hook_file.with_name("ModuleEntry.java").read_text()
for part, expected in [('"native-panels:application"', "attachSystemUi(c.getArg(0))"),
                       ('"native-panels:oplus-application"', "attachSystemUi(c.getThisObject())")]:
    assert expected in entry.split(part, 1)[1].split("return result;", 1)[0]
# Combined shade sessions, renderer and display-only preview on this ROM.
viewmodel = read("com.android.systemui.qs.composefragment.viewmodel.QSFragmentComposeViewModel")
for func in ("getQqsMediaVisible","getQsMediaVisible"):
    check(body(viewmodel, func), "->getValue()")
session = read("android.media.session.MediaSessionManager", "framework")
check(session, "getActiveSessionsForUser(Landroid/content/ComponentName;Landroid/os/UserHandle;)",
      "addOnActiveSessionsChangedListener(Landroid/content/ComponentName;Landroid/os/UserHandle;Ljava/util/concurrent/Executor;")
check(body(read("com.android.systemui.qs.composefragment.QSFragmentComposeKt"), "MediaObject"), "->AndroidView(")
depth = read("com.android.systemui.statusbar.NotificationShadeDepthController")
check(depth, "setBrightnessMirrorVisible(Z)V", "getBrightnessMirrorVisible()Z")
check(read("com.android.systemui.shade.NotificationShadeWindowView"), "dispatchTouchEvent(Landroid/view/MotionEvent;)Z")
check(read("com.android.systemui.media.controls.ui.controller.MediaCarouselController"), "mediaFrame:Landroid/view/ViewGroup;", "mediaContent:Landroid/view/ViewGroup;")
check(read("com.android.systemui.media.controls.ui.view.MediaHost"), "mediaCarouselController:", "hostView:")
resources = (base / "systemui/res/layout/super_notification_shade.xml").read_text()
check(resources, "@id/legacy_window_root", "@id/panel_view_pager", "@id/scrim_behind", "@id/scrim_notifications", "@id/oplus_background_mask")
# Native Compose media and brightness renderers on this SystemUI build.
media_kt = read("com.android.systemui.media.remedia.ui.compose.MediaKt")
media_ui = body(media_kt, "Media")
check(media_ui, "->rememberViewModel(", "->CardCarousel(", "0x40")
legacy = read("com.android.systemui.media.controls.domain.pipeline.LegacyMediaDataFilterImpl")
check(body(legacy, "onMediaDataLoaded"), "->isCurrentProfile(", "->userEntries:")
repo = read("com.android.systemui.media.remedia.data.repository.MediaRepositoryImpl")
check(repo, "getCurrentMedia()Landroidx/compose/runtime/snapshots/SnapshotStateList;",
      "addCurrentUserMediaEntry(Lcom/android/systemui/media/controls/shared/model/MediaData;)",
      "removeCurrentUserMediaEntry(Lcom/android/internal/logging/InstanceId;Lcom/android/systemui/media/controls/shared/model/MediaData;)Z")
check(body(read("com.android.systemui.media.remedia.data.MediaDataModule$Companion"), "providesMediaRepository"), "return-object p1")
check(container, "->getShowMirror()Z", "->drawInOverlay(", "->getMirrorColor-")
check(body(read("com.android.compose.ui.graphics.DrawInOverlayKt"), "drawInOverlay"), "->FullScreenComposeViewInOverlay(", "->drawInContainer")
check(read("com.android.systemui.qs.composefragment.QSFragmentCompose$$ExternalSyntheticLambda19"), "->getShowingMirror()Z", "->gesturesDisabled(")
mi = read("com.android.systemui.media.remedia.domain.interactor.MediaInteractorImpl")
check(body(mi, "getHasActiveMedia"), "->getCurrentMedia()", "->isActive:Z")
check(body(mi, "getHasAnyMedia"), "->getCurrentMedia()")
# The provider invokes the fragment's scene inside the root QS Box, after
# installing its placement and opacity, and before ending the Box node.
qsf = "com.android.systemui.qs.composefragment.QSFragmentCompose"
qsroot = read(qsf + "$$ExternalSyntheticLambda19")
qsbranch = qsroot[qsroot.index("QSFragmentCompose.Content.<anonymous>.<anonymous>"):]
assert qsbranch.index("->graphicsLayer(") < qsbranch.index("->startReusableNode()") < qsbranch.index("->CollapsableQuickSettingsSTL(") < qsbranch.index("->endNode()")
stl = body(read(qsf), "CollapsableQuickSettingsSTL")
check(stl, '"QuickSettings"', "->SceneTransitionLayout(Lcom/android/compose/animation/scene/SceneTransitionLayoutState;")
check(body(read("com.android.systemui.shade.QuickSettingsControllerImpl"), "updateExpansion"), "->getHeaderTranslation()F", "->setQsExpansion(FFFF)V", "->setHeaderTranslationY(F)V")
check(body(read(qsf), "setQsExpansion"), "->setProposedTranslation(F)V")
scene = "com.android.compose.animation.scene."
scope = read(scene + "ContentScope")
check(scope, " Element(Lcom/android/compose/animation/scene/ElementKey;Landroidx/compose/ui/Modifier;Lkotlin/jvm/functions/Function3;Landroidx/compose/runtime/Composer;I)V",
      " element(Landroidx/compose/ui/Modifier;Lcom/android/compose/animation/scene/ElementKey;)Landroidx/compose/ui/Modifier;")
check(body(read(scene + "MovableElementKt"), "Element"), "->element(", "->maybeCachedBoxMeasurePolicy(")
check(read("com.android.systemui.media.remedia.ui.compose.Media$Elements"), "getMediaCarousel()Lcom/android/compose/animation/scene/ElementKey;")
check(read("com.android.systemui.media.remedia.ui.compose.Media"), "field public static final DEFAULT_HEIGHT:F", "field public static final COMPRESSED_HEIGHT:F")
check(read("com.android.compose.modifiers.SizeKt"), " height(Landroidx/compose/ui/Modifier;Lkotlin/jvm/functions/Function1;)")
check(read("androidx.compose.runtime.internal.ComposableLambdaKt"), " rememberComposableLambda(IZLjava/lang/Object;Landroidx/compose/runtime/Composer;I)")
check(read("androidx.compose.ui.unit.Density"), " roundToPx-0680j_4(F)I")
check(read("com.android.systemui.media.remedia.ui.compose.MediaPresentationStyle"), "static final enum Compressed:")
# The resting keyguard can report a full notification window, while QS has no drag.
check(body(qs_controller, "updateExpansion"), "->getLockscreenShadeDragProgress()F", "->setClippingBounds()V")
check(body(qs_controller, "getLockscreenShadeDragProgress"), "->getQSDragProgress()F", "->computeExpansionFraction()F")
check(body(qs_controller, "applyClippingImmediately"), "->mVisible:Z", "->setQsVisible(Z)V")
check(body(qs_controller, "isQsVisible"), "->mVisible:Z")
check(body(qs_controller, "isExpansionEnabled"), "->mExpansionEnabledPolicy:Z", "->mExpansionEnabledAmbient:Z")
# Verify the source resource, its baked circle, and the final Compose painter consuming the clean AVD.
vendor = body(read("com.oplus.systemui.media.controls.pipeline.OplusMediaDataManagerStrategy"), "getStandardAction")
public = ET.parse(base / "systemui/res/values/public.xml")
drawables = {r.get("name"): r.get("id") for r in public.findall("public") if r.get("type") == "drawable"}
drawable = base / "systemui/res/drawable"
for action in ("play", "pause"):
    name = "qs_media_container_action_" + action
    check(vendor, drawables[name])
    check((drawable / (name + ".xml")).read_text(), 'android:fillColor="#1fffffff"', 'android:pathData="M0 24c0-13.25')
check(body(mi, "getMediaActionModel"), "->getIcon()Landroid/graphics/drawable/Drawable;", "Icon$Loaded;-><init>")
check(read("com.android.systemui.media.controls.shared.model.MediaAction"), " getMediaActionEx()")
check(read("com.android.systemui.media.controls.models.player.OplusMediaActionEx"), " getIcon()Landroid/graphics/drawable/Icon;")
check(media_kt, "PlayPauseAction-Y0xEhic(")
transport = read("com.android.systemui.media.remedia.ui.compose.MediaKt$$ExternalSyntheticLambda40")
check(transport, "->animatedVectorResource(", "MediaSessionState$Playing;->INSTANCE:", "->rememberAnimatedVectorPainter(", "->CircularProgressIndicator-")
check((drawable / "ic_media_play_button.xml").read_text(), "<animated-vector", 'android:drawable="@drawable/$ic_media_play_button__4"')
check(read("com.android.systemui.media.remedia.ui.viewmodel.MediaPlayPauseActionViewModel"), " copy(", " getState()", " getIcon()", " getOnClick()")
# The brightness button uses the same background/icon pair as single-target QS tiles.
tile_defaults = read(grid + "TileDefaults")
check(body(tile_defaults, "activeTileColors"), "->getPrimary-0d7_KjU()J", "->getOnPrimary-0d7_KjU()J")
check(body(tile_defaults, "inactiveTileColors"), "->getSurfaceEffect1-0d7_KjU()J", "->getOnSurface-0d7_KjU()J")
check(body(read(grid + "TileColors"), "getBackground-0d7_KjU"), "->background:J")
check(body(read(grid + "TileColors"), "getIcon-0d7_KjU"), "->icon:J")
check(read("androidx.compose.ui.graphics.ColorKt"), " toArgb-8_81llA(J)I")
# 0.6.15: resolve the actual entry points used by the supplied SystemUI and the OS resources.
qs = "com.android.systemui.qs."
footer = read(qs + "QSSecurityFooterUtils")
calc = read("com.oplus.systemui.qs.tiles.CalculatorTile")
check(body(calc, "handleClick"), "oplus.intent.action.CALCULATOR", "ComponentName;-><init>(Landroid/content/Context;Ljava/lang/String;)", "0x7f13039c")
check((base / "systemui/res/values/strings.xml").read_text(), '<string name="calculator_os_package">com.coloros.calculator</string>')
check(read(qs + "QSHost"), " getUserContext()Landroid/content/Context;")
check(read("android.content.Context", "framework"), " getUser()Landroid/os/UserHandle;")
check(read("com.android.systemui.plugins.ActivityStarter"), "postStartActivityDismissingKeyguard(Landroid/content/Intent;ILcom/android/systemui/animation/ActivityTransitionAnimator$Controller;Ljava/lang/String;Landroid/os/UserHandle;)")
check(read(qs + "external.CustomTile"), "mComponent:Landroid/content/ComponentName;", "mShoulDisableTile:Z", " getUserContext()", "mTile:Landroid/service/quicksettings/Tile;")
check(body(read(qs + "panels.ui.viewmodel.TileViewModel"), "settingsClick"), "->longClick(")
check(body(read("com.oplus.systemui.qs.anim.OplusQSActivityStarter"), "getTileAnimationController"), "Lcom/android/systemui/qs/OplusQSTransitionAnimatorController;")
check(read("com.android.systemui.shade.QuickSettingsControllerImpl"), "mPanelViewControllerLazy:Ldagger/Lazy;", "->setQsExpansion(FFFF)V")
check(read("com.android.systemui.shade.NotificationPanelViewController"), " isClosing()Z")
check(read(qs + "panels.ui.viewmodel.TileUiStateKt"), " toIconProvider(", "IconProvider$ConstantIcon;")
check(read(qs + "tileimpl.QSTileImpl"), "maybeLoadResourceIcon(ILandroid/content/Context;)")
for icon in ("qs_auto_rotate_icon_on", "qs_auto_rotate_icon_off"):
    check(read(qs + "tiles.RotationLockTile"), drawables[icon])
    check((drawable / (icon + ".xml")).read_text(), "<animated-vector")
check(footer, "QSSecurityFooterUtils$1;", " getPositiveButton()", "->setView(Landroid/view/View;)", "QSSecurityFooterUtils;->mSystemUIDialogFactory")
check((base / "systemui/res/layout/quick_settings_footer_dialog.xml").read_text(), 'android:id="@id/vpn_warning"', '?couiColorPrimaryNeutral')
check(read(qs + "footer.domain.interactor.FooterActionsInteractorImpl"), " showUserSwitcher(Lcom/android/systemui/animation/Expandable;)")
check(read("com.android.systemui.user.ui.dialog.UserSwitcherDialogCoordinator"), "userSwitchDialogDelegateFactory:", "shadeDialogContextInteractor:")
check(read("com.android.systemui.user.ui.dialog.UserSwitchDialogDelegate"), " createDialog()", "->mDialogShower:", "->registerDataSetObserver(")
check(read("com.android.systemui.user.domain.interactor.UserSwitcherInteractor"), " dismissDialog()V")
edit = qs + "panels.ui.compose."
check(body(read(edit + "EditTileListState"), "updateTiles"), "->clear()V", "->addAll(")
check(read(edit + "infinitegrid.EditTileKt"), "->isIdle()Z", "->getTilePlacementSpec()", "->animateItem$default(", " TileGridCell(")
check(read(qs + "panels.ui.model.TileGridCell"), "->getRow()I", "->getSpec()Ljava/lang/String;", "->key:Ljava/lang/String;")
check(read(edit + "infinitegrid.EditTileKt$$ExternalSyntheticLambda12"), "->getKey()Ljava/lang/String;")
check(read(edit + "infinitegrid.EditTileKt$TileGridCell$3$1$invokeSuspend$$inlined$map$1$2"), "->getKey()Ljava/lang/String;")
for tile in ("WifiTile", "MobileDataTile", "BluetoothTile", "ModesTile", "FlashlightTileWithLevel"):
    check(read(qs + "tiles." + tile), "->handlesSecondaryClick:Z")
oplus = "com.oplus.systemui."
for entry in ("qs.widget.OplusQSMultiUserSwitch", "separate.OplusSeparateQSQuickEntranceManager"):
    check(read(oplus + entry), "->showUserSwitcher(Lcom/android/systemui/animation/Expandable;Landroid/content/Context;Landroid/view/View;)V")
check(read(oplus + "qs.user.OplusUserSwitcherDialogCoordinator$startHandlingDialogShowRequests$1$1"), "ShowUserSwitcherDialog;", "->show(Landroid/view/View;)Z")
check(read(oplus + "qs.user.OplusUserSwitcherDialogCoordinator"), "->userDetailAdapterProvider:", "OplusQSDetailInteractor$Factory;->create(")
check(read(oplus + "qs.user.OplusUserDetailAdapter"), ".implements Lcom/android/systemui/plugins/qs/DetailAdapter;")
graph = read("com.oplus.systemui.dagger.DaggerOplusReferenceGlobalRootComponent$OplusReferenceSysUIComponentImpl")
for key in ("provideShadeDialogContextInteractor", "userSwitcherController", "qsEventLoggerImpl", "falsingManagerProxy",
            "activityStarterImpl", "provideDialogTransitionAnimator", "internetDialogManager", "userSwitcherInteractor",
            "flashlightRepositoryImpl", "flashlightInteractor", "modesTileDataInteractor", "modesTileUserActionInteractor", "shadeControllerImpl",
            "provideUserTracker", "provideGlobalConfigurationController", "tunerServiceImpl", "featureFlagsClassicRelease", "provideBatteryController"):
    check(graph, ".field public " + key + "Provider:Ldagger/internal/Provider;")
def descriptor(name):
    return "L" + name.replace(".", "/") + ";"
def constructor(name, *parameters):
    check(read(name), " <init>(" + "".join(map(descriptor, parameters)) + ")V")
adapter = qs + "tiles.UserDetailView$Adapter"
logger = "com.android.internal.logging.UiEventLogger"
falsing = "com.android.systemui.plugins.FalsingManager"
starter = "com.android.systemui.plugins.ActivityStarter"
dialog_factory = "com.android.systemui.statusbar.phone.SystemUIDialog$Factory"
constructor(adapter, "android.content.Context", "com.android.systemui.statusbar.policy.UserSwitcherController", logger, falsing)
constructor("com.android.systemui.user.ui.dialog.UserSwitchDialogDelegate", adapter, logger, falsing, starter,
            "com.android.systemui.animation.DialogTransitionAnimator", dialog_factory, "android.content.Context")
check(read(qs + "QsEventLoggerImpl"), ".implements " + descriptor(logger))
check(read(qs + "tiles.dialog.InternetDialogManager"), "systemUIDialogFactory:" + descriptor(dialog_factory))
check((base / "systemui/res/layout/qs_user_dialog_content.xml").read_text(), "com.android.systemui.qs.PseudoGridView", 'android:id="@id/grid"')
check(read("com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractor"), " getContext()Landroid/content/Context;")
check(read(oplus + "qs.tileimpl.OplusQSFactoryImpl"), '"dnd"', "->mDndTileProvider:", '"flashlight"', "->mFlashlightTileProvider:")
check(read(qs + "tiles.ModesTile$handleSecondaryClick$2"), "->getCurrentTileModel(", "->handleToggleClick(")
check(read(qs + "tiles.impl.flashlight.domain.interactor.FlashlightTileUserActionInteractor"), "FlashlightInteractor;->setEnabled(Z)V")
check(read("kotlin.coroutines.intrinsics.CoroutineSingletons"), " COROUTINE_SUSPENDED:")
check(read("kotlin.ResultKt"), " throwOnFailure(Ljava/lang/Object;)V")
check(read("kotlinx.coroutines.Dispatchers"), " getMain()")
check(read(qs + "panels.ui.compose.infinitegrid.TileKt$$ExternalSyntheticLambda19"), "->toggleClick()V", "->mainClick(")
check(body(read("com.android.systemui.shade.ShadeController"), "animateCollapseShadeForcedDelayed"), "->animateCollapseShade(FIZZ)V")
check(body(read("com.android.systemui.shade.ShadeControllerImpl"), "animateCollapseShade"), "->collapse(FZZ)V")
check(read("com.android.systemui.shade.NotificationPanelViewController"), " getExpandedHeight()F", " setExpandedHeightInternal(FLjava/lang/Boolean;)V")
check(read(qs + "composefragment.FrameLayoutTouchPassthrough"), " dispatchDraw(Landroid/graphics/Canvas;)V")
check(read(starter), " startActivity(Landroid/content/Intent;Z)V")
check(read("com.android.systemui.statusbar.phone.StatusIconContainer"), " setShouldRestrictIcons(Z)V")
check(read("com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl"), " addIconGroup(", " removeIconGroup(")
battery = "com.android.systemui.statusbar.pipeline.battery."
ui = battery + "ui.composable."
vm = battery + "ui.viewmodel.BatteryViewModel"
shade_header = "com.android.systemui.shade.ui."
check(body(read(shade_header + "composable.ShadeHeaderKt"), "BatteryInfo-FHprtrg"), "->getBatteryViewModelFactory(", "->BatteryWithEstimate-FU0evQE(")
check(read(shade_header + "viewmodel.ShadeHeaderViewModel"), "batteryViewModelFactory:" + descriptor(vm + "$AlwaysShowPercent$Factory"))
check(body(read(ui + "BatteryWithEstimateKt"), "BatteryWithEstimate-FU0evQE"), "->rememberViewModel(", "->getStatusBarBatteryHeight-kPz2Gy4(", "->toDp-GaN1DYA(", "->height-3ABfNKs(", "->UnifiedBattery(")
unified = read(ui + "UnifiedBatteryKt")
check(body(unified, "UnifiedBattery"), "->getAttribution()", "->getContentDescription()", "->load(", "->BatteryLayout(")
zero = "Lkotlin/jvm/functions/Function0;"
native_layout = " BatteryLayout(" + descriptor(battery + "shared.ui.BatteryGlyph") + zero * 4 + "Landroidx/compose/ui/Modifier;Ljava/lang/String;Landroidx/compose/runtime/Composer;II)V"
check(unified, native_layout)
check(body(unified, "BatteryLayout"), "->getBodyPathSpec(", "->BatteryBody(", "->BatteryAttribution(", "->BatteryCap(")
check(body(unified, "BatteryCap"), "->getCapPathSpec(")
check(read(battery + "shared.ui.BatteryFrame"), "M17.5 0H2", "pathSpec:", "bodyPathSpec:", "capPathSpec:")
check(read(ui + "UnifiedBatteryKt$$ExternalSyntheticLambda1"), "->getLevel()", "->isFull()", "->getGlyphList()")
check(read(ui + "UnifiedBatteryKt$$ExternalSyntheticLambda0"), "->getColorProfile()", "->getDark()", "->getLight()")
colors = battery + "shared.ui.BatteryColors"
for role in ("Fill","BackgroundOnly","BackgroundWithGlyph","Glyph","Attribution"):
    check(read(colors), " get" + role + "-0d7_KjU()J")
for theme in ("DarkTheme","LightTheme"):
    check(read(colors + "$" + theme + "$Default"), " INSTANCE:", " getFill-0d7_KjU()J")
    for status in ("Error","PowerSave","Charging"):
        check(read(colors + "$" + theme + "$" + status), " getFill-0d7_KjU()J")
check(graph, ".field public batteryInteractorProvider:Ldagger/internal/Provider;")
constructor(vm + "$AlwaysShowPercent", battery + "domain.interactor.BatteryInteractor", "android.content.Context")
check(read(vm + "$Companion"), " getStatusBarBatteryHeight-kPz2Gy4(Landroid/content/Context;)J")
check(read(vm), " getAttribution()", " getLevel()", " isFull()Z", " getGlyphList()", " getColorProfile()", " getContentDescription()", " Companion:")
check(read("androidx.compose.ui.platform.ComposeView"), " <init>(Landroid/content/Context;)V", " setContent(Lkotlin/jvm/functions/Function2;)V")
check(read("androidx.compose.runtime.SnapshotStateKt"), " mutableStateOf$default(Ljava/lang/Object;)")
check(read("androidx.compose.runtime.State"), " getValue()")
check(read("androidx.compose.runtime.MutableState"), " setValue(Ljava/lang/Object;)V")
check(read("androidx.compose.ui.graphics.ColorKt"), " Color(I)J", " toArgb-8_81llA(J)I")
check(read("com.android.systemui.lifecycle.SysUiViewModelKt"), " rememberViewModel(Ljava/lang/String;Ljava/lang/Object;Lkotlin/coroutines/CoroutineContext;Lkotlin/jvm/functions/Function0;Landroidx/compose/runtime/Composer;II)Ljava/lang/Object;")
check(read("com.android.systemui.user.domain.interactor.UserSwitcherInteractor"), " isUserSwitcherEnabled()Z")
check(read(oplus + "statusbar.pipeline.battery.data.repository.BatteryRepositoryImpl"), '"display_power_percent"')
check(read("com.android.systemui.statusbar.policy.BaseUserSwitcherAdapter"), "->addAdapter(Ljava/lang/ref/WeakReference;)V")

# The HOME replacement observes only the existing battery slot.
bar = "com.android.systemui.statusbar."
osm = "com.oplus.systemui.statusbar."
check(read(bar + "window.StatusBarWindowView"), "ComposeInitializer;->onAttachedToWindow(", "ComposeInitializer;->onDetachedFromWindow(")
check(read(osm + "pipeline.battery.ui.view.StatBatteryMeterView"), " getBatteryIcon()", " disable(Z)V", " onHiddenChanged(")
check(read(osm + "pipeline.battery.ui.model.StatBatteryIcon"), " getTintColor()I")
layouts = base / "systemui/res/layout"
check((layouts/"system_icons.xml").read_text(), 'id="@id/statusIcons"', 'layout="@layout/stat_system_battery"')
check((layouts/"stat_system_battery.xml").read_text(), 'id="@id/battery_icon_view"', 'id="@id/battery_content"', 'id="@id/battery_percentage_view"')
charging = read(osm + "pipeline.battery.ui.binder.BatteryViewBinder")
check((layouts/"stat_system_battery.xml").read_text(), 'id="@id/battery_charge_icon"', 'id="@id/battery_charge_reverse"')
check(body(charging, "bind$updateChargingView"), "->isVisible:Z", "->setVisibility(I)V", "->setImageDrawable(")
check(read(osm + "pipeline.battery.ui.view.StatBatteryMeterView"), " getTransXForCoord()F")
check(read(bar + "phone.PhoneStatusBarView"), " onFinishInflate()V")
check(read("com.android.app.displaylib.PerDisplayRepository"), " get(I)Ljava/lang/Object;")
check(read("com.android.systemui.display.dagger.SystemUIDisplaySubcomponent"), " getDarkIconDispatcher()")
dispatcher = "com.android.systemui.plugins.DarkIconDispatcher"
check(read(dispatcher), " getTint(Ljava/util/Collection;Landroid/view/View;I)I", " addDarkReceiver(", " removeDarkReceiver(")
check(read(dispatcher + "$DarkReceiver"), " onDarkChanged(Ljava/util/ArrayList;FI)V", " onDarkChangedWithContrast(Ljava/util/ArrayList;II)V")
check(body(read(bar + "pipeline.shared.ui.view.ModernStatusBarView"), "onDarkChangedWithContrast"), "DarkIconDispatcher;->getTint(")
stack = read("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout")
check(stack, " dispatchDraw(Landroid/graphics/Canvas;)V", *["mRoundedRectClipping" + edge + ":I" for edge in ("Left", "Top", "Right", "Bottom", "YTranslation")])
check(read("com.android.systemui.shade.ui.ShadeColors"), " classicShadeNotificationScrimBg(Landroid/content/Context;ZZ)I")
# The combined shade paints in panel coordinates, using the pre-conversion bounds.
qs_controller = read("com.android.systemui.shade.QuickSettingsControllerImpl")
check(qs_controller, "mLastClipBounds:Landroid/graphics/Rect;", "mScreenCornerRadius:I")
check(body(qs_controller, "applyClippingImmediately"), "->mLastClipBounds:", "->mDrawableBounds:", "->set(IIII)V")
check(body(qs_controller, "getEdgePosition"), "->getExpansionFraction()F", "->getStackY()F")
check(read("com.android.systemui.shade.NotificationPanelView"), " dispatchDraw(Landroid/graphics/Canvas;)V")
check((layouts/"super_notification_shade.xml").read_text(), 'id="@id/scrim_notifications"')
card_background = read(rows + "NotificationBackgroundView")
check(card_background, "mDrawableAlpha:I", "mCornerRadii:[F", " draw(Landroid/graphics/Canvas;Landroid/graphics/drawable/Drawable;)V")
check(body(card_background, "onDraw"), "->getOplusStyle()Z", "->draw(Landroid/graphics/Canvas;Landroid/graphics/drawable/Drawable;)V")
check(read(oplus + "statusbar.notification.row.NotificationBackgroundViewExtImp"), " draw(Landroid/graphics/Canvas;Landroid/graphics/drawable/Drawable;)V")
check(read(rows + "ExpandableNotificationRow"), " isChildInGroup()Z", " isSummaryWithChildren()Z", " getPrivateLayout()", "mShowingPublic:Z")
check(read(rows + "ActivatableNotificationView"), "mOnKeyguard:Z", "mBackgroundNormal:")
check(read("android.widget.RemoteViews", "framework"), "0x1020018", "->setTagInternal(ILjava/lang/Object;)V")
# 0.6.26: the moving header is independent of the pinned notification clip.
check(qs_controller, "mMinExpansionHeight:I", "mShadeExpandedFraction:F")
check(body(qs_controller, "getHeaderTranslation"), "->getQsMinExpansionHeight()I", "->interpolate(FFF)F")
notif_row = read(rows + "ExpandableNotificationRow")
check(notif_row, " onAttachedToWindow()V", " dispatchDraw(Landroid/graphics/Canvas;)V")
check(body(notif_row, "dispatchDraw"), "ActivatableNotificationView;->dispatchDraw(")
check(read(rows + "ActivatableNotificationView"), " onDetachedFromWindow()V")
check((layouts/"status_bar_notification_row.xml").read_text(), "ExpandableNotificationRow", "NotificationBackgroundView")
# 0.6.27: inspect row metadata through the same adapter as SystemUI and put the
# paint layer between the native scrim and the pager, with window-owned layout params.
adapter = read("com.android.systemui.statusbar.notification.collection.EntryAdapter")
check(adapter, " getSbn()Landroid/service/notification/StatusBarNotification;", " isColorized()Z", " isBundle()Z")
check(body(notif_row, "getEntryAdapter"), "->mEntryAdapter:")
check(body(notif_row, "getEntry"), "instance-of", "NotificationEntry;")
check(body(read("com.android.systemui.statusbar.notification.collection.BundleEntryAdapter"), "getSbn"), "const/4 p0, 0x0")
check(notif_row, " getShowingLayout()")
check(body(read(rows + "NotificationContentView"), "getBackgroundColorForExpansionState"), "->getCustomBackgroundColor()I")
check(body(notif_row, "setBackgroundTintColor"), "->isColorized()Z", "->setAlphaComponent(II)I")
check(body(read(rows + "ActivatableNotificationView"), "getCurrentBackgroundTint"), "->mCurrentBackgroundTint:I")
check(body(read("android.app.Notification", "framework"), "isColorized"), "->isColorizedRequested()Z", "->isFgsOrUij()Z")
window = ET.parse(layouts / "super_notification_shade.xml").getroot()
child_ids = [child.get("{http://schemas.android.com/apk/res/android}id") for child in window]
assert child_ids.index("@id/scrim_notifications") < child_ids.index("@id/panel_view_pager")
checks += 1
check(read("com.android.systemui.scene.ui.view.WindowRootView"), ".super Landroid/widget/FrameLayout;", " generateDefaultLayoutParams()Landroid/widget/FrameLayout$LayoutParams;")
# 0.6.28: keyguard and ordinary status bars attach to distinct windows.
lock_bar = read("com.android.systemui.statusbar.phone.KeyguardStatusBarView")
check(lock_bar, " onAttachedToWindow()V")
check(read("com.android.systemui.statusbar.phone.PhoneStatusBarView"), " onAttachedToWindow()V")
check((layouts / "keyguard_status_bar.xml").read_text(), "KeyguardStatusBarView", 'layout="@layout/system_icons"')
check((layouts / "status_bar_expanded.xml").read_text(), 'layout="@layout/keyguard_status_bar"')
check(body(read(osm + "phone.KeyguardStatusBarViewExImpl"), "onAttachedToWindow"), "findViewById(I)", "->batteryView:")
check(body(read("com.android.systemui.scene.ui.view.WindowRootView"), "onAttachedToWindow"), "ComposeInitializer;->onAttachedToWindow(")
check(read(rows + "ExpandableOutlineView"), " drawChild(Landroid/graphics/Canvas;Landroid/view/View;J)Z")
check(body(read(rows + "ExpandableOutlineView"), "drawChild"), "FrameLayout;->drawChild(")
check(read(rows + "ExpandableView"), " getActualHeight()I", " getClipTopAmount()I", " getClipBottomAmount()I")
check(notif_row, " areGutsExposed()Z", " isExpandAnimationRunning()Z", " getTopCornerRadius()F", " getBottomCornerRadius()F")
check(read(rows + "ActivatableNotificationView"), "mDrawingAppearAnimation:Z")
check(card_background, " getActualHeight()I", " getActualWidth()I", " isAlignedToRight()Z", "mTopOverlap:I", "mBottomOverlap:I")
check(notif_row, " getTranslation()F")
check(read(rows + "ExpandableView"), "mInShelf:Z", "mInRemovalAnimation:Z")
check((layouts.parent / "values/dimens.xml").read_text(), 'name="notification_corner_radius_small">4.0dp',
      'name="notification_divider_height"')
check((layouts.parent / "values/bools.xml").read_text(), 'name="config_clipNotificationsToOutline">false')
tile_vm = read("com.android.systemui.qs.panels.ui.viewmodel.TileViewModel")
check(body(tile_vm, "mainClick"), "QSTile;->click(")
check(body(tile_vm, "toggleClick"), "QSTile;->secondaryClick(")
wifi = read("com.oplus.systemui.qs.tiles.OplusWifiTile")
check(body(wifi, "handleSecondaryClick"), "->canConfigWifi()", "->postQSRunnableDismissingKeyguard(", "->showOplusQSDetailDialogProvider(")
bt = read("com.oplus.systemui.qs.tiles.OplusBluetoothTile")
check(body(bt, "handleSecondaryClick"), "->canConfigBluetooth()", "OplusQSDetailInteractor;->show(")
check(body(read("com.android.systemui.qs.tiles.dialog.InternetDetailsContentController"), "isDeviceLocked"), "->isUnlocked()")
# Trace the lock-screen tile source from the vendor detail callback to the native return transition.
check(body(wifi, "handleSecondaryClick"), "invoke-virtual {p0, p1}, Lcom/oplus/systemui/qs/tiles/OplusWifiTile;->showOplusQSDetailDialogProvider(")
check(body(bt, "handleSecondaryClick"), "Expandable;->getView()", "OplusQSDetailInteractor;->show(")
check(body(read("com.android.systemui.qs.tileimpl.QSTileImpl$H"), "handleMessage"), "WeakReference;->get()", "->handleSecondaryClick(")
for owner, method in [
    ("com.android.systemui.qs.tiles.dialog.InternetDialogManager", "create"),
    ("com.android.systemui.bluetooth.ui.viewModel.BluetoothDetailsContentViewModel$showDialog$1", "invokeSuspend"),
]:
    check(body(read(owner), method), "Expandable;->dialogTransitionController(", "DialogTransitionAnimator;->show(")
check(body(read("com.android.systemui.animation.AnimatedDialog"), "onDialogDismissed"),
      "->shouldAnimateExit()Z", "->startAnimation(")
motion_model = read("com.android.systemui.qs.composefragment.viewmodel.QSFragmentComposeViewModel")
check(body(motion_model, "getViewTranslationY"), "->getOverScrollAmount()I", "->getOnKeyguardAndExpanded()Z",
      "->getQqsHeight()I", "->getHeaderTranslation()F")
check(body(qs_controller, "getHeaderTranslation"), "->mAmbientState:", "->getExpansionFraction()F")
check(body(read("com.android.systemui.statusbar.LockscreenShadeQsTransitionController"), "onDragDownAmountChanged"),
      "->setTransitionToFullShadeProgress(ZFF)V")
check(body(read("com.android.systemui.qs.composefragment.QSFragmentCompose"), "setTransitionToFullShadeProgress"),
      "->setLockscreenToShadeProgress(F)V", "->setSquishinessFraction(F)V")
osp = "com.oplus.systemui.statusbar.pipeline.mobile.ui.view."
check(read(osp + "AbstractOplusStatusBarMobileViewBinder"), "->initViews(I)V")
check(read(osp + "OplusStatusBarMobileViewBinder$Os17Binding"), " initViews(I)V", " getMobileTypeText()")
check(read(osp + "AbstractOplusStatusBarMobileViewBinder$Binding"), " getMobileComboReal()Landroid/view/ViewGroup;")
check((layouts / "oplus_status_bar_mobile_signal_group_big_fiveg.xml").read_text(),
      'layout_constraintEnd_toStartOf="@id/mobile_type_text"', 'layout_constraintStart_toEndOf="@id/mobile_signal_data_outer"')
stack = "com.android.systemui.statusbar.pipeline.shared.ui.composable."
stacked = body(read(stack + "BigTypeStackedMobileIconLayoutStrategy"), "StackedMobileIcon")
check(stacked, "->rowMeasurePolicy(", "->RoamingText-", "->SignalIcon$default-", "->NetworkTypeText-")
assert stacked.index("->RoamingText-") < stacked.index("->SignalIcon$default-") < stacked.index("->NetworkTypeText-")
check(read("androidx.compose.foundation.layout.RowMeasurePolicy"), "horizontalArrangement:", "verticalAlignment:", " copy(", "->arrange(")
check(read("androidx.compose.ui.node.LayoutNode"), " getChildren$ui()", " getParent$ui()", " setMeasurePolicy(", " getMeasurePolicy(")
check(read("androidx.compose.ui.node.NodeCoordinator"), " getLayoutNode()")
check(read("androidx.compose.ui.layout.OnPlacedModifierKt"), " onPlaced(")
manager = read("com.android.systemui.bluetooth.qsdialog.BluetoothDetailsContentManager")
check(body(manager, "onSeeAllClicked"), "com.android.settings.PREVIOUSLY_CONNECTED_DEVICE", "->startSettingsActivity(")
check(body(manager, "startSettingsActivity"), "->createActivityTransitionController(", "->postStartActivityDismissingKeyguard(")
check(read("com.android.systemui.bluetooth.qsdialog.DeviceItemActionInteractorImpl$onActionIconClick$2"),
      "com.android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS", '"device_address"', '":settings:show_fragment_args"')
check(read("com.android.systemui.bluetooth.ui.viewModel.BluetoothDetailsContentViewModel$showDialog$1"), "->setContentManager(")
check(body(read("com.android.systemui.qs.tiles.BluetoothTile"), "getLongClickIntent"), "android.settings.BLUETOOTH_SETTINGS")
check(read("com.android.systemui.qs.tileimpl.QSTileImpl"), "mContext:Landroid/content/Context;")
# Collapsed application summaries use the group content host. The OEM's overlay is
# visible only while the user drags or a group expansion animation is active.
row34 = read("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow")
check(row34, " getChildrenContainer()", " areChildrenExpanded()Z", " isGroupExpansionChanging()Z",
      "mIsUserSwipingToExpandRow:Z", "mShowNoBackground:Z")
check(body(row34, "updateBackgroundForGroupState"), "->isGroupExpansionChanging()", "->isChildrenExpandedAnimating()",
      "->mIsUserSwipingToExpandRow:Z", "->mShowNoBackground:Z")
overlay34 = read("com.oplus.systemui.statusbar.notification.stack.OplusNotificationChildrenContainerOverlayExImpl")
check(body(overlay34, "setOverlayVisibility"), "->isUserLocked:Z", "->isGroupExpandAnimating:Z", "const/4 v0, 0x4")
dims34 = read(stack + "OplusStackedMobileIconDimensions")
check(dims34, " copy-EiCO8V4(FFJJFFFFJFFF)", "contentStartPadding:F", "mobileTextMinWidth:F",
      "networkTypeTextPrefixFontSize:J", "networkTypeTextSuffixFontSize:J", "networkTypeTextStartPadding:F",
      "networkTypeTextEndPadding:F", "networkTypeTextBottomPadding:F", "mobileSignalIconStartPadding:F",
      "roamingTextFontSize:J", "roamingTextStartPadding:F", "roamingTextEndPadding:F", "roamingContentBottomPadding:F")
check(stacked, "->getMobileSignalIconStartPadding-", "->getNetworkTypeTextEndPadding-")
check((layouts/"status_bar_mobile_signal_group_inner.xml").read_text(), 'android:layout_marginEnd="1.0sp"')
icon_host = read("com.android.systemui.statusbar.phone.StatusIconContainer")
check(body(icon_host, "updateStates"), "0x7f0a0e02", "->getChildAt(I)", "->isIconVisible()Z",
      "->isIconBlocked()Z", "->mIgnoredSlots:", "->getSlot()", "->visibleState:I", "add-int/lit8 v6, v5, -0x1")
check(read("com.android.systemui.statusbar.phone.StatusIconContainer$StatusIconState"), "visibleState:I")
check(read("com.android.systemui.statusbar.StatusIconDisplayable"), " getSlot()Ljava/lang/String;")
check((layouts/"system_icons.xml").read_text(), 'android:id="@id/statusIcons"', 'layout="@layout/stat_system_battery"')
check((layouts.parent/"values/public.xml").read_text(), 'name="status_bar_view_state_tag" id="0x7f0a0e02"')
check((layouts.parent/"values/strings.xml").read_text(), 'name="status_bar_mobile">mobile<',
      'name="status_bar_stacked_mobile">stacked_mobile<', 'name="status_bar_wifi">wifi<')
check(body(icon_host, "updateStates"), "->getPaddingEnd()I", "sub-float v2, v1, v2", "->setXTranslation(F)V")
check(body(icon_host, "onMeasure"), "->mPaddingLeft:I", "->mPaddingRight:I", "->mRealNeedWidth:I")
check(body(icon_host, "onLayout"), "->updateStates()V")
check(body(read("com.android.systemui.statusbar.phone.StatusIconContainer$$ExternalSyntheticLambda0"), "run"), "->updateStates()V")
check(read("com.oplus.systemui.statusbar.phone.signal.widget.OplusModernStatusBarMobileView"), "pipeline/mobile/ui/view/ModernStatusBarMobileView;")
check(read("com.android.systemui.bluetooth.qsdialog.BluetoothDetailsContentManager$bind$1"), "->onSeeAllClicked(")
check(read("com.oplus.systemui.qs.tiles.OplusBluetoothTile"), ".super Lcom/android/systemui/qs/tiles/BluetoothTile;")
check(body(read("com.android.systemui.qs.tileimpl.QSTileImpl"), "handleLongClick"), "->getLongClickIntent()")
# The compact media slot and measured-height listener must observe the same dismissal state.
media_repo = read("com.android.systemui.media.remedia.data.repository.MediaRepositoryImpl")
check(body(media_repo, "isSwipedAway"), "isSwipedAway$delegate", "->getValue()")
check(body(media_repo, "setSwipedAway"), "isSwipedAway$delegate", "->setValue(")
check(body(read("com.android.systemui.media.controls.domain.pipeline.interactor.MediaCarouselInteractor"), "onSwipeToDismiss"), "->setSwipedAway(Z)V")
check(body(viewmodel, "onMediaSwipeToDismiss"), "->onSwipeToDismiss()V")
media_slots = read(qsf + "$$ExternalSyntheticLambda13")
check(body(media_slots, "invoke"), "->getQqsMediaVisible()", "->getQsMediaVisible()", "Media$Location;->SHADE:", "Media$Location;->QS:")
height_watcher = read(qsf + "$setListenerCollections$1$1$$ExternalSyntheticLambda0")
check(body(height_watcher, "invoke"), "->getQqsMediaVisible()", "->getQqsHeight()", "->onQqsHeightChanged()V")
check(body(read("com.android.systemui.shade.QuickSettingsControllerImpl$1"), "onQqsHeightChanged"),
      "->updateHeightsOnShadeLayoutChange()", "->positionClockAndNotifications(Z)V")
# Expanded-only media lives inside the same animated element as the grid.
check(body(read(qsf), "QuickSettingsElement"), "->getQuickSettingsContent()", "$$ExternalSyntheticLambda52;", "->Element(")
grid_content = "$r8$lambda$qL0OyDvA0Sgn3z6WE3ql3OX4ho0"
check(body(read(qsf + "$$ExternalSyntheticLambda52"), "invoke"), grid_content)
check(body(read(qsf), grid_content), "$$ExternalSyntheticLambda13;", "->QuickSettingsLayout(")
transition = "com.android.systemui.qs.composefragment.ui.FromQuickQuickSettingsToQuickSettingsKt"
check(body(read(transition), "quickQuickSettingsToQuickSettings"), "->getQuickSettingsContent()", "->getGridAnchor()",
      "->anchoredTranslate(", "->fractionRange$default(")
check(body(read(transition + "$$ExternalSyntheticLambda0"), "invoke"), "->getQuickSettingsContent()", "->fade(")
# Tap launches and the recorder's asynchronous action converge on physical panel collapse.
npvc = read("com.android.systemui.shade.NotificationPanelViewController")
shade_impl = read("com.android.systemui.shade.ShadeControllerImpl")
check(body(read("com.oplus.systemui.qs.tiles.ScreenRecorderTile"), "handleClick"), "->forceCollapsePanels()V")
check(body(read("com.android.systemui.qs.pipeline.domain.interactor.PanelInteractorImpl"), "forceCollapsePanels"),
      "->postAnimateForceCollapseShade()V")
check(body(shade_impl, "postAnimateForceCollapseShade"), "ShadeControllerImpl$$ExternalSyntheticLambda0;", "->execute(")
check(body(read("com.android.systemui.shade.ShadeControllerImpl$$ExternalSyntheticLambda0"), "run"), "->animateCollapseShade(FIZZ)V")
check(body(shade_impl, "animateCollapseShade"), "->collapse(FZZ)V")
check(body(shade_impl, "collapseWithDuration"), "->collapse(ZFLjava/lang/String;)V")
check(body(npvc, "collapse"), "->collapse(ZFLjava/lang/String;)V")
check(npvc.split(".method public final collapse(ZFLjava/lang/String;)V", 1)[1].split(".end method", 1)[0],
      "->canBeCollapsed$1()Z", "->setClosing(Z)V", "->fling(FLjava/lang/String;FZZ)V")
check(npvc, "mQsController:Lcom/android/systemui/shade/QuickSettingsControllerImpl;", " getExpandedHeight()F",
      " setExpandedHeightInternal(FLjava/lang/Boolean;)V", " setClosing(Z)V")
media_domain = "com.android.systemui.media.remedia.domain.interactor.MediaInteractorImpl"
check(body(read(media_domain + "$toMediaSessionModel$1$$ExternalSyntheticLambda0"), "invoke"), "->startClickIntent(")
check(body(read(media_domain), "startClickIntent"), "->launchOverLockscreen(", "->activityTransitionController(",
      "->postStartActivityDismissingKeyguard(Landroid/app/PendingIntent;")
phone = "com.android.systemui.statusbar.phone."
check(body(read(phone + "LegacyActivityStarterInternalImpl$executeRunnableDismissingKeyguard$onDismissAction$1"), "onDismiss"),
      "->collapseShadeForActivityStart()V")
check(body(read(phone + "StatusBarTransitionAnimatorController"), "onTransitionAnimationStart"), "if-nez p1,", "->collapseWithDuration(I)V")
check(body(read("com.android.systemui.qs.composefragment.FrameLayoutTouchPassthrough"), "dispatchDraw"),
      "->dispatchDraw(Landroid/graphics/Canvas;)V")
# Current display scale and the actual split-column bounds are taken from SystemUI.
battery_vm = "com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.BatteryViewModel$Companion"
check(body(read(battery_vm), "getStatusBarBatteryHeight-kPz2Gy4"), "->getResources()", "->getFloat(I)F", "->pack(FJ)J")
check(read("com.android.systemui.statusbar.pipeline.battery.ui.composable.BatteryWithEstimateKt"),
      "->getStatusBarBatteryHeight-kPz2Gy4(", "->height-3ABfNKs(")
check(dims, 'name="status_bar_icon_scale_factor"', 'name="qs_privacy_to_header_view_margin"')
chip = read("com.android.systemui.privacy.OngoingPrivacyChip")
check(chip, " getLocationOnly()Z", " setPrivacyList(", " onConfigurationChanged(")
check((layouts / "notification_stack_scroll_layout.xml").read_text(), 'android:id="@id/notification_stack_scroller"')
check(body(qs_controller, "setClippingBounds"), "->mSplitShadeEnabled:Z", "->getLeft()I", "->getRight()I", "->applyClippingImmediately(")
check(body(qs_controller, "applyClippingImmediately"), "->mLastClipBounds:Landroid/graphics/Rect;")
check(read("androidx.compose.ui.semantics.SemanticsProperties"), " getVerticalScrollAxisRange()")
check(read("androidx.compose.ui.semantics.SemanticsConfiguration"), " get(Landroidx/compose/ui/semantics/SemanticsPropertyKey;)")
check(read("androidx.compose.ui.semantics.ScrollAxisRange"), " getMaxValue()", " getValue()", " getReverseScrolling()")
check(read("androidx.compose.foundation.ScrollState"), " getValue()I", " dispatchRawDelta(F)F")
check(read("com.android.systemui.qs.composefragment.QSFragmentCompose"), " scrollState:", "->verticalScroll$default(")
check(qs_controller, " mNotificationStackScrollLayoutController:")
check(read("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController"), " getView()Lcom/android/systemui/statusbar/notification/stack/NotificationStackScrollLayout;")
check(read("com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer"), " addNotificationStackScrollLayout(")
check((layouts / "large_screen_shade_header.xml").read_text(), '@id/split_shade_status_bar', '@layout/quick_qs_status_icons')
check((layouts / "quick_qs_status_icons.xml").read_text(), '@id/statusIcons', '@id/batteryRemainingIcon', 'OngoingPrivacyChip')
check((layouts / "combined_qs_header.xml").read_text(), '@id/shade_header_system_icons', '@id/privacy_container')
bp = "com.android.systemui.statusbar.pipeline.battery."
check(read(bp+"shared.ui.BatteryFrame"), " getBodyPathSpec()", " getCapPathSpec()")
check(read(bp+"shared.ui.PathSpec"), " viewportWidth:F", " viewportHeight:F")
measure=body(read(bp+"ui.composable.BatteryMeasurePolicy"), "measure-3p2s80s")
check(measure, "->getBodyPathSpec()", "->getCapPathSpec()", "LayoutId$Attribution;->getWrapped()", "->getWidth()I", "->roundToInt(F)I")
stack_view = read("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout")
check(stack_view, " getTopPadding()I", " getOwnScrollY()I", " getFirstChildNotGone()", " getScrollRange()I", " mMaxLayoutHeight:I", " mContentHeight:I")
check(body(stack_view, "getScrollRange"), "->getContentHeight()I", "->mMaxLayoutHeight:I")
check(dims, 'name="notification_panel_margin_bottom"')
check((layouts / "status_bar_expanded.xml").read_text(), '@id/notification_panel', '@id/qs_frame', '@id/shared_notification_container')
check(read("com.android.systemui.shade.NotificationPanelView"), ".super Landroid/widget/FrameLayout;")
check(body(read("com.android.systemui.shade.NotificationsQSContainerController"), "setQsConstraints"), "->topMargin:I", "->setMargin(III)V")
check(body(stack_view, "updateTopPadding"), "->setTopPadding(I)V")
check(stack_view, " mBottomPadding:I")
check(((layouts.parent / "values-land") / "integers.xml").read_text(), 'name="quick_settings_paginated_grid_num_rows">2<')
print(f"PASS: {checks} ROM checks.")
