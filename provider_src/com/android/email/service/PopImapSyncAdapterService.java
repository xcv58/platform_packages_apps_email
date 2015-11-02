/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.email.service;

import android.app.Service;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

import android.text.format.DateUtils;
import com.android.email.R;
import com.android.emailcommon.TempDirectory;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.mail.providers.UIProvider;
import com.android.mail.utils.LogUtils;
import edu.buffalo.cse.phonelab.json.StrictJSONArray;
import edu.buffalo.cse.phonelab.json.StrictJSONObject;

import java.util.ArrayList;
import java.util.List;

public class PopImapSyncAdapterService extends Service {
    private static final String TAG = "PopImapSyncService";

    private static final String MAYBE_TAG = "Maybe_Email_PhoneLab";
    private static final String SYNC_ACTION = "sync";

    private SyncAdapterImpl mSyncAdapter = null;

    public PopImapSyncAdapterService() {
        super();
    }

    private static class SyncAdapterImpl extends AbstractThreadedSyncAdapter {
        public SyncAdapterImpl(Context context) {
            super(context, true /* autoInitialize */);
        }

        @Override
        public void onPerformSync(android.accounts.Account account, Bundle extras,
                String authority, ContentProviderClient provider, SyncResult syncResult) {

            boolean uiRefresh = extras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, false);
            // TODO: get current sync frequency and compare with maybe, if diff use maybe to overwrite.
            int deltaMessageCount = extras.getInt(Mailbox.SYNC_EXTRA_DELTA_MESSAGE_COUNT, 0);
            boolean syncAutomatically = ContentResolver.getSyncAutomatically(account, EmailContent.AUTHORITY);
            final List<PeriodicSync> syncs = ContentResolver.getPeriodicSyncs(account, EmailContent.AUTHORITY);
            StrictJSONObject log = new StrictJSONObject(MAYBE_TAG)
                    .put(StrictJSONObject.KEY_ACTION, SYNC_ACTION)
                    .put("uiRefresh", uiRefresh)
                    .put("syncAutomatically", syncAutomatically)
                    .put("deltaMessageCount", deltaMessageCount);
            StrictJSONArray periodicSyncLog = new StrictJSONArray();
            for (final PeriodicSync sync : syncs) {
                int oldSyncInterval = (int) sync.period / 60;
                int syncInterval = 1;
                periodicSyncLog.put(new StrictJSONObject().put("period", oldSyncInterval));
                if (oldSyncInterval != syncInterval) {
                    // First remove all existing periodic syncs.
//                    ContentResolver.removePeriodicSync(account, EmailContent.AUTHORITY, sync.extras);
                    // Only positive values of sync interval indicate periodic syncs. The value is in minutes,
                    // while addPeriodicSync expects its time in seconds.
                    if (syncInterval > 0) {
                        ContentResolver.addPeriodicSync(account, EmailContent.AUTHORITY, Bundle.EMPTY,
                                syncInterval * DateUtils.MINUTE_IN_MILLIS / DateUtils.SECOND_IN_MILLIS);
                    }
                }
            }
            log.put("syncs", periodicSyncLog);

            PopImapSyncAdapterService.performSync(getContext(), account, extras, provider,
                    syncResult, log);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mSyncAdapter = new SyncAdapterImpl(getApplicationContext());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mSyncAdapter.getSyncAdapterBinder();
    }

    /**
     * @return whether or not this mailbox retrieves its data from the server (as opposed to just
     *     a local mailbox that is never synced).
     */
    private static boolean loadsFromServer(Context context, Mailbox m, String protocol) {
        String legacyImapProtocol = context.getString(R.string.protocol_legacy_imap);
        String pop3Protocol = context.getString(R.string.protocol_pop3);
        if (legacyImapProtocol.equals(protocol)) {
            // TODO: actually use a sync flag when creating the mailboxes. Right now we use an
            // approximation for IMAP.
            return m.mType != Mailbox.TYPE_DRAFTS
                    && m.mType != Mailbox.TYPE_OUTBOX
                    && m.mType != Mailbox.TYPE_SEARCH;

        } else if (pop3Protocol.equals(protocol)) {
            return Mailbox.TYPE_INBOX == m.mType;
        }

        return false;
    }

