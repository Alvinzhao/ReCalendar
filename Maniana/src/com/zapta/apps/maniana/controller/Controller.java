/*
 * Copyright (C) 2011 The original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.zapta.apps.maniana.controller;

import static com.zapta.apps.maniana.util.Assertions.check;

import java.io.InputStream;

import javax.annotation.Nullable;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.TextView;

import com.zapta.apps.maniana.editors.ItemTextEditor;
import com.zapta.apps.maniana.editors.ItemVoiceEditor;
import com.zapta.apps.maniana.help.PopupMessageActivity;
import com.zapta.apps.maniana.help.PopupMessageActivity.MessageKind;
import com.zapta.apps.maniana.main.AppContext;
import com.zapta.apps.maniana.main.ResumeAction;
import com.zapta.apps.maniana.model.AppModel;
import com.zapta.apps.maniana.model.ItemColor;
import com.zapta.apps.maniana.model.ItemModel;
import com.zapta.apps.maniana.model.ItemModelReadOnly;
import com.zapta.apps.maniana.model.OrganizePageSummary;
import com.zapta.apps.maniana.model.PageKind;
import com.zapta.apps.maniana.model.PushScope;
import com.zapta.apps.maniana.persistence.ModelDeserialization;
import com.zapta.apps.maniana.persistence.ModelPersistence;
import com.zapta.apps.maniana.persistence.PersistenceMetadata;
import com.zapta.apps.maniana.quick_action.QuickActionItem;
import com.zapta.apps.maniana.settings.PreferenceKind;
import com.zapta.apps.maniana.settings.SettingsActivity;
import com.zapta.apps.maniana.util.AttachmentUtil;
import com.zapta.apps.maniana.util.FileUtil;
import com.zapta.apps.maniana.util.FileUtil.FileReadResult;
import com.zapta.apps.maniana.util.LogUtil;
import com.zapta.apps.maniana.view.AppView;
import com.zapta.apps.maniana.view.AppView.ItemAnimationType;
import com.zapta.apps.maniana.widget.BaseWidgetProvider;

/**
 * The controller class. Contains main app logic. Interacts with the model (data) and view
 * (display).
 * 
 * @author Tal Dayan
 */
public class Controller {

    private static final int VOICE_RECOGNITION_REQUEST_CODE = 1001;

    /** The app context. Provide access to the model, view and services. */
    private final AppContext mApp;

    private final QuickActionsCache mQuickActionCache;

    /** Used to detect first app resume to trigger the startup animation. */
    private int mOnAppResumeCount = 0;

    /**
     * Used to determine if the resume is from own sub activity (e.g. voice or help) as opposed to
     * app re-entry.
     */
    private boolean mInSubActivity = false;

    /**
     * Preallocated temp object. Used to reduce object alloctation.
     */
    private final OrganizePageSummary mTempSummary = new OrganizePageSummary();

    public Controller(AppContext app) {
        mApp = app;
        mQuickActionCache = new QuickActionsCache(app);
    }

    /** Called by the view when user clicks on item's text area */
    public void onItemTextClick(PageKind pageKind, int itemIndex) {
        mApp.services().maybePlayStockSound(AudioManager.FX_KEY_CLICK, false);
        showItemMenu(pageKind, itemIndex);
    }

    /** Called the view when user clicks on item's color swatch area */
    public final void onItemColorClick(final PageKind pageKind, final int itemIndex) {
        mApp.services().maybePlayStockSound(AudioManager.FX_KEYPRESS_SPACEBAR, false);
        final ItemModel item = mApp.model().getItemForMutation(pageKind, itemIndex);
        item.setColor(item.getColor().nextCyclicColor());
        mApp.view().updateSingleItemView(pageKind, itemIndex);
    }

    /** Called by the view when user clicks on item's arrow/lock area */
    public final void onItemArrowClick(final PageKind pageKind, final int itemIndex) {
        // If item locked, show item menu, allowing to unlock it.
        if (mApp.model().getItemReadOnly(pageKind, itemIndex).isLocked()) {
            showItemMenu(pageKind, itemIndex);
            return;
        }

        // Here when item is not locked. Animate and move to other page and do the actual
        // move in the model at the end of the animation.
        mApp.services().maybePlayStockSound(AudioManager.FX_KEYPRESS_RETURN, false);
        mApp.view().startItemAnimation(pageKind, itemIndex,
                AppView.ItemAnimationType.MOVING_ITEM_TO_OTHER_PAGE, 0, new Runnable() {
                    @Override
                    public void run() {
                        moveItemToOtherPage(pageKind, itemIndex);
                    }
                });
    }

