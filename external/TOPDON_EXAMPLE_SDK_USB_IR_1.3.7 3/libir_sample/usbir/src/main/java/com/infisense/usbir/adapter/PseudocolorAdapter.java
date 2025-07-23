package com.infisense.usbir.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.infisense.usbir.R;
import com.infisense.usbir.bean.PseudocolorBean;

import java.util.ArrayList;

/*
 * @Description:
 * @Author:         brilliantzhao
 * @CreateDate:     2021.12.10 10:20
 * @UpdateUser:
 * @UpdateDate:     2021.12.10 10:20
 * @UpdateRemark:
 */
public class PseudocolorAdapter extends RecyclerView.Adapter<PseudocolorAdapter.ViewHolder> {

    private Context context;
    private ArrayList<PseudocolorBean> mDataList;
    private OnItemOnclickListenter listenter;

    public interface OnItemOnclickListenter {
        void onClick(int position);
    }

    /**
     * @param context
     * @param mMyLiveList
     * @param listenter
     */
    public PseudocolorAdapter(Context context, ArrayList<PseudocolorBean> mMyLiveList, OnItemOnclickListenter listenter) {
        this.context = context;
        this.mDataList = mMyLiveList;
        this.listenter = listenter;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_pseudocolor_filter, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, @SuppressLint("RecyclerView") int position) {
        PseudocolorBean filterBean = mDataList.get(position);
        holder.tvName.setText(filterBean.getTitleName());
        holder.Background_iv.setImageResource(filterBean.getImg());
        holder.rlRoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listenter.onClick(position);
            }
        });
    }

    @Override
    public int getItemCount() {
        return mDataList.size();
    }

    /**
     *
     */
    class ViewHolder extends RecyclerView.ViewHolder {

        TextureView textureView;
        TextView tvName;
        ImageView Background_iv;
        RelativeLayout rlRoot;

        ViewHolder(View view) {
            super(view);
            textureView = view.findViewById(R.id.textureView);
            tvName = view.findViewById(R.id.tv_Name);
            Background_iv = view.findViewById(R.id.Background_iv);
            rlRoot = view.findViewById(R.id.rl_root);
        }
    }
}
