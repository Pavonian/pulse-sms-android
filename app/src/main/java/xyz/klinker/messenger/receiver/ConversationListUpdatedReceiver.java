/*
 * Copyright (C) 2016 Jacob Klinker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.klinker.messenger.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.util.List;

import xyz.klinker.messenger.adapter.ConversationListAdapter;
import xyz.klinker.messenger.data.DataSource;
import xyz.klinker.messenger.data.SectionType;
import xyz.klinker.messenger.data.model.Conversation;
import xyz.klinker.messenger.fragment.ConversationListFragment;

/**
 * Receiver that handles changing the conversation list when a new message is received. The logic
 * here can be quite tricky because of the pinned section and the section headers in the adapter.
 *
 * We either need to create a new today section under the pinned section if one does not exist, or
 * we need to add an extra item to the today section and remove that item from below, depending
 * on whether we've received a new conversation or are just updating an old one.
 */
public class ConversationListUpdatedReceiver extends BroadcastReceiver {

    private static final String ACTION_UPDATED = "xyz.klinker.messenger.CONVERSATION_UPDATED";
    private static final String EXTRA_CONVERSATION_ID = "conversation_id";
    private static final String EXTRA_SNIPPET = "snippet";
    private static final String EXTRA_READ = "read";

    private ConversationListFragment fragment;

    public ConversationListUpdatedReceiver(ConversationListFragment fragment) {
        this.fragment = fragment;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!fragment.isAdded()) {
            return;
        }

        long conversationId = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1);
        String snippet = intent.getStringExtra(EXTRA_SNIPPET);
        boolean read = intent.getBooleanExtra(EXTRA_READ, false);

        if (conversationId == -1 || fragment.getExpandedId() == conversationId) {
            return;
        }

        ConversationListAdapter adapter = fragment.getAdapter();
        int adapterPosition = adapter.findPositionForConversationId(conversationId);
        boolean insertToday = adapter.getCountForSection(SectionType.TODAY) == 0;
        int pinnedCount = adapter.getCountForSection(SectionType.PINNED);
        List<Conversation> conversations = adapter.getConversations();
        List<SectionType> sectionTypes = adapter.getSectionCounts();

        if (adapterPosition == -1) {
            DataSource source = DataSource.getInstance(context);
            source.open();
            Conversation conversation = source.getConversation(conversationId);
            source.close();

            // need to insert after the pinned conversations
            conversations.add(pinnedCount, conversation);
        } else {
            int position = -1;
            for (int i = 0; i < conversations.size(); i++) {
                if (conversations.get(i).id == conversationId) {
                    position = i;
                    break;
                }
            }

            if (position == -1) {
                return;
            }

            if (position <= pinnedCount) {
                // if it is already pinned or the top item that isn't pinned, just mark the read
                // and snippet changes
                Conversation conversation = conversations.get(position);
                conversation.snippet = snippet;
                conversation.read = read;
                adapter.notifyItemChanged(adapterPosition);
                return;
            } else {
                // remove, update, and reinsert conversation to appropriate place
                Conversation conversation = conversations.get(position);
                adapter.removeItem(adapterPosition, false);

                conversation.snippet = snippet;
                conversation.read = read;
                conversations.add(pinnedCount, conversation);
            }
        }

        if (insertToday) {
            // no today section exists, so we'll need to insert one. we need to check if pinned
            // conversations exist. if they do, then insert today in the second slot, if not then
            // insert it into the first slot.

            SectionType type = new SectionType(SectionType.TODAY, 1);
            if (pinnedCount == 0) {
                sectionTypes.add(0, type);
                adapter.notifyItemRangeInserted(0, 2);
            } else {
                sectionTypes.add(1, type);

                // add one to pinned count to include the header
                adapter.notifyItemRangeInserted(pinnedCount + 1, 2);
            }
        } else {
            if (pinnedCount == 0) {
                sectionTypes.get(0).count++;
                adapter.notifyItemInserted(1);
            } else {
                sectionTypes.get(1).count++;

                // add 2 here for the pinned header and today header
                adapter.notifyItemInserted(pinnedCount + 2);
            }
        }
    }

    /**
     * Sends a broadcast to anywhere that has registered this receiver to let it know to update.
     */
    public static void sendBroadcast(Context context, long conversationId, String snippet,
                                     boolean read) {
        Intent intent = new Intent(ACTION_UPDATED);
        intent.putExtra(EXTRA_CONVERSATION_ID, conversationId);
        intent.putExtra(EXTRA_SNIPPET, snippet);
        intent.putExtra(EXTRA_READ, read);
        context.sendBroadcast(intent);
    }

    /**
     * Gets an intent filter that will pick up these broadcasts.
     */
    public static IntentFilter getIntentFilter() {
        return new IntentFilter(ACTION_UPDATED);
    }

}