    /**
     * Move a model item to the other page.
     * 
     * @param pageKind the source page.
     * @param itemIndex item index in the source page.
     */
    private final void moveItemToOtherPage(PageKind pageKind, int itemIndex) {
        // Remove item from current page.
        final ItemModel item = mApp.model().removeItem(pageKind, itemIndex);
        // Insert at the beginning of the other page.
        final PageKind otherPageKind = pageKind.otherPageKind();
        mApp.model().insertItem(otherPageKind, 0, item);

        mApp.model().clearAllUndo();
        maybeAutoSortPage(otherPageKind, false, false);
        mApp.view().updatePages();

        // The item is inserted at the top of the other page. Scroll there so it is visible
        // if the user flips to the other page.
        // NOTE(tal): This must be done after updateAlLPages(), otherwise it is ignored.
        mApp.view().scrollToTop(otherPageKind);
    }

    /** Called by the view when the user drag an item within the page */
    public final void onItemMoveInPage(final PageKind pageKind, final int sourceItemIndex,
            final int destinationItemIndex) {
        final ItemModel itemModel = mApp.model().removeItem(pageKind, sourceItemIndex);
        // NOTE(tal): if source index < destination index, the item removal above affect the index
        // of the destination by 1. Despite that, we don't compensate for it as this acieve a more
        // intuitive behavior and allow to move an item to the end of the list.
        mApp.model().insertItem(pageKind, destinationItemIndex, itemModel);
        mApp.view().upadatePage(pageKind);
        mApp.view().getRootView().post(new Runnable() {
            @Override
            public void run() {
                maybeAutosortPageWithItemOfInterest(pageKind, destinationItemIndex);
            }
        });

    }

    /** Called when the activity is paused */
    public final void onMainActivityPause() {
        // Close any leftover dialogs. This provides a more intuitive user experience.
        mApp.popupsTracker().closeAllLeftOvers();
        flushModelChanges(false);
    }

    /** If model is dirty then persist and update widgets. */
    private final void flushModelChanges(boolean alwaysUpdateAllWidgets) {
        // If state is dirty persist data so we don't lose it if the app will not resumed.
        final boolean modelWasDirty = mApp.model().isDirty();
        if (modelWasDirty) {
            final PersistenceMetadata metadata = new PersistenceMetadata(mApp.services()
                    .getAppVersionCode(), mApp.services().getAppVersionName());
            // NOTE(tal): this clears the dirty bit.
            ModelPersistence.saveData(mApp, mApp.model(), metadata);
            check(!mApp.model().isDirty());
            onBackupDataChange();

            // Use this opportunity also to garbage collect old attachment files.
            AttachmentUtil.garbageCollectAttachmentFile(mApp.context());
        }
        if (modelWasDirty || alwaysUpdateAllWidgets) {
            updateAllWidgets();
        }
    }

