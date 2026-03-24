package com.npcl.com.vcpopdl;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.database.Cursor;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.GridView;

import java.util.ArrayList;

public class PaymentCommanReport extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_payment_comman_report);
        Bundle bundle = getIntent().getExtras();
        String text = bundle.getString("user");
        String[] Temp = text.split("#");
        String ReportType =Temp[0].toString();
        String User = Temp[1].toString();
        EditText txtuser = (EditText) findViewById(R.id.txtuser)  ;
        txtuser.setText(User);
        EditText ReportHeader = (EditText) findViewById(R.id.txtReportheader)  ;



        if (ReportType.equals ("PD"))
        {

            ReportHeader.setText("Total Collection Datewise");

            String Sql = "select level, ConsumerNo, paymentMode, case when  paydate='31-12-9999' then '----' else paydate end paydate ,amountPaid from( " +
                    " select level, ConsumerNo, paymentMode, amountPaid, paydate from( " +
                    " select '1' level, ConsumerNo, paymentMode, amountPaid,  substr(paymentdate,1,2)||'-'|| substr(paymentdate,3,2)||'-'|| substr(paymentdate,5,4)paydate  from PaymentDetails group by consumerno, paymentMode, amountPaid\n" +
                    " union " +
                    " select '2' level,'---SubToal---' ConsumerNo,'----' paymentMode, sum(amountPaid) ,paydate from( " +
                    " select consumerno, paymentMode, amountPaid,paydate from " +
                    " ( " +
                    " select  ConsumerNo, paymentMode, amountPaid,  substr(paymentdate,1,2)||'-'|| substr(paymentdate,3,2)||'-'|| substr(paymentdate,5,4)paydate  from PaymentDetails group by consumerno, paymentMode, amountPaid " +
                    " ) )group by paydate " +
                    " union " +
                    " select '3' level,'------Grand Total-----' ConsumerNo,'---' paymentMode, sum(amountPaid) ,'31-12-9999'paydate  from PaymentDetails " +
                    " )  order by paydate,level )";

            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            GridView gd;
            ArrayList list=new ArrayList<String>();
            gd=(GridView)findViewById(R.id.grid_view);

            int i=0;

            Cursor c=db.GetData(Sql);
            //Move the cursor to the first row.
            if(c.moveToFirst()) {
                do {
                    String Data = "";
                    String ConsumerNo = c.getString(c.getColumnIndex("ConsumerNo"));
                    String PaymentMode = c.getString(c.getColumnIndex("paymentMode"));
                    String PaymentDate = c.getString(c.getColumnIndex("paydate"));
                    String AmountPaid = c.getString(c.getColumnIndex("amountPaid"));



                    Data = PaymentDate + "::" + ConsumerNo + "::" + PaymentMode + "::" + AmountPaid;
                    //add in to array list
                    if (Data.contains("SubToal")) {
                        list.add("-------------------------------------------------");
                        Data = PaymentDate + "----------SubToal----------" + AmountPaid;
                    }
                    if (Data.contains("Grand Total"))
                        Data =  "*********Grand Total************" + AmountPaid;
                    list.add(Data);

                } while (c.moveToNext());//Move the cursor to the next row.

                ArrayAdapter<String> adp = new ArrayAdapter<String>(PaymentCommanReport.this, android.R.layout.simple_list_item_1, list);
                gd.setAdapter(adp);
            }

        }
        if (ReportType.equals ("PPFULD"))
        {
            ReportHeader.setText("Payment Pending For Uploading");
            String Sql = "select  ConsumerNo, PaymentMode,  substr(paymentdate,1,2)||'-'|| substr(paymentdate,3,2)||'-'|| substr(paymentdate,5,4)paydate ,AmountPaid    from PaymentDetails where UploadFlag=0";
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            GridView gd;
            ArrayList list=new ArrayList<String>();
            gd=(GridView)findViewById(R.id.grid_view);

            int i=0;

            Cursor c=db.GetData(Sql);
            //Move the cursor to the first row.
            if(c.moveToFirst()) {
                do {
                    String Data = "";
                    String ConsumerNo = c.getString(c.getColumnIndex("ConsumerNo"));
                    String PaymentMode = c.getString(c.getColumnIndex("PaymentMode"));
                    String PaymentDate = c.getString(c.getColumnIndex("paydate"));
                    String AmountPaid = c.getString(c.getColumnIndex("AmountPaid"));
                    Data = PaymentDate + "::" + ConsumerNo + "::" + PaymentMode + "::" + AmountPaid;
                    list.add(Data);

                } while (c.moveToNext());//Move the cursor to the next row.

                ArrayAdapter<String> adp = new ArrayAdapter<String>(PaymentCommanReport.this, android.R.layout.simple_list_item_1, list);
                gd.setAdapter(adp);
            }
        }

        if (ReportType.equals ("PULD"))
        {
            ReportHeader.setText("Payment Uploaded");
            String Sql = "select  ConsumerNo, PaymentMode,  substr(paymentdate,1,2)||'-'|| substr(paymentdate,3,2)||'-'|| substr(paymentdate,5,4)paydate ,AmountPaid    from PaymentDetails where UploadFlag=1";
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            GridView gd;
            ArrayList list=new ArrayList<String>();
            gd=(GridView)findViewById(R.id.grid_view);

            int i=0;

            Cursor c=db.GetData(Sql);
            //Move the cursor to the first row.
            if(c.moveToFirst()) {
                do {
                    String Data = "";
                    String ConsumerNo = c.getString(c.getColumnIndex("ConsumerNo"));
                    String PaymentMode = c.getString(c.getColumnIndex("PaymentMode"));
                    String PaymentDate = c.getString(c.getColumnIndex("paydate"));
                    String AmountPaid = c.getString(c.getColumnIndex("AmountPaid"));
                    Data = PaymentDate + "::" + ConsumerNo + "::" + PaymentMode + "::" + AmountPaid;
                    list.add(Data);

                } while (c.moveToNext());//Move the cursor to the next row.

                ArrayAdapter<String> adp = new ArrayAdapter<String>(PaymentCommanReport.this, android.R.layout.simple_list_item_1, list);
                gd.setAdapter(adp);
            }
        }

    }
}
