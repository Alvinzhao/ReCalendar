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

package com.zapta.apps.maniana.settings;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.annotation.Nullable;

import net.margaritov.preference.colorpicker.ColorPickerPreference;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.net.Uri;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.text.format.Time;

import com.zapta.apps.maniana.R;
import com.zapta.apps.maniana.help.PopupMessageActivity;
import com.zapta.apps.maniana.help.PopupMessageActivity.MessageKind;
import com.zapta.apps.maniana.util.AttachmentUtil;
import com.zapta.apps.maniana.util.DateUtil;
import com.zapta.apps.maniana.util.LogUtil;
import com.zapta.apps.maniana.util.PopupsTracker;
import com.zapta.apps.maniana.util.VisibleForTesting;
import com.zapta.apps.maniana.util.WorkingDialog;

/**
 * Activity that shows the settings page.
 * <p>
 * The class includes logic to display the current selected values. This feature is not yet provided
 * by the Android framework (as of Dec 2011).
 * 
 * @author Tal Dayan
 */
public class SettingsActivity extends PreferenceActivity implements
        OnSharedPreferenceChangeListener {

    // Sound
    private CheckBoxPreference mSoundEnablePreference;
    private ListPreference mApplauseLevelListPreference;

    // Behavior
    private ListPreference mLockPeriodListPreference;

    // Page
    private CheckBoxPreference mPageBackgroundPaperPreference;
    private ColorPickerPreference mPagePaperColorPickPreference;
    private ColorPickerPreference mPageSolidColorPickPreference;
    private PageIconSetPreference mPageIconSetPreference;
    private FontPreference mPageFontTypePreference;
    private SeekBarPreference mPageFontSizePreference;
    private ColorPickerPreference mPageTextActiveColorPickPreference;
    private ColorPickerPreference mPageTextCompletedColorPickPreference;
    private ColorPickerPreference mPageItemDividerColorPickPreference;
    private Preference mPageSelectThemePreference;

    // Widget
    private CheckBoxPreference mWidgetBackgroundPaperPreference;
    private ColorPickerPreference mWidgetPaperColorPickPreference;
    private ColorPickerPreference mWidgetSolidColorPickPreference;
    private FontPreference mWidgetFontTypePreference;
    private SeekBarPreference mWidgetFontSizePreference;
    private ColorPickerPreference mWidgetTextColorPickPreference;
    private ColorPickerPreference mWidgetTextCompletedColorPickPreference;
    private CheckBoxPreference mWidgetShowToolbarPreference;
    private Preference mWidgetSelectThemePreference;

    // Miscellaneous
    private Preference mVersionInfoPreference;
    private Preference mSharePreference;
    private Preference mFeedbackPreference;
    private Preference mRestoreDefaultsPreference;

    // Backup
    private EditTextPreference mBackupEmailPreference;
    private Preference mBackupPreference;
    // TODO: implement popup help message for do restore
    private Preference mRestoreBackupPreference;

    /** For temp time calculations. Avoiding new object creation. */
    private Time tempTime = new Time();

    /** The open dialog tracker. */
    private final PopupsTracker mPopupsTracker = new PopupsTracker();

    private PreferenceSelector mPageColorPreferenceSelector;
    private PreferenceSelector mWidgetColorPreferenceSelector;

    @Nullable
    private WorkingDialog mWorkingDialog = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.preferences);

        // Sound
        mSoundEnablePreference = (CheckBoxPreference) findPreference(PreferenceKind.SOUND_ENABLED);
        mApplauseLevelListPreference = (ListPreference) findPreference(PreferenceKind.APPLAUSE_LEVEL);

        // Behavior
        mLockPeriodListPreference = (ListPreference) findPreference(PreferenceKind.LOCK_PERIOD);

        // Pages
        mPageBackgroundPaperPreference = (CheckBoxPreference) findPreference(PreferenceKind.PAGE_BACKGROUND_PAPER);
        mPagePaperColorPickPreference = findColorPickerPrerence(PreferenceKind.PAGE_PAPER_COLOR);
        mPageSolidColorPickPreference = findColorPickerPrerence(PreferenceKind.PAGE_BACKGROUND_SOLID_COLOR);
        mPageColorPreferenceSelector = new PreferenceSelector(
                (PreferenceGroup) findPreference("prefPagesScreenKey"),
                mPageBackgroundPaperPreference, mPagePaperColorPickPreference,
                mPageSolidColorPickPreference);
        mPageIconSetPreference = (PageIconSetPreference) findPreference(PreferenceKind.PAGE_ICON_SET);
        mPageFontTypePreference = (FontPreference) findPreference(PreferenceKind.PAGE_ITEM_FONT_TYPE);
        mPageFontSizePreference = (SeekBarPreference) findPreference(PreferenceKind.PAGE_ITEM_FONT_SIZE);
        mPageTextActiveColorPickPreference = findColorPickerPrerence(PreferenceKind.PAGE_ITEM_ACTIVE_TEXT_COLOR);
        mPageTextCompletedColorPickPreference = findColorPickerPrerence(PreferenceKind.PAGE_ITEM_COMPLETED_TEXT_COLOR);
        mPageItemDividerColorPickPreference = findColorPickerPrerence(PreferenceKind.PAGE_ITEM_DIVIDER_COLOR);
        mPageSelectThemePreference = findPreference(PreferenceKind.PAGE_SELECT_THEME);

        // Widget
        mWidgetBackgroundPaperPreference = (CheckBoxPreference) findPreference(PreferenceKind.WIDGET_BACKGROUND_PAPER);
        mWidgetPaperColorPickPreference = findColorPickerPrerence(PreferenceKind.WIDGET_PAPER_COLOR);
        mWidgetSolidColorPickPreference = findColorPickerPrerence(PreferenceKind.WIDGET_BACKGROUND_COLOR);
        mWidgetColorPreferenceSelector = new PreferenceSelector(
                (PreferenceGroup) findPreference("prefWidgetScreenKey"),
                mWidgetBackgroundPaperPreference, mWidgetPaperColorPickPreference,
                mWidgetSolidColorPickPreference);
        mWidgetFontTypePreference = (FontPreference) findPreference(PreferenceKind.WIDGET_ITEM_FONT_TYPE);
        mWidgetFontSizePreference = (SeekBarPreference) findPreference(PreferenceKind.WIDGET_ITEM_FONT_SIZE);

        mWidgetTextColorPickPreference = findColorPickerPrerence(PreferenceKind.WIDGET_ITEM_TEXT_COLOR);
        mWidgetTextCompletedColorPickPreference = findColorPickerPrerence(PreferenceKind.WIDGET_ITEM_COMPLETED_TEXT_COLOR);
        mWidgetShowToolbarPreference = (CheckBoxPreference) findPreference(PreferenceKind.WIDGET_SHOW_TOOLBAR);
        mWidgetSelectThemePreference = findPreference(PreferenceKind.WIDGET_SELECT_THEME);

        // Miscellaneous
        mVersionInfoPreference = findPreference(PreferenceKind.VERSION_INFO);

        mSharePreference = findPreference(PreferenceKind.SHARE);
        mFeedbackPreference = findPreference(PreferenceKind.FEEDBACK);
        mRestoreDefaultsPreference = findPreference(PreferenceKind.RESTORE_DEFAULTS);

        // Backup
        mBackupEmailPreference = (EditTextPreference) findPreference(PreferenceKind.BACKUP_EMAIL);
        mBackupPreference = findPreference(PreferenceKind.BACKUP);
        mRestoreBackupPreference = findPreference(PreferenceKind.RESTORE);

        // Enabled alpha channel in colors pickers that need it.
        mPageItemDividerColorPickPreference.setAlphaSliderEnabled(true);
        mWidgetSolidColorPickPreference.setAlphaSliderEnabled(true);

        // Disable color V setting
        mPagePaperColorPickPreference.setJustHsNoV(0.2f);
        mWidgetPaperColorPickPreference.setJustHsNoV(0.2f);

        // We lookup also the preferences we don't use here to assert that the code and the xml
        // key strings match.
        findPreference(PreferenceKind.AUTO_SORT);
        findPreference(PreferenceKind.AUTO_DAILY_CLEANUP);
        findPreference(PreferenceKind.WIDGET_SHOW_COMPLETED_ITEMS);

        findPreference(PreferenceKind.WIDGET_SINGLE_LINE);

        mPageSelectThemePreference.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            public boolean onPreferenceClick(Preference preference) {
                onPageSelectThemeClick();
                return true;
            }
        });

        mWidgetSelectThemePreference.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            public boolean onPreferenceClick(Preference preference) {
                onWidgetSelectThemeClick();
                return true;
            }
        });

        mRestoreDefaultsPreference.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            public boolean onPreferenceClick(Preference preference) {
                onResetSettingsInitialClick();
                return true;
            }
        });

        mVersionInfoPreference.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            public boolean onPreferenceClick(Preference preference) {
                onVersionInfoClick();
                return true;
            }
        });

        mRestoreBackupPreference.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            public boolean onPreferenceClick(Preference preference) {
                onRestoreBackupClick();
                return true;
            }
        });

        mBackupPreference.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            public boolean onPreferenceClick(Preference preference) {
                onBackupClick();
                return true;
            }
        });

        mSharePreference.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            public boolean onPreferenceClick(Preference preference) {
                onShareClick();
                return true;
            }
        });

        mFeedbackPreference.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            public boolean onPreferenceClick(Preference preference) {
                onFeedbackClick();
                return true;
            }
        });
    }

    /** Handle the user clicking on page theme selection in the settings activity */
    private final void onPageSelectThemeClick() {
        final Dialog dialog = new ThumbnailSelector<PageTheme>(this, PageTheme.PAGE_THEMES,
                mPopupsTracker, new ThumbnailSelector.ThumbnailSelectorListener<PageTheme>() {
                    @Override
                    public void onThumbnailSelection(PageTheme theme) {
                        onPageThemeSelection(theme);
                    }
                });
        dialog.show();
    }

    /** Handle the user clicking on widget theme selection in the settings activity */
    private final void onWidgetSelectThemeClick() {
        final Dialog dialog = new ThumbnailSelector<WidgetTheme>(this, WidgetTheme.WIDGET_THEMES,
                mPopupsTracker, new ThumbnailSelector.ThumbnailSelectorListener<WidgetTheme>() {
                    @Override
                    public void onThumbnailSelection(WidgetTheme theme) {
                        onWidgetThemeSelection(theme);
                    }
                });
        dialog.show();
    }

    /** Called when a page theme is selected from the widget theme dialog. */
    private final void onPageThemeSelection(PageTheme theme) {
        mPageBackgroundPaperPreference.setChecked(theme.backgroundPaper);
        mPagePaperColorPickPreference.onColorChanged(theme.paperColor);
        mPageSolidColorPickPreference.onColorChanged(theme.backgroundSolidColor);
        mPageIconSetPreference.setValue(theme.iconSet);
        mPageFontTypePreference.setValue(theme.fontType);
        mPageFontSizePreference.setValue(theme.fontSize);
        mPageTextActiveColorPickPreference.onColorChanged(theme.textColor);
        mPageTextCompletedColorPickPreference.onColorChanged(theme.completedTextColor);
        mPageItemDividerColorPickPreference.onColorChanged(theme.itemDividerColor);
    }

    /** Called when a widget theme is selected from the widget theme dialog. */
    private final void onWidgetThemeSelection(WidgetTheme theme) {
        mWidgetBackgroundPaperPreference.setChecked(theme.backgroundPaper);
        mWidgetPaperColorPickPreference.onColorChanged(theme.paperColor);
        mWidgetSolidColorPickPreference.onColorChanged(theme.backgroundColor);
        mWidgetFontTypePreference.setValue(theme.fontType);
        mWidgetFontSizePreference.setValue(theme.fontSize);
        mWidgetTextColorPickPreference.onColorChanged(theme.textColor);
        mWidgetTextCompletedColorPickPreference.onColorChanged(theme.completedTextColor);
        mWidgetShowToolbarPreference.setChecked(theme.showToolbar);
        // Show completed items preferences and show single line are NOT modified by the theme.
    }

    /** Handle user selecting reset settings in the settings activity */
    private final void onResetSettingsInitialClick() {
        final DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch (which) {
                    case DialogInterface.BUTTON_POSITIVE:
                        dialog.dismiss();
                        mWorkingDialog = new WorkingDialog(SettingsActivity.this,
                                "Restoring defaults...");
                        mWorkingDialog.setOnShowListener(new DialogInterface.OnShowListener() {
                            @Override
                            public void onShow(DialogInterface arg0) {
                                getListView().post(new Runnable() {
                                    @Override
                                    public void run() {
                                        onResetSettingsConfirmed();
                                    }
                                });
                            }
                        });

                        mWorkingDialog.show();
                        break;

                    case DialogInterface.BUTTON_NEGATIVE:
                        // Doing nothing
                        break;
                }
            }
        };

        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage("Revert all settings to default values?\n\n(Does not affect task data)")
                .setPositiveButton("Yes!", dialogClickListener)
                .setNegativeButton("No", dialogClickListener).show();
    }

    private final void onResetSettingsConfirmed() {

        Editor editor = getPreferenceScreen().getEditor();
        editor.clear();

        // TODO: this is a hack to force notifying the main activity and widget
        // about the change. For some reason they are not notified about it
        // as the the non custom preference types do.
        editor.putInt(PreferenceKind.PAGE_ITEM_ACTIVE_TEXT_COLOR.getKey(),
                PreferenceConstants.DEFAULT_ITEM_TEXT_COLOR);
        editor.putInt(PreferenceKind.PAGE_ITEM_COMPLETED_TEXT_COLOR.getKey(),
                PreferenceConstants.DEFAULT_COMPLETED_ITEM_TEXT_COLOR);
        editor.putInt(PreferenceKind.PAGE_PAPER_COLOR.getKey(),
                PreferenceConstants.DEFAULT_PAGE_PAPER_COLOR);
        editor.putInt(PreferenceKind.PAGE_BACKGROUND_SOLID_COLOR.getKey(),
                PreferenceConstants.DEFAULT_PAGE_BACKGROUND_SOLID_COLOR);
        editor.putInt(PreferenceKind.PAGE_ITEM_DIVIDER_COLOR.getKey(),
                PreferenceConstants.DEFAULT_PAGE_ITEM_DIVIDER_COLOR);
        editor.putInt(PreferenceKind.WIDGET_BACKGROUND_COLOR.getKey(),
                PreferenceConstants.DEFAULT_WIDGET_BACKGROUND_COLOR);
        editor.putInt(PreferenceKind.WIDGET_ITEM_TEXT_COLOR.getKey(),
                PreferenceConstants.DEFAULT_WIDGET_TEXT_COLOR);
        editor.putInt(PreferenceKind.WIDGET_ITEM_COMPLETED_TEXT_COLOR.getKey(),
                PreferenceConstants.DEFAULT_WIDGET_ITEM_COMPLETED_TEXT_COLOR);

        // NOTE: for checkbox whose default value is false, need to set them
        // here to false.
        editor.putBoolean(PreferenceKind.WIDGET_SHOW_COMPLETED_ITEMS.getKey(),
                PreferenceConstants.DEFAULT_WIDGET_SHOW_COMPLETED_ITEMS);
        editor.putBoolean(PreferenceKind.WIDGET_SINGLE_LINE.getKey(),
                PreferenceConstants.DEFAULT_WIDGET_SINGLE_LINE);
        editor.putBoolean(PreferenceKind.WIDGET_AUTO_FIT.getKey(),
                PreferenceConstants.DEFAULT_WIDGET_AUTO_FIT);

        // Set icon set preferences to broadcast the change event.
        editor.putString(PreferenceKind.PAGE_ICON_SET.getKey(),
                PreferenceConstants.DEFAULT_PAGE_ICON_SET.getKey());
        
        // Set font preferences to broadcast the change event.
        editor.putString(PreferenceKind.PAGE_ITEM_FONT_TYPE.getKey(),
                PreferenceConstants.DEFAULT_PAGE_FONT_TYPE.getKey());
        editor.putString(PreferenceKind.WIDGET_ITEM_FONT_TYPE.getKey(),
                PreferenceConstants.DEFAULT_WIDGET_FONT_TYPE.getKey());

        // Set the seekbar preferences to broadcast the change event
        editor.putInt(PreferenceKind.PAGE_ITEM_FONT_SIZE.getKey(),
                PreferenceConstants.DEFAULT_PAGE_FONT_SIZE);
        editor.putInt(PreferenceKind.WIDGET_ITEM_FONT_SIZE.getKey(),
                PreferenceConstants.DEFAULT_WIDGET_ITEM_FONT_SIZE);

        editor.commit();

        // Hack per http://tinyurl.com/c44gl4r We close this activity and restart
        // Using the same intent that created this preferences activity.
        // This causes it to reload the preferences.
        finish();
        startActivity(getIntent());

        mWorkingDialog.dismiss();
        mWorkingDialog = null;
    }

    private final void onVersionInfoClick() {
        final Intent intent = PopupMessageActivity.intentFor(this, MessageKind.WHATS_NEW);
        startActivity(intent);
    }

    private final void onRestoreBackupClick() {
        // Popup a message with restore instructions
        final Intent intent = PopupMessageActivity.intentFor(this, MessageKind.RESTORE_BACKUP);
        startActivity(intent);
    }

    private final void onBackupClick() {

        AttachmentUtil.createAttachmentFile(this);

        Intent intent = new Intent(Intent.ACTION_SEND);
        // sharingIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        final String defaultToAddress = getBackupEmailAddress();
        intent.putExtra(Intent.EXTRA_EMAIL, new String[] {
            defaultToAddress
        });

        final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-dd-MM HH:mm:ss");
        final String timeString = dateFormat.format(new Date());
        intent.putExtra(Intent.EXTRA_SUBJECT, "Maniana backup " + timeString);

        intent.putExtra(
                Intent.EXTRA_TEXT,
                "This email message was sent by Android's Maniana To Do List app.\n\n"
                        + "It contains an attachment file with a backup copy of the task list.\n\n"
                        + "To restore the task list, open this email message in an Android device where"
                        + " Maniana is installed and click on the Download button of the attachment.");

        intent.setType("application/json");
        final Uri fileUri = Uri.fromFile(new File("/mnt/sdcard/../.." + getFilesDir() + "/"
                + AttachmentUtil.BACKUP_ATTACHMENT_FILE_NAME));
        intent.putExtra(Intent.EXTRA_STREAM, fileUri);

        startActivity(intent);
    }

    private final void onShareClick() {
        Intent sharingIntent = new Intent(Intent.ACTION_SEND);
        sharingIntent.setType("text/plain");
        String message = "Check out Maniana, a free fun and easy to use todo "
                + "list app on the Android Market:\n \n"
                + "https://market.android.com/details?id=com.zapta.apps.maniana";
        sharingIntent.putExtra(android.content.Intent.EXTRA_TEXT, message);
        sharingIntent.putExtra(android.content.Intent.EXTRA_SUBJECT,
                "Check out \"Maniana To Do List\"");
        startActivity(Intent.createChooser(sharingIntent, "Share using"));
    }

    private final void onFeedbackClick() {
        // NOTE: based on http://stackoverflow.com/questions/3312438
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        final Uri data = Uri.parse("mailto:maniana@zapta.com?subject=Maniana feedback&body=");
        intent.setData(data);
        startActivity(intent);
    }

    private final ColorPickerPreference findColorPickerPrerence(PreferenceKind kind) {
        return (ColorPickerPreference) findPreference(kind);
    }

    private final Preference findPreference(PreferenceKind kind) {
        final Preference result = getPreferenceScreen().findPreference(kind.getKey());
        if (result == null) {
            throw new RuntimeException("Preference key not found for kind: " + kind);
        }
        return result;
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSummaries();
        getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPopupsTracker.closeAllLeftOvers();
        getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(
                this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        // NOTE(tal): since settings change is a rare operation, we don't bother to
        // switch of key and always update all the lists. Code is simpler this way.
        updateSummaries();
    }

    private void updateSummaries() {
        // Disable applause if voice is disabled
        if (mSoundEnablePreference.isChecked()) {
            updateListPreferenceSummary(mApplauseLevelListPreference,
                    R.array.applauseLevelSummaries, null);
        } else {
            mApplauseLevelListPreference.setSummary("(sound is off)");
        }

        {
            final String baseBackupEmailSummary = "Destination Gmail address for sending backup attachments.";
            final String backupEmailAddress = getBackupEmailAddress();
            mBackupEmailPreference
                    .setSummary((backupEmailAddress.length() > 0) ? "("
                            + backupEmailAddress + ")\n" + baseBackupEmailSummary  : baseBackupEmailSummary);

            final boolean hasBackupEmailAddress = backupEmailAddress.contains("@")
                    && backupEmailAddress.contains(".");
            mBackupPreference
                    .setSummary(hasBackupEmailAddress ? "Click to email a Maniana backup attachment to your Gmail account"
                            : "Please enter your Gmail address to enable Maniana backups");
            mBackupPreference.setEnabled(hasBackupEmailAddress);
        }

        // Update color selectors
        mPageColorPreferenceSelector.update();
        mWidgetColorPreferenceSelector.update();

        // For lock expiration preference, also show the time until next expiration. This require
        // some computation.
        {
            final String key = mLockPeriodListPreference.getValue();

            final LockExpirationPeriod selection = LockExpirationPeriod.fromKey(key, null);

            final int wholeHoursLeft;
            if (selection == LockExpirationPeriod.WEEKLY) {
                tempTime.setToNow();
                wholeHoursLeft = DateUtil.hoursToEndOfWeek(tempTime);
            } else if (selection == LockExpirationPeriod.MONTHLY) {
                tempTime.setToNow();
                wholeHoursLeft = DateUtil.hoursToEndOfMonth(tempTime);
            } else {
                wholeHoursLeft = -1;
            }

            final String suffix = (wholeHoursLeft >= 0) ? construtLockTimeLeftMessageSuffix(wholeHoursLeft)
                    : "";
            updateListPreferenceSummary(mLockPeriodListPreference, R.array.lockPeriodSummaries,
                    suffix);
        }
    }

    private final String getBackupEmailAddress() {
        return mBackupEmailPreference.getText().trim();
    }

    @VisibleForTesting
    static String construtLockTimeLeftMessageSuffix(int wholeHoursLeft) {
        if (wholeHoursLeft < 16) {
            return "  (tonight)";
        }
        if (wholeHoursLeft < 48) {
            return String.format("  (in %d hours)", wholeHoursLeft);
        }
        // Rounding up
        return String.format("  (in %d days)", (wholeHoursLeft + 23) / 24);
    }

    private void updateListPreferenceSummary(ListPreference listPreference, int stringArrayId,
            @Nullable String suffix) {
        final String value = listPreference.getValue();
        // -1 if not found.
        final int index = listPreference.findIndexOfValue(value);
        String summary;
        if (index < 0) {
            LogUtil.error("Could not find index of preference value [%s]", value);
            // Fallback
            summary = "";
        } else {
            summary = getResources().getStringArray(stringArrayId)[index];
            if (suffix != null) {
                summary += suffix;
            }
        }
        listPreference.setSummary(summary);
    }
}