    /** Called when the main activity is resumed, including after app creation. */
    public final void onMainActivityResume(ResumeAction resumeAction, @Nullable Intent resumeIntent) {
        // This may leave undo items in case we cleanup completed tasks.
        maybeHandleDateChange();

        // NOTE: if we want the cleaned up items to stay in the undo buffers, move this
        // statement before the maybeHandleDateChange above.
        clearAllUndo();

        ++mOnAppResumeCount;

        // Typically we reset the view to default position (both pages are scrolled
        // to the top, Today page is shown) when the app is resume. We preserve it only
        // when it is resumed from a sub activity (e.g. settings or startup message)
        // with no resume action.
        final boolean preserveView = mInSubActivity && resumeAction.isNone();

        if (!preserveView) {
            // Force both pages to be scrolled to top. More intuitive this way.
            mApp.view().scrollToTop(PageKind.TOMOROW);
            mApp.view().scrollToTop(PageKind.TODAY);

            // Force showing today's page. This is done with animation or immediately
            // depending on settings. In case of an actual resume action, we skip
            // the animation to save user's time.
            final boolean isAppStartup = (mOnAppResumeCount == 1);
            final boolean actionAllowsAnimation = (resumeAction.isNone() || resumeAction == ResumeAction.ONLY_RESET_PAGE);
            final boolean doAnimation = isAppStartup && mApp.pref().getStartupAnimationPreference()
                    && actionAllowsAnimation;
            if (doAnimation) {
                // Show initial animation
                mApp.view().setCurrentPage(PageKind.TOMOROW, -1);
                mApp.view().getRootView().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mApp.view().setCurrentPage(PageKind.TODAY, 800);
                    }
                }, 500);
            } else {
                // No animation. Jump directly to Today.
                mApp.view().setCurrentPage(PageKind.TODAY, 0);
            }
        }

        // Reset the in sub activity tracking .
        mInSubActivity = false;

        maybeAutoSortPages(true, true);

        // Dispatch optional resume action by simulating user clicks. By the logic
        // above, if resumeAction is not NONE, here the TODAY page is already displayed and
        // is scrolled all the way to the top.
        switch (resumeAction) {
            case ADD_NEW_ITEM_BY_TEXT:
                onAddItemByTextButton(PageKind.TODAY);
                break;
            case ADD_NEW_ITEM_BY_VOICE:
                onAddItemByVoiceButton(PageKind.TODAY);
                break;
            case RESTORE_FROM_BABKUP_FILE:
                onRestoreBackupFromFile(resumeIntent);
                break;
            case NONE:
            case ONLY_RESET_PAGE:
            default:
                // Do nothing
        }
    }

    /** Update date and if needed push model items from Tomorow to Today. */
    private void maybeHandleDateChange() {
        // Sample and cache the current date.
        mApp.dateTracker().updateDate();

        // TODO: filter out redundant view date changes? (do not update unless date changed)
        mApp.view().onDateChange();

        // A quick check for the normal case where the last push was today.
        final String modelPushDateStamp = mApp.model().getLastPushDateStamp();
        final String trackerTodayDateStamp = mApp.dateTracker().getDateStampString();
        if (trackerTodayDateStamp.equals(modelPushDateStamp)) {
            return;
        }

        // Determine if to expire all locks
        final PushScope pushScope = mApp.dateTracker().computePushScope(modelPushDateStamp,
                mApp.pref().getLockExpirationPeriodPrefernece());

        if (pushScope == PushScope.NONE) {
            // Not expected because of the quick check above
            LogUtil.error("*** Unexpected condition, pushScope=NONE,"
                    + " modelTimestamp=%s, trackerDateStamp=%s", modelPushDateStamp,
                    trackerTodayDateStamp);
        } else {
            final boolean expireAllLocks = (pushScope == PushScope.ALL);
            final boolean deleteCompletedItems = mApp.pref().getAutoDailyCleanupPreference();
            LogUtil.info("Model push scope: %s, auto_cleanup=%s", pushScope, deleteCompletedItems);
            mApp.model().pushToToday(expireAllLocks, deleteCompletedItems);
            // Not bothering to test if anything changed. Always updating. This happens only once a
            // day.
            mApp.view().updatePages();
        }

        // NOTE(tal): we update the model push to today date even if we did not push. This will
        // eliminate the need to parse the model date stamp for the rest of the day.
        mApp.model().setLastPushDateStamp(mApp.dateTracker().getDateStampString());
    }

    /** Show the popup menu for a given item. Item is assumed to be already visible. */
    private final void showItemMenu(PageKind pageKind, int itemIndex) {
        final ItemModelReadOnly item = mApp.model().getItemReadOnly(pageKind, itemIndex);

        // Done vs ToDo based on item isCompleted status.
        final QuickActionItem doneAction = item.isCompleted() ? mQuickActionCache.getToDoAction()
                : mQuickActionCache.getDoneAction();

        // Edit.
        final QuickActionItem editAction = mQuickActionCache.getEditAction();

        // Lock vs Unlock based on item isLocked status.
        final QuickActionItem lockAction = item.isLocked() ? mQuickActionCache.getUnlockAction()
                : mQuickActionCache.getLockAction();

        // Delete.
        final QuickActionItem deleteAction = mQuickActionCache.getDeleteAction();

        // Action list
        final QuickActionItem actions[] = {
            doneAction,
            editAction,
            lockAction,
            deleteAction
        };

        mApp.view().setItemViewHighlight(pageKind, itemIndex, true);
        mApp.view().showItemMenu(pageKind, itemIndex, actions,
                QuickActionsCache.DISMISS_WITH_NO_SELECTION_ID);
    }

    /** Called when the user made a selection from an item popup menu. */
    public void onItemMenuSelection(final PageKind pageKind, final int itemIndex, int actionId) {
        // In case of dismissal with no selection we don't clear the undo buffer.
        if (actionId != QuickActionsCache.DISMISS_WITH_NO_SELECTION_ID) {
            clearPageUndo(pageKind);
        }

        // Clear the item-highlighted state when the menu was shown.
        mApp.view().setItemViewHighlight(pageKind, itemIndex, false);

        // Handle the action
        switch (actionId) {
            case QuickActionsCache.DISMISS_WITH_NO_SELECTION_ID: {
                return;
            }

            case QuickActionsCache.DONE_ACTION_ID: {
                mApp.services().maybePlayApplauseSoundClip(AudioManager.FX_KEY_CLICK, false);
                final ItemModel item = mApp.model().getItemForMutation(pageKind, itemIndex);
                item.setIsCompleted(true);
                // NOTE(tal): we assume that the color flag is not needed once an item is completed.
                // This is a usability heuristic. Not required otherwise.
                item.setColor(ItemColor.NONE);
                mApp.view().updateSingleItemView(pageKind, itemIndex);
                maybeAutosortPageWithItemOfInterest(pageKind, itemIndex);
                return;
            }

            case QuickActionsCache.TODO_ACTION_ID: {
                mApp.services().maybePlayStockSound(AudioManager.FX_KEY_CLICK, false);
                final ItemModel item = mApp.model().getItemForMutation(pageKind, itemIndex);
                item.setIsCompleted(false);
                mApp.view().updateSingleItemView(pageKind, itemIndex);
                maybeAutosortPageWithItemOfInterest(pageKind, itemIndex);
                return;
            }

            // Edit
            case QuickActionsCache.EDIT_ACTION_ID: {
                mApp.services().maybePlayStockSound(AudioManager.FX_KEY_CLICK, false);
                final ItemModel item = mApp.model().getItemForMutation(pageKind, itemIndex);
                ItemTextEditor.startEditor(mApp, "Edit Task", item.getText(), item.getColor(),
                        new ItemTextEditor.ItemEditorListener() {
                            @Override
                            public void onDismiss(String finalString, ItemColor finalColor) {
                                // NOTE: at this point, finalString is also cleaned of leading or
                                // trailing
                                if (finalString.length() == 0) {
                                    startItemDeletionWithAnination(pageKind, itemIndex);
                                    if (mApp.pref().getVerboseMessagesEnabledPreference()) {
                                        mApp.services().toast("Empty task deleted");
                                    }
                                } else {
                                    item.setText(finalString);
                                    item.setColor(finalColor);
                                    mApp.model().setDirty();
                                    mApp.view().updateSingleItemView(pageKind, itemIndex);
                                    // Highlight the modified item for a short time, to provide
                                    // the user with an indication of the modified item.
                                    briefItemHighlight(pageKind, itemIndex, 700);
                                }
                            }
                        });
                return;
            }

            case QuickActionsCache.LOCK_ACTION_ID:
            case QuickActionsCache.UNLOCK_ACTION_ID: {
                mApp.services().maybePlayStockSound(AudioManager.FX_KEYPRESS_STANDARD, false);

                final ItemModel item = mApp.model().getItemForMutation(pageKind, itemIndex);
                item.setIsLocked(actionId == QuickActionsCache.LOCK_ACTION_ID);
                mApp.view().updateSingleItemView(pageKind, itemIndex);
                // If lock and in Today page, we also move it to the Tomorrow page, with an
                // animation.
                if (pageKind == PageKind.TODAY && actionId == QuickActionsCache.LOCK_ACTION_ID) {
                    // We do a short delay before the animation to let the use see the icon change
                    // to lock before the item is moved to the other page.
                    mApp.view().startItemAnimation(pageKind, itemIndex,
                            AppView.ItemAnimationType.MOVING_ITEM_TO_OTHER_PAGE, 200,
                            new Runnable() {
                                @Override
                                public void run() {
                                    moveItemToOtherPage(pageKind, itemIndex);
                                }
                            });
                } else {
                    maybeAutosortPageWithItemOfInterest(pageKind, itemIndex);
                }
                return;
            }

            case QuickActionsCache.DELETE_ACTION_ID: {
                mApp.services().maybePlayStockSound(AudioManager.FX_KEYPRESS_DELETE, false);
                startItemDeletionWithAnination(pageKind, itemIndex);
                return;
            }
        }

        throw new RuntimeException("Unknown menu action: " + actionId);
    }

    /**
     * Start item deletion from the current page. The item is deleted after a short animation and
     * the page view is then updated.
     */
    private final void startItemDeletionWithAnination(final PageKind pageKind, final int itemIndex) {
        mApp.view().startItemAnimation(pageKind, itemIndex,
                AppView.ItemAnimationType.DELETING_ITEM, 0, new Runnable() {
                    @Override
                    public void run() {
                        // This runs at the end of the animation.
                        mApp.model().removeItemWithUndo(pageKind, itemIndex);
                        mApp.view().upadatePage(pageKind);
                    }
                });
    }

    /** Highlight the given item for a brief time. The item is assumed to already be visible. */
    private final void briefItemHighlight(final PageKind pageKind, final int itemIndex, int millis) {
        mApp.view().setItemViewHighlight(pageKind, itemIndex, true);
        mApp.view().getRootView().postDelayed(new Runnable() {
            @Override
            public void run() {
                mApp.view().setItemViewHighlight(pageKind, itemIndex, false);
            }
        }, millis);
    }

    /** Called by the app view when the user clicks on the Settings button. */
    public final void onIcsMenuOverflowButtonClick(PageKind pageKind) {
        mApp.mainActivity().openOptionsMenu();
    }

    /** Called by the app view when the user clicks on the Undo button. */
    public final void onUndoButton(PageKind pageKind) {
        mApp.services().maybePlayStockSound(AudioManager.FX_KEYPRESS_RETURN, false);
        final int itemRestored = mApp.model().applyUndo(pageKind);
        maybeAutoSortPage(pageKind, false, false);
        mApp.view().upadatePage(pageKind);
        mApp.services().toast("Restored %d deleted %s", itemRestored, taskOrTasks(itemRestored));
    }

    /** Called by the app view when the user clicks on the Add Item button. */
    public final void onAddItemByTextButton(final PageKind pageKind) {
        clearPageUndo(pageKind);
        mApp.services().maybePlayStockSound(AudioManager.FX_KEY_CLICK, false);
        ItemTextEditor.startEditor(mApp, "New Task", "", ItemColor.NONE,
                new ItemTextEditor.ItemEditorListener() {
                    @Override
                    public void onDismiss(String finalString, ItemColor finalColor) {
                        maybeAddNewItem(finalString, finalColor, pageKind, true);
                    }
                });
    }

    public final void onAddItemByVoiceButton(final PageKind pageKind) {
        mApp.services().maybePlayStockSound(AudioManager.FX_KEY_CLICK, false);
        mInSubActivity = true;
        ItemVoiceEditor.startVoiceEditor(mApp.mainActivity(), VOICE_RECOGNITION_REQUEST_CODE);
    }

    /** Add a new task from text editor or voice recognition. */
    private final void maybeAddNewItem(final String text, ItemColor color, final PageKind pageKind,
            boolean upperCaseIt) {
        String cleanedValue = text.trim();
        if (cleanedValue.length() == 0) {
            return;
        }

        if (upperCaseIt) {
            cleanedValue = cleanedValue.substring(0, 1).toUpperCase() + cleanedValue.substring(1);
        }

        ItemModel item = new ItemModel(cleanedValue, false, false, color);

        mApp.model().insertItem(pageKind, 0, item);
        mApp.view().upadatePage(pageKind);
        mApp.view().scrollToTop(pageKind);

        // We perform the highlight only after the view has been
        // stabilized from the scroll (since item views are reused during the scroll).
        mApp.view().getRootView().post(new Runnable() {
            @Override
            public void run() {
                briefItemHighlight(pageKind, 0, 700);
            }
        });
    }

    /** Handle the case where the app is responding to a restore from file action. */
    private final void onRestoreBackupFromFile(Intent resumeIntent) {
        // NOTE: main activity already qualified this to have the expected content type.
        final AppModel newModel;
        try {
            final Uri uri = resumeIntent.getData();
            final InputStream in = mApp.context().getContentResolver().openInputStream(uri);
            FileReadResult readResult = FileUtil.readFileToString(in, uri.toString());
            if (!readResult.outcome.isOk()) {
                mApp.services().toast("Failed to read backup file.");
                return;
            }
            // TODO: test that the file size is reasonable
            // TODO: test that the file looks like maniana file
            PersistenceMetadata resultMetadata = new PersistenceMetadata();
            newModel = new AppModel();
            ModelDeserialization.deserializeModel(newModel, resultMetadata, readResult.content);
        } catch (Throwable e) {
            LogUtil.error(e, "Error while trying to restore data");
            mApp.services().toast("Error processign the backup file");
            return;
        }

        mApp.services().toast("Found a model with %d items", newModel.getItemCount());

        final DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch (which) {
                    case DialogInterface.BUTTON_POSITIVE:
                        onRestoreBackupFromFileConfirmed(newModel);
                        break;
                    case DialogInterface.BUTTON_NEGATIVE:
                        // Nothing to do
                        break;
                }
            }
        };

        final AlertDialog.Builder builder = new AlertDialog.Builder(mApp.context());
        final String message = String.format("Replace existing %d tasks with %d new tasks?", mApp
                .model().getItemCount(), newModel.getItemCount());
        builder.setMessage(message).setPositiveButton("Yes!", dialogClickListener)
                .setNegativeButton("No", dialogClickListener).show();
    }

    private final void onRestoreBackupFromFileConfirmed(AppModel newModel) {
        mApp.model().restoreBackup(newModel);
        mApp.view().updatePages();
        mApp.services().toast("Tasks list restored from backup");
    }

    public final void onActivityResult(int requestCode, int resultCode, Intent intent) {
        switch (requestCode) {
            case VOICE_RECOGNITION_REQUEST_CODE: {
                onVoiceActivityResult(resultCode, intent);
                break;
            }
            default:
                LogUtil.warning("Unknown onActivityResult requestCode: %s", requestCode);
        }
    }

    /** Handle the result of add-new-item by voice recongition. */
    private final void onVoiceActivityResult(int resultCode, Intent intent) {
        // Prevents the main activity from scrolling to top of pages as we do when resuming from
        // an external activity.
        mInSubActivity = true;

        if (resultCode != Activity.RESULT_OK) {
            return;
        }

        ItemVoiceEditor.startSelectionDialog(mApp.context(), intent, new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int arg2, long arg3) {
                final TextView itemTextView = (TextView) arg1;
                maybeAddNewItem(itemTextView.getText().toString(), ItemColor.NONE, mApp.view()
                        .getCurrentPage(), true);
            }
        });
    }

    /** Called by the app view when the user click or long press the clean page button. */
    public final void onCleanPageButton(final PageKind pageKind, boolean isLongPress) {
        final boolean deleteCompletedItems = isLongPress;

        // NOTE: reusing mTempSummary.
        mApp.model().organizePageWithUndo(pageKind, deleteCompletedItems, -1, mTempSummary);

        mApp.services().maybePlayStockSound(
                (mTempSummary.completedItemsDeleted > 0) ? AudioManager.FX_KEYPRESS_DELETE
                        : AudioManager.FX_KEY_CLICK, false);

        mApp.view().upadatePage(pageKind);

        // Display optional message to the user
        @Nullable
        final String message = constructPageCleanMessage(mTempSummary);
        if (message != null) {
            mApp.services().toast(message);
        }
    }

    /**
     * Compose the message to show to the user after a page cleanup operation.
     * 
     * @param summary the page cleanup summary.
     * @return the message or null if no message should me shown.
     */
    @Nullable
    private final String constructPageCleanMessage(OrganizePageSummary summary) {
        final boolean isMinimal = !mApp.pref().getVerboseMessagesEnabledPreference();

        if (summary.completedItemsDeleted > 0) {
            return isMinimal ? null : String.format("Deleted %d completed %s",
                    summary.completedItemsDeleted, taskOrTasks(summary.completedItemsDeleted));
        }

        // Here when not deleted
        if (summary.orderChanged) {
            // Here when reorderd
            if (isMinimal) {
                return null;
            }
            return (summary.completedItemsFound == 0) ? "Tasks reordered" : String.format(
                    "Tasks reordered. Long press to delete %d completed %s",
                    summary.completedItemsFound, taskOrTasks(summary.completedItemsFound));
        }

        // Here when was already ordered.
        if (summary.completedItemsFound > 0) {
            // Here if found completed items.
            return isMinimal ? null : String.format(
                    "Page already organized. Long press to delete %d completed %s",
                    summary.completedItemsFound, taskOrTasks(summary.completedItemsFound));
        }

        return isMinimal ? null : "Page already organized";
    }

    /** Called by the framework when the user makes a main menu selection. */
    public final void onMainMenuSelection(MainMenuEntry entry) {
        switch (entry) {
            case HELP: {
                startPopupMessageSubActivity(MessageKind.HELP);
                break;
            }
            case SETTINGS: {
                startSubActivity(SettingsActivity.class);
                break;
            }
            case ABOUT: {
                startPopupMessageSubActivity(MessageKind.ABOUT);
                break;
            }
            default:
                throw new RuntimeException("Unknown main menu action id: " + entry);
        }
    }

    /** Handle back button event or return false if not used. */
    public final boolean onBackButton() {
        // If the current page is not today, we still the back key event and switch back to the
        // today page. Otherwise we use the default back behavior.
        final PageKind currentPage = mApp.view().getCurrentPage();
        if (currentPage != PageKind.TODAY) {
            mApp.view().setCurrentPage(PageKind.TODAY, -1);
            return true;
        }
        return false;
    }

    /**
     * Called by the app preferences client when app preferences changed.
     * 
     * @param id the id of the preference item that was changed.
     */
    public final void onPreferenceChange(PreferenceKind id) {
        onBackupDataChange();

        switch (id) {
            case PAGE_ITEM_FONT_TYPE:
            case PAGE_ITEM_FONT_SIZE:
            case PAGE_ITEM_ACTIVE_TEXT_COLOR:
            case PAGE_ITEM_COMPLETED_TEXT_COLOR:
                mApp.pref().onPageItemFontVariationPreferenceChange();
                mApp.view().onPageItemFontVariationPreferenceChange();
                break;

            case PAGE_BACKGROUND_PAPER:
            case PAGE_PAPER_COLOR:
            case PAGE_BACKGROUND_SOLID_COLOR:
                mApp.view().onPageBackgroundPreferenceChange();
                break;

            case PAGE_ITEM_DIVIDER_COLOR:
                mApp.view().onItemDividerColorPreferenceChange();
                break;

            case AUTO_SORT:
                maybeAutoSortPages(true, true);
                // If auto sort got enabled, this may affect the list widgets and thus
                // we force widget updated. In the other direction it's not required.
                flushModelChanges(mApp.pref().getAutoSortPreference());
                break;

            case SOUND_ENABLED:
            case APPLAUSE_LEVEL:
            case LOCK_PERIOD:
            case VERBOSE_MESSAGES:
            case STARTUP_ANIMATION:
                // Nothing to do here. We query these preferences on the fly.
                break;

            case AUTO_DAILY_CLEANUP:
                // This setting may affect the widget on next update but by itself, its
                // change event does require widget update (?).
                break;

            case WIDGET_BACKGROUND_PAPER:
            case WIDGET_PAPER_COLOR:
            case WIDGET_BACKGROUND_COLOR:
            case WIDGET_ITEM_FONT_TYPE:
            case WIDGET_ITEM_TEXT_COLOR:
            case WIDGET_ITEM_FONT_SIZE:
            case WIDGET_SHOW_COMPLETED_ITEMS:
            case WIDGET_ITEM_COMPLETED_TEXT_COLOR:
            case WIDGET_SHOW_TOOLBAR:
            case WIDGET_SINGLE_LINE:
                // NOTE: This covers the case where the user changes widget settings and presses the
                // Home button immediately, going back to the widgets. The widget update at
                // onAppPause() is not triggered in this case because the main activity is already
                // paused.
                flushModelChanges(true);
                break;

            case BACKUP_EMAIL:
                // Nothing to do here.
                break;

            default:
                throw new RuntimeException("Unknown preference: " + id);
        }
    }

    /** Inform backup manager about change in persisted model data of app settings */
    private final void onBackupDataChange() {
        LogUtil.info("Backup data changed");
        mApp.services().backupManager().dataChanged();
    }

    /** Force a widget update with the current */
    private final void updateAllWidgets() {
        BaseWidgetProvider.updateAllWidgetsFromModel(mApp.context(), mApp.model());
    }

    /** Called by the main activity when it is created. */
    public final void onMainActivityCreated(StartupKind startupKind) {
        // NOTE: at this point the model has not been processed yet for potential
        // task move/cleanup due to date change. This is done later in the
        // onMainActivityResume() event.

        switch (startupKind) {
            case NORMAL:
            case NEW_VERSION_SILENT:
                // Model is assume to be clean here.
                break;
            case NEW_USER:
                // Mark as dirty to persist the sample data so we don't get this message again.
                mApp.model().setDirty();
                startPopupMessageSubActivity(MessageKind.NEW_USER);
                break;
            case NEW_VERSION_ANNOUNCE:
                // Mark the model for writing to avoid this what's up splash the next time.
                mApp.model().setDirty();
                startPopupMessageSubActivity(MessageKind.WHATS_NEW);
                break;
            case SAMPLE_DATA_ERROR:
            case MODEL_DATA_ERROR:
                mApp.model().clear();
                mApp.services().toast("Error loading data (code %s)", startupKind);
            default:
                LogUtil.error("Unknown startup message type: ", startupKind);
        }
    }

    private final void startPopupMessageSubActivity(MessageKind messageKind) {
        startSubActivity(PopupMessageActivity.intentFor(mApp.context(), messageKind));
    }

    private final void startSubActivity(Class<? extends Activity> cls) {
        final Intent intent = new Intent(mApp.context(), cls);
        startSubActivity(intent);
    }

    private final void startSubActivity(Intent intent) {
        // TODO: should we assert or print an error message that mInSubActivity is false here?
        mInSubActivity = true;
        mApp.context().startActivity(intent);
    }

    /** Called by the main activity when it is destroyed. */
    public final void onMainActivityDestroy() {
    }

    /** Clear undo buffer of both model pages. */
    private final void clearAllUndo() {
        mApp.model().clearAllUndo();
        mApp.view().updateUndoButtons();
    }

    /** Clear undo buffer of given model page. */
    private final void clearPageUndo(PageKind pageKind) {
        mApp.model().clearPageUndo(pageKind);
        mApp.view().updateUndoButton(pageKind);
    }

    /** Return the correct 'n tasks' string. Result is user visible. */
    private static final String taskOrTasks(int n) {
        return (n == 1) ? "task" : "tasks";
    }

    private final boolean maybeAutoSortPage(PageKind pageKind, boolean updateViewIfSorted,
            boolean showMessageIfSorted) {
        if (mApp.pref().getAutoSortPreference()) {
            // NOTE: reusing temp summary mmeber.
            mApp.model().organizePageWithUndo(pageKind, false, -1, mTempSummary);
            if (mTempSummary.orderChanged) {
                if (updateViewIfSorted) {
                    mApp.view().upadatePage(pageKind);
                }
                if (showMessageIfSorted && mApp.pref().getVerboseMessagesEnabledPreference()) {
                    mApp.services().toast("Auto sorted");
                }
                return true;
            }
        }
        return false;
    }

    private final boolean maybeAutoSortPages(boolean updateViewIfSorted, boolean showMessageIfSorted) {
        // NOTE: avoiding '||' operator short circuit to make sure both pages are sorted.
        final boolean sorted1 = maybeAutoSortPage(PageKind.TODAY, updateViewIfSorted, false);
        final boolean sorted2 = maybeAutoSortPage(PageKind.TOMOROW, updateViewIfSorted, false);
        final boolean sorted = sorted1 || sorted2;
        // NOTE: suppressing message if showing a sub activity (e.g. SettingActivity).
        if (sorted && showMessageIfSorted && mApp.pref().getVerboseMessagesEnabledPreference()
                && !mInSubActivity) {
            mApp.services().toast("Tasks sorted");
        }
        return sorted;
    }

    /**
     * @param pageKind the page
     * @param itemOfInteresttOriginalIndex if >= 0, the pre sort index of the item to highlight post
     *        sort.
     */
    private final void maybeAutosortPageWithItemOfInterest(final PageKind pageKind,
            final int itemOfInteresttOriginalIndex) {
        if (!mApp.pref().getAutoSortPreference() || mApp.model().isPageSorted(pageKind)) {
            return;
        }

        mApp.view().startItemAnimation(pageKind, itemOfInteresttOriginalIndex,
                ItemAnimationType.SORTING_ITEM, 0, new Runnable() {
                    @Override
                    public void run() {
                        // NOTE: reusing temp summary member
                        mApp.model().organizePageWithUndo(pageKind, false,
                                itemOfInteresttOriginalIndex, mTempSummary);
                        mApp.view().upadatePage(pageKind);
                        if (mApp.pref().getVerboseMessagesEnabledPreference()) {
                            mApp.services().toast("Auto sorted");
                        }
                        if (mTempSummary.itemOfInterestNewIndex >= 0) {
                            // After the animation, briefly highlight the item at the new
                            // location.
                            mApp.view().getRootView().post(new Runnable() {
                                @Override
                                public void run() {
                                    briefItemHighlight(pageKind,
                                            mTempSummary.itemOfInterestNewIndex, 300);
                                }
                            });

                        }
                    }
                });
    }
}
