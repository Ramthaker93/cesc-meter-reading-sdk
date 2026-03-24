package com.npcl.com.vcpopdl;

import java.util.ArrayList;

import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.content.Context;
public class CustomAdapter  extends ArrayAdapter {

    private ArrayList dataSet;
    Context mContext;

    // View lookup cache
    private static class ViewHolder {
        TextView txtName;

    }

    public CustomAdapter(ArrayList data, Context context) {
        super(context, R.layout.support_simple_spinner_dropdown_item, data);
        this.dataSet = data;
        this.mContext = context;

    }





}
