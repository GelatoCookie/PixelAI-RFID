package com.zebra.rfid.demo.sdksample;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.zebra.rfid.api3.ReaderDevice;

import java.util.ArrayList;
import java.util.List;

/**
 * UI Adapter to display all available RFID reader names and handle user selection.
 */
class ReaderAdapter extends RecyclerView.Adapter<ReaderAdapter.ReaderViewHolder> {

    public interface OnReaderClickListener {
        void onReaderClick(ReaderDevice readerDevice);
    }

    private final List<ReaderDevice> readerList = new ArrayList<>();
    private final OnReaderClickListener listener;

    public ReaderAdapter(List<ReaderDevice> readers, OnReaderClickListener listener) {
        if (readers != null) {
            this.readerList.addAll(readers);
        }
        this.listener = listener;
    }

    @NonNull
    @Override
    public ReaderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_reader, parent, false);
        return new ReaderViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ReaderViewHolder holder, int position) {
        ReaderDevice device = readerList.get(position);
        String name = getDisplayName(device);
        holder.readerNameText.setText(name);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onReaderClick(device);
            }
        });
    }

    @Override
    public int getItemCount() {
        return readerList.size();
    }

    private String getDisplayName(ReaderDevice device) {
        return RfidUtility.toDisplayName(device == null ? null : device.getName());
    }

    static class ReaderViewHolder extends RecyclerView.ViewHolder {
        final TextView readerNameText;

        ReaderViewHolder(@NonNull View itemView) {
            super(itemView);
            readerNameText = itemView.findViewById(R.id.readerNameText);
        }
    }
}
