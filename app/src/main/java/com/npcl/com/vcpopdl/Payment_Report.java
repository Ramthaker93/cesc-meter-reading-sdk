package com.npcl.com.vcpopdl;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;

import android.database.Cursor;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.view.ViewGroup.LayoutParams;
import android.graphics.Typeface;
import android.graphics.Color;
import  androidx.appcompat.view.ContextThemeWrapper;

import android.widget.Toast;
import android.widget.EditText;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;


public class Payment_Report extends AppCompatActivity {


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try
        {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_payment__report);
            StringBuilder sb = new StringBuilder();
            Bundle bundle = getIntent().getExtras();
            String text = bundle.getString("user");

            String User = text;
            EditText txtuser = (EditText) findViewById(R.id.txtuser)  ;
            txtuser.setText(User);


            sb.append("1").append(";").append("cash").append(";").append("300").append("_");
            sb.append("2").append(";").append("Cheuqe").append(";").append("3000").append("_");
            sb.append("3").append(";").append("Card").append(";").append("500").append("_");
            String Sql = "select  PaymentMode , AmountPaid from ( " +
                    "select PaymentMode,sum(AmountPaid) AmountPaid  from PaymentDetails group by  PaymentMode " +
                    "union " +
                    "select 'Total' PaymentMode,sum(AmountPaid) AmountPaid  from PaymentDetails  " +
                    ") order by PaymentMode ";
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            GridView gd;
            ArrayList list = new ArrayList<String>();
            gd = (GridView) findViewById(R.id.grid_view);

            int i = 0;

            Cursor c = db.GetData(Sql);
            //Move the cursor to the first row.
            if (c.moveToFirst()) {
                do {
                    String Data = "";
                    if (i == 0) {

                        Data = "Sno" + AddSpace(5, 3) + "Payment Mode" + AddSpace(15, 12) + "Amount Paid" + AddSpace(20, 11);
                        list.add(Data);
                        Data = "-------------------------------------------------------";
                        list.add(Data);
                    }
                    i = i + 1;
                    String PaymentMode = c.getString(c.getColumnIndex("PaymentMode"));
                    String AmountPaid = c.getString(c.getColumnIndex("AmountPaid"));
                    Data = "";
                    if (PaymentMode.equals("Total")) {
                        Data = "-------------------------------------------------------";
                        list.add(Data);
                        Data = AddSpace(5, 0) + PaymentMode + AddSpace(30, PaymentMode.length()) + AmountPaid + AddSpace(40, AmountPaid.length());
                    } else {
                        Data = i + AddSpace(5, 1) + PaymentMode + AddSpace(30, PaymentMode.length()) + AmountPaid + AddSpace(40, AmountPaid.length());
                        //add in to array list
                    }
                    list.add(Data);

                } while (c.moveToNext());//Move the cursor to the next row.

                ArrayAdapter<String> adp = new ArrayAdapter<String>(Payment_Report.this, android.R.layout.simple_list_item_1, list);
                gd.setAdapter(adp);
            } else {
                Toast.makeText(getApplicationContext(), "No data found", Toast.LENGTH_LONG).show();
            }

        }
        catch (Exception ex)
        {
            Toast.makeText(getApplicationContext(), "No data found", Toast.LENGTH_LONG).show();
        }
    }



    public String AddSpace(int TotalLen, int Occp)
    {
        String Result ="";
        for (int i=0; i<(TotalLen-Occp); i++)
        {
            Result = Result + " " ;
        }
        return Result;
    }

}