    private static StrictJSONObject sync(final Context context, final long mailboxId,
            final Bundle extras, final SyncResult syncResult, final boolean uiRefresh,
            final int deltaMessageCount) {
        StrictJSONObject strictJSONObject = new StrictJSONObject();
        TempDirectory.setTempDirectory(context);
        Mailbox mailbox = Mailbox.restoreMailboxWithId(context, mailboxId);
        if (mailbox == null) {
            strictJSONObject.put("fail", "mailbox is null");
            return strictJSONObject;
        }
        Account account = Account.restoreAccountWithId(context, mailbox.mAccountKey);
        if (account == null) {
            strictJSONObject.put("fail", "account is null");
            return strictJSONObject;
        }
        ContentResolver resolver = context.getContentResolver();
        String protocol = account.getProtocol(context);
        if ((mailbox.mType != Mailbox.TYPE_OUTBOX) &&
                !loadsFromServer(context, mailbox, protocol)) {
            // This is an update to a message in a non-syncing mailbox; delete this from the
            // updates table and return
            resolver.delete(Message.UPDATED_CONTENT_URI, MessageColumns.MAILBOX_KEY + "=?",
                    new String[] {Long.toString(mailbox.mId)});
            strictJSONObject.put("fail", "non-syncing mailbox");
            return strictJSONObject;
        }
        LogUtils.d(TAG, "About to sync mailbox: " + mailbox.mDisplayName);
        strictJSONObject.put("name", mailbox.mDisplayName);
        strictJSONObject.put("type", mailbox.mType);

        Uri mailboxUri = ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId);
        ContentValues values = new ContentValues();
        // Set mailbox sync state
        final int syncStatus = uiRefresh ? EmailContent.SYNC_STATUS_USER :
                EmailContent.SYNC_STATUS_BACKGROUND;
        values.put(Mailbox.UI_SYNC_STATUS, syncStatus);
        resolver.update(mailboxUri, values, null, null);
        try {
            int lastSyncResult;
            try {
                String legacyImapProtocol = context.getString(R.string.protocol_legacy_imap);
                if (mailbox.mType == Mailbox.TYPE_OUTBOX) {
                    EmailServiceStub.sendMailImpl(context, account.mId);
                } else {
                    lastSyncResult = UIProvider.createSyncValue(syncStatus,
                            EmailContent.LAST_SYNC_RESULT_SUCCESS);
                    EmailServiceStatus.syncMailboxStatus(resolver, extras, mailboxId,
                            EmailServiceStatus.IN_PROGRESS, 0, lastSyncResult);
                    final int status;
                    if (protocol.equals(legacyImapProtocol)) {
                        status = ImapService.synchronizeMailboxSynchronous(context, account,
                                mailbox, deltaMessageCount != 0, uiRefresh);
                    } else {
                        status = Pop3Service.synchronizeMailboxSynchronous(context, account,
                                mailbox, deltaMessageCount);
                    }
                    strictJSONObject.put("status", status);
                    strictJSONObject.put("lastSyncResult", lastSyncResult);
                    EmailServiceStatus.syncMailboxStatus(resolver, extras, mailboxId, status, 0,
                            lastSyncResult);
                }
            } catch (MessagingException e) {
                final int type = e.getExceptionType();
                // type must be translated into the domain of values used by EmailServiceStatus
                switch (type) {
                    case MessagingException.IOERROR:
                        lastSyncResult = UIProvider.createSyncValue(syncStatus,
                                EmailContent.LAST_SYNC_RESULT_CONNECTION_ERROR);
                        EmailServiceStatus.syncMailboxStatus(resolver, extras, mailboxId,
                                EmailServiceStatus.FAILURE, 0, lastSyncResult);
                        syncResult.stats.numIoExceptions++;
                        break;
                    case MessagingException.AUTHENTICATION_FAILED:
                        lastSyncResult = UIProvider.createSyncValue(syncStatus,
                                EmailContent.LAST_SYNC_RESULT_AUTH_ERROR);
                        EmailServiceStatus.syncMailboxStatus(resolver, extras, mailboxId,
                                EmailServiceStatus.FAILURE, 0, lastSyncResult);
                        syncResult.stats.numAuthExceptions++;
                        break;
                    case MessagingException.SERVER_ERROR:
                        lastSyncResult = UIProvider.createSyncValue(syncStatus,
                                EmailContent.LAST_SYNC_RESULT_SERVER_ERROR);
                        EmailServiceStatus.syncMailboxStatus(resolver, extras, mailboxId,
                                EmailServiceStatus.FAILURE, 0, lastSyncResult);
                        break;

                    default:
                        lastSyncResult = UIProvider.createSyncValue(syncStatus,
                                EmailContent.LAST_SYNC_RESULT_INTERNAL_ERROR);
                        EmailServiceStatus.syncMailboxStatus(resolver, extras, mailboxId,
                                EmailServiceStatus.FAILURE, 0, lastSyncResult);
                }
            }
        } finally {
            // Always clear our sync state and update sync time.
            values.put(Mailbox.UI_SYNC_STATUS, EmailContent.SYNC_STATUS_NONE);
            values.put(Mailbox.SYNC_TIME, System.currentTimeMillis());
            resolver.update(mailboxUri, values, null, null);
        }
        return strictJSONObject;
    }

    /**
     * Partial integration with system SyncManager; we initiate manual syncs upon request
     */
    private static void performSync(Context context, android.accounts.Account account,
            Bundle extras, ContentProviderClient provider, SyncResult syncResult, StrictJSONObject log) {
        // Find an EmailProvider account with the Account's email address
        Cursor c = null;
        StrictJSONArray syncArray = new StrictJSONArray();
        try {
            c = provider.query(com.android.emailcommon.provider.Account.CONTENT_URI,
                    Account.CONTENT_PROJECTION, AccountColumns.EMAIL_ADDRESS + "=?",
                    new String[] {account.name}, null);
            if (c != null && c.moveToNext()) {
                Account acct = new Account();
                acct.restore(c);
                log.put("name", acct.mDisplayName);
                if (extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD)) {
                    log.put("upload", true);
                    LogUtils.d(TAG, "Upload sync request for " + acct.mDisplayName);
                    // See if any boxes have mail...
                    ArrayList<Long> mailboxesToUpdate;
                    Cursor updatesCursor = provider.query(Message.UPDATED_CONTENT_URI,
                            new String[]{MessageColumns.MAILBOX_KEY},
                            MessageColumns.ACCOUNT_KEY + "=?",
                            new String[]{Long.toString(acct.mId)},
                            null);
                    try {
                        if ((updatesCursor == null) || (updatesCursor.getCount() == 0)) return;
                        mailboxesToUpdate = new ArrayList<Long>();
                        while (updatesCursor.moveToNext()) {
                            Long mailboxId = updatesCursor.getLong(0);
                            if (!mailboxesToUpdate.contains(mailboxId)) {
                                mailboxesToUpdate.add(mailboxId);
                            }
                        }
                    } finally {
                        if (updatesCursor != null) {
                            updatesCursor.close();
                        }
                    }
                    for (long mailboxId : mailboxesToUpdate) {
                        StrictJSONObject result = sync(context, mailboxId, extras, syncResult, false, 0);
                        syncArray.put(result);
                    }
                } else {
                    LogUtils.d(TAG, "Sync request for " + acct.mDisplayName);
                    LogUtils.d(TAG, extras.toString());
                    log.put("upload", false);

                    // We update our folder structure on every sync.
                    final EmailServiceProxy service =
                            EmailServiceUtils.getServiceForAccount(context, acct.mId);
                    service.updateFolderList(acct.mId);

                    // Get the id for the mailbox we want to sync.
                    long [] mailboxIds = Mailbox.getMailboxIdsFromBundle(extras);
                    if (mailboxIds == null || mailboxIds.length == 0) {
                        // No mailbox specified, just sync the inbox.
                        // TODO: IMAP may eventually want to allow multiple auto-sync mailboxes.
                        final long inboxId = Mailbox.findMailboxOfType(context, acct.mId,
                                Mailbox.TYPE_INBOX);
                        if (inboxId != Mailbox.NO_MAILBOX) {
                            mailboxIds = new long[1];
                            mailboxIds[0] = inboxId;
                        }
                    }

                    if (mailboxIds != null) {
                        boolean uiRefresh =
                            extras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, false);
                        int deltaMessageCount =
                                extras.getInt(Mailbox.SYNC_EXTRA_DELTA_MESSAGE_COUNT, 0);
                        for (long mailboxId : mailboxIds) {
                            StrictJSONObject result = sync(context, mailboxId, extras, syncResult, uiRefresh,
                                    deltaMessageCount);
                            syncArray.put(result);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            log.put("syncResults", syncArray);
            log.put("extras", extras.toString());
            log.log();
            if (c != null) {
                c.close();
            }
        }
    }
}
