/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.contacts.list;

import com.google.common.collect.Lists;

import android.content.ContentValues;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.provider.ContactsContract.ProviderStatus;
import android.util.Log;

import java.util.ArrayList;

/**
 * A singleton that keeps track of the last known provider status.
 *
 * All methods must be called on the UI thread unless noted otherwise.
 *
 * All members must be set on the UI thread unless noted otherwise.
 */
public class ProviderStatusWatcher extends ContentObserver {
    private static final String TAG = "ProviderStatusWatcher";
    private static final boolean DEBUG = false;

    /**
     * Callback interface invoked when the provider status changes.
     */
    public interface ProviderStatusListener {
        public void onProviderStatusChange();
    }

    private static final String[] PROJECTION = new String[] {
        ProviderStatus.STATUS,
        ProviderStatus.DATA1
    };

    /**
     * We'll wait for this amount of time on the UI thread if the load hasn't finished.
     */
    private static final int LOAD_WAIT_TIMEOUT_MS = 1000;

    private static final int STATUS_UNKNOWN = -1;

    private static ProviderStatusWatcher sInstance;

    private final Context mContext;
    private final Handler mHandler = new Handler();

    private final Object mSignal = new Object();

    private int mStartRequestedCount;

    private LoaderTask mLoaderTask;

    /** Last known provider status.  This can be changed on a worker thread. */
    private int mProviderStatus = STATUS_UNKNOWN;

    /** Last known provider status data.  This can be changed on a worker thread. */
    private String mProviderData;

    private final ArrayList<ProviderStatusListener> mListeners = Lists.newArrayList();

    private final Runnable mStartLoadingRunnable = new Runnable() {
        @Override
        public void run() {
            startLoading();
        }
    };

    /**
     * Returns the singleton instance.
     */
    public synchronized static ProviderStatusWatcher getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new ProviderStatusWatcher(context);
        }
        return sInstance;
    }

    private ProviderStatusWatcher(Context context) {
        super(null);
        mContext = context;
    }

    /** Add a listener. */
    public void addListener(ProviderStatusListener listener) {
        mListeners.add(listener);
    }

    /** Remove a listener */
    public void removeListener(ProviderStatusListener listener) {
        mListeners.remove(listener);
    }

    private void notifyListeners() {
        if (DEBUG) {
            Log.d(TAG, "notifyListeners: " + mListeners.size());
        }
        if (isStarted()) {
            for (ProviderStatusListener listener : mListeners) {
                listener.onProviderStatusChange();
            }
        }
    }

    private boolean isStarted() {
        return mStartRequestedCount > 0;
    }

    /**
     * Starts watching the provider status.  {@link #start()} and {@link #stop()} calls can be
     * nested.
     */
    public void start() {
        if (++mStartRequestedCount == 1) {
            mContext.getContentResolver()
                .registerContentObserver(ProviderStatus.CONTENT_URI, false, this);
            startLoading();

            if (DEBUG) {
                Log.d(TAG, "Start observing");
            }
        }
    }

    /**
     * Stops watching the provider status.
     */
    public void stop() {
        if (!isStarted()) {
            Log.e(TAG, "Already stopped");
            return;
        }
        if (--mStartRequestedCount == 0) {

            mHandler.removeCallbacks(mStartLoadingRunnable);

            mContext.getContentResolver().unregisterContentObserver(this);
            if (DEBUG) {
                Log.d(TAG, "Stop observing");
            }
        }
    }

    /**
     * @return last known provider status.
     *
     * If this method is called when we haven't started the status query or the query is still in
     * progress, it will start a query in a worker thread if necessary, and *wait for the result*.
     *
     * This means this method is essentially a blocking {@link ProviderStatus#CONTENT_URI} query.
     * This URI is not backed by the file system, so is usually fast enough to perform on the main
     * thread, but in extreme cases (when the system takes a while to bring up the contacts
     * provider?) this may still cause ANRs.
     *
     * In order to avoid that, if we can't load the status within {@link #LOAD_WAIT_TIMEOUT_MS},
     * we'll give up and just returns {@link ProviderStatus#STATUS_UPGRADING} in order to unblock
     * the UI thread.  The actual result will be delivered later via {@link ProviderStatusListener}.
     * (If {@link ProviderStatus#STATUS_UPGRADING} is returned, the app (should) shows an according
     * message, like "contacts are being updated".)
     */
    public int getProviderStatus() {
        waitForLoaded();

        if (mProviderStatus == STATUS_UNKNOWN) {
            return ProviderStatus.STATUS_UPGRADING;
        }

        return mProviderStatus;
    }

    /**
     * @return last known provider status data.  See also {@link #getProviderStatus()}.
     */
    public String getProviderStatusData() {
        waitForLoaded();

        if (mProviderStatus == STATUS_UNKNOWN) {
            // STATUS_UPGRADING has no data.
            return "";
        }

        return mProviderData;
    }

    private void waitForLoaded() {
        if (mProviderStatus == STATUS_UNKNOWN) {
            if (mLoaderTask == null) {
                // For some reason the loader couldn't load the status.  Let's start it again.
                startLoading();
            }
            synchronized (mSignal) {
                try {
                    mSignal.wait(LOAD_WAIT_TIMEOUT_MS);
                } catch (InterruptedException ignore) {
                }
            }
        }
    }

    private void startLoading() {
        if (mLoaderTask != null) {
            return; // Task already running.
        }

        if (DEBUG) {
            Log.d(TAG, "Start loading");
        }

        mLoaderTask = new LoaderTask();
        mLoaderTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private class LoaderTask extends AsyncTask<Void, Void, Boolean> {
        @Override
        protected Boolean doInBackground(Void... params) {
            try {
                Cursor cursor = mContext.getContentResolver().query(ProviderStatus.CONTENT_URI,
                        PROJECTION, null, null, null);
                if (cursor != null) {
                    try {
                        if (cursor.moveToFirst()) {
                            mProviderStatus = cursor.getInt(0);
                            mProviderData = cursor.getString(1);
                            return true;
                        }
                    } finally {
                        cursor.close();
                    }
                }
                return false;
            } finally {
                synchronized (mSignal) {
                    mSignal.notifyAll();
                }
            }
        }

        @Override
        protected void onCancelled(Boolean result) {
            cleanUp();
        }

        @Override
        protected void onPostExecute(Boolean loaded) {
            cleanUp();
            if (loaded != null && loaded) {
                notifyListeners();
            }
        }

        private void cleanUp() {
            mLoaderTask = null;
        }
    }

    /**
     * Called when provider status may has changed.
     *
     * This method will be called on a worker thread by the framework.
     */
    @Override
    public void onChange(boolean selfChange, Uri uri) {
        if (!ProviderStatus.CONTENT_URI.equals(uri)) return;

        // Provider status change is rare, so okay to log.
        Log.i(TAG, "Provider status changed.");

        mHandler.removeCallbacks(mStartLoadingRunnable); // Remove one in the queue, if any.
        mHandler.post(mStartLoadingRunnable);
    }

    /**
     * Sends a provider status update, which will trigger a retry of database upgrade
     */
    public void retryUpgrade() {
        Log.i(TAG, "retryUpgrade");
        final AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                ContentValues values = new ContentValues();
                values.put(ProviderStatus.STATUS, ProviderStatus.STATUS_UPGRADING);
                mContext.getContentResolver().update(ProviderStatus.CONTENT_URI, values,
                        null, null);
                return null;
            }
        };
        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
}