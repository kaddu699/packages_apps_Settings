/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settings.homepage.contextualcards;

import static com.android.settings.slices.CustomSliceRegistry.BLUETOOTH_DEVICES_SLICE_URI;
import static com.android.settings.slices.CustomSliceRegistry.CONTEXTUAL_WIFI_SLICE_URI;
import static com.android.settings.slices.CustomSliceRegistry.NOTIFICATION_CHANNEL_SLICE_URI;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.settings.R;
import com.android.settings.overlay.FeatureFactory;
import com.android.settingslib.utils.AsyncLoaderCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ContextualCardLoader extends AsyncLoaderCompat<List<ContextualCard>> {

    @VisibleForTesting
    static final int DEFAULT_CARD_COUNT = 4;
    static final int CARD_CONTENT_LOADER_ID = 1;
    static final long CARD_CONTENT_LOADER_TIMEOUT_MS = DateUtils.SECOND_IN_MILLIS * 3;

    private static final String TAG = "ContextualCardLoader";
    private static final long ELIGIBILITY_CHECKER_TIMEOUT_MS = 250;

    private final ExecutorService mExecutorService;
    private final ContentObserver mObserver = new ContentObserver(
            new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            if (isStarted()) {
                forceLoad();
            }
        }
    };

    private Context mContext;

    ContextualCardLoader(Context context) {
        super(context);
        mContext = context.getApplicationContext();
        mExecutorService = Executors.newCachedThreadPool();
    }

    @Override
    protected void onStartLoading() {
        super.onStartLoading();
        mContext.getContentResolver().registerContentObserver(CardContentProvider.URI,
                false /*notifyForDescendants*/, mObserver);
    }

    @Override
    protected void onStopLoading() {
        super.onStopLoading();
        mContext.getContentResolver().unregisterContentObserver(mObserver);
    }

    @Override
    protected void onDiscardResult(List<ContextualCard> result) {

    }

    @NonNull
    @Override
    public List<ContextualCard> loadInBackground() {
        final List<ContextualCard> result = new ArrayList<>();
        if (mContext.getResources().getBoolean(R.bool.config_use_legacy_suggestion)) {
            Log.d(TAG, "Skipping - in legacy suggestion mode");
            return result;
        }
        try (Cursor cursor = getContextualCardsFromProvider()) {
            if (cursor.getCount() > 0) {
                for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                    final ContextualCard card = new ContextualCard(cursor);
                    if (card.isCustomCard()) {
                        //TODO(b/114688391): Load and generate custom card,then add into list
                    } else if (isLargeCard(card)) {
                        result.add(card.mutate().setIsLargeCard(true).build());
                    } else {
                        result.add(card);
                    }
                }
            }
        }
        return getDisplayableCards(result);
    }

    // Get final displayed cards and log what cards will be displayed/hidden
    @VisibleForTesting
    List<ContextualCard> getDisplayableCards(List<ContextualCard> candidates) {
        final List<ContextualCard> eligibleCards = filterEligibleCards(candidates);
        final List<ContextualCard> visibleCards = new ArrayList<>();
        final List<ContextualCard> hiddenCards = new ArrayList<>();

        final int size = eligibleCards.size();
        for (int i = 0; i < size; i++) {
            if (i < DEFAULT_CARD_COUNT) {
                visibleCards.add(eligibleCards.get(i));
            } else {
                hiddenCards.add(eligibleCards.get(i));
            }
        }

        try {
            // The maximum cards are four small cards OR
            // one large card with two small cards OR
            // two large cards
            if (visibleCards.size() <= 2 || getNumberOfLargeCard(visibleCards) == 0) {
                // four small cards
                return visibleCards;
            }

            if (visibleCards.size() == DEFAULT_CARD_COUNT) {
                hiddenCards.add(visibleCards.remove(visibleCards.size() - 1));
            }

            if (getNumberOfLargeCard(visibleCards) == 1) {
                // One large card with two small cards
                return visibleCards;
            }

            hiddenCards.add(visibleCards.remove(visibleCards.size() - 1));

            // Two large cards
            return visibleCards;
        } finally {
            //TODO(b/121196921): Should not call this if user click dismiss
            final ContextualCardFeatureProvider contextualCardFeatureProvider =
                    FeatureFactory.getFactory(mContext).getContextualCardFeatureProvider(mContext);
            contextualCardFeatureProvider.logContextualCardDisplay(visibleCards, hiddenCards);
        }
    }

    @VisibleForTesting
    Cursor getContextualCardsFromProvider() {
        return CardDatabaseHelper.getInstance(mContext).getContextualCards();
    }

    @VisibleForTesting
    List<ContextualCard> filterEligibleCards(List<ContextualCard> candidates) {
        final List<ContextualCard> cards = new ArrayList<>();
        final List<Future<ContextualCard>> eligibleCards = new ArrayList<>();

        for (ContextualCard card : candidates) {
            final EligibleCardChecker future = new EligibleCardChecker(mContext, card);
            eligibleCards.add(mExecutorService.submit(future));
        }
        // Collect future and eligible cards
        for (Future<ContextualCard> cardFuture : eligibleCards) {
            try {
                //TODO(b/124492762): Log latency and timeout occurrence.
                final ContextualCard card = cardFuture.get(ELIGIBILITY_CHECKER_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS);
                if (card != null) {
                    cards.add(card);
                }
            } catch (ExecutionException | InterruptedException | TimeoutException e) {
                Log.w(TAG, "Failed to get eligible state for card, likely timeout. Skipping", e);
            }
        }
        return cards;
    }

    private int getNumberOfLargeCard(List<ContextualCard> cards) {
        return (int) cards.stream()
                .filter(card -> isLargeCard(card))
                .count();
    }

    private boolean isLargeCard(ContextualCard card) {
        return card.getSliceUri().equals(CONTEXTUAL_WIFI_SLICE_URI)
                || card.getSliceUri().equals(BLUETOOTH_DEVICES_SLICE_URI)
                || card.getSliceUri().equals(NOTIFICATION_CHANNEL_SLICE_URI);
    }

    public interface CardContentLoaderListener {
        void onFinishCardLoading(List<ContextualCard> contextualCards);
    }
}
