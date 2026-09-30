package com.zebra.rfid.demo.sdksample;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.zebra.rfid.api3.TagData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class TagAdapter extends RecyclerView.Adapter<TagAdapter.TagViewHolder> {

    static class TagItem {
        final String tagId;
        String rssi;
        int count;

        TagItem(String tagId, String rssi) {
            this.tagId = tagId;
            this.rssi = rssi;
            this.count = 1;
        }
    }

    private final List<TagItem> tags = new ArrayList<>();
    private final Map<String, Integer> tagIndexMap = new HashMap<>();
    private int totalReads = 0;

    /**
     * Processes a batch of tags from the RFID reader.
     * Updates existing unique tags in-place and inserts new unique tag rows.
     * Uses optimized notify calls to minimize UI redraws.
     *
     * @return true if at least one new unique tag was added to the list.
     */
    boolean addOrUpdateTags(TagData[] tagDataList) {
        if (tagDataList == null || tagDataList.length == 0) {
            return false;
        }

        boolean newTagAdded = false;
        int firstNewTagIndex = tags.size();
        int newTagsCount = 0;
        
        // Track which existing indices were modified to batch updates
        Set<Integer> changedIndices = new HashSet<>();

        for (TagData tag : tagDataList) {
            if (tag == null || tag.getTagID() == null) {
                continue;
            }

            String tagId = tag.getTagID();
            String rssi = String.valueOf(tag.getPeakRSSI());
            totalReads++;

            Integer index = tagIndexMap.get(tagId);
            if (index != null) {
                TagItem item = tags.get(index);
                item.rssi = rssi;
                item.count++;
                if (index < firstNewTagIndex) {
                    changedIndices.add(index);
                }
            } else {
                TagItem newItem = new TagItem(tagId, rssi);
                int newIndex = tags.size();
                tags.add(newItem);
                tagIndexMap.put(tagId, newIndex);
                newTagsCount++;
                newTagAdded = true;
            }
        }

        // Notify for existing items that were updated
        for (Integer index : changedIndices) {
            notifyItemChanged(index);
        }

        // Notify for all new items in a single range call
        if (newTagsCount > 0) {
            notifyItemRangeInserted(firstNewTagIndex, newTagsCount);
        }

        return newTagAdded;
    }

    void clear() {
        int size = tags.size();
        tags.clear();
        tagIndexMap.clear();
        totalReads = 0;
        if (size > 0) {
            notifyItemRangeRemoved(0, size);
        }
    }

    int getUniqueTagCount() {
        return tags.size();
    }

    int getTotalReadCount() {
        return totalReads;
    }

    @NonNull
    @Override
    public TagViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_tag, parent, false);
        return new TagViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TagViewHolder holder, int position) {
        TagItem item = tags.get(position);
        Context context = holder.itemView.getContext();
        holder.tagIdText.setText(item.tagId);
        holder.tagCountText.setText(context.getString(R.string.label_count_format, item.count));
        holder.rssiText.setText(context.getString(R.string.label_rssi_format, item.rssi));
    }

    @Override
    public int getItemCount() {
        return tags.size();
    }

    static class TagViewHolder extends RecyclerView.ViewHolder {
        final TextView tagIdText;
        final TextView tagCountText;
        final TextView rssiText;

        TagViewHolder(@NonNull View itemView) {
            super(itemView);
            tagIdText = itemView.findViewById(R.id.tagIdText);
            tagCountText = itemView.findViewById(R.id.tagCountText);
            rssiText = itemView.findViewById(R.id.rssiText);
        }
    }
}
