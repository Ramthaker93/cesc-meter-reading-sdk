package com.npcl.com.vcpopdl;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.content.Intent;
import android.database.Cursor;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

public class CollectionHome extends AppCompatActivity {
    EditText eduser;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_collection_home);
        Bundle bundle = getIntent().getExtras();
        String text= bundle.getString("user");
        eduser = (EditText)findViewById(R.id.txtuser);
        eduser.setText(text);
        Button btnPayDownloaddata = (Button) findViewById(R.id.btnPayDownloaddata);
        Button btnPaymentCollectR = (Button) findViewById(R.id.btnPaymentCollectR);
        Button btnPaymentNotUploaded = (Button) findViewById(R.id.btnPaymentNotUploaded);
        Button btnPaymentUploaded = (Button) findViewById(R.id.btnPaymentUploaded);


        /* Paymebt Data download */
        DatabaseHandler db = new DatabaseHandler(getApplicationContext());
        String Sql="select count(*) paycnt from PaymentData";
        Cursor c = db.GetData(Sql);
        int i=c.getCount();
        if (i >0 && c!=null ) {
            c.moveToNext();
        }
        if (i >0 && c!=null )
        {
            do {
                String paycnt = c.getString(c.getColumnIndex("paycnt"));
                btnPayDownloaddata.setText("Total Payment Data Downloaded :::::  "+ paycnt);
            } while (c.moveToNext());//Move the cursor to the next row.

        }
        /*  Payment collect */
        db = new DatabaseHandler(getApplicationContext());
        Sql="select count(*) paycnt from PaymentDetails";
        c = db.GetData(Sql);
        i=0;
        i=c.getCount();
        if (i >0 && c!=null ) {
            c.moveToNext();
        }
        if (i >0 && c!=null )
        {
            do {
                String paycnt = c.getString(c.getColumnIndex("paycnt"));
                btnPaymentCollectR.setText("Total Payment Collection :::::  "+ paycnt);
            } while (c.moveToNext());//Move the cursor to the next row.

        }
        /*  Payment Data not uploaded */
        db = new DatabaseHandler(getApplicationContext());
        Sql="select count(*) paycnt from PaymentDetails where UploadFlag=0";
        c = db.GetData(Sql);
        i=0;
        i=c.getCount();
        if (i >0 && c!=null ) {
            c.moveToNext();
        }
        if (i >0 && c!=null )
        {
            do {
                String paycnt = c.getString(c.getColumnIndex("paycnt"));
                btnPaymentNotUploaded.setText("Payment pending for uploading :::::  "+ paycnt);
            } while (c.moveToNext());//Move the cursor to the next row.

        }
        /*  Payment Data  uploaded */
        db = new DatabaseHandler(getApplicationContext());
        Sql="select count(*) paycnt from PaymentDetails where UploadFlag>0";
        c = db.GetData(Sql);
        i=0;
        i=c.getCount();
        if (i >0 && c!=null ) {
            c.moveToNext();
        }
        if (i >0 && c!=null )
        {
            do {
                String paycnt = c.getString(c.getColumnIndex("paycnt"));
                btnPaymentUploaded.setText("Payment  uploaded :::::  "+ paycnt);
            } while (c.moveToNext());//Move the cursor to the next row.

        }
    }

    public void onbtnbtnPaymentReport(View v)
    {
        Intent i = new Intent(CollectionHome.this, Payment_Report.class);
        Bundle b = new Bundle();
        eduser = (EditText)findViewById(R.id.txtuser);
        b.putString("user", eduser.getText().toString());
        i.putExtras(b);
        startActivity(i);

    }
    public void onbtnbtnPaymentSearch(View v)
    {

        Intent i = new Intent(CollectionHome.this, PaySearch.class);
        Bundle b = new Bundle();
        eduser = (EditText)findViewById(R.id.txtuser);
        b.putString("user", eduser.getText().toString());
        i.putExtras(b);
        startActivity(i);
    }

    public void onbtnbtnPaymentReportdet(View v)
    {
        Intent i = new Intent(CollectionHome.this, Payment_Detail.class);
        Bundle b = new Bundle();
        eduser = (EditText)findViewById(R.id.txtuser);
        b.putString("user", eduser.getText().toString());
        i.putExtras(b);
        startActivity(i);

    }
    public void onClickbtnPaymentCollectR(View v)
    {
        //payment date wise
        Intent i = new Intent(CollectionHome.this, PaymentCommanReport.class);
        Bundle b = new Bundle();
        eduser = (EditText)findViewById(R.id.txtuser);
        b.putString("user","PD#"+ eduser.getText().toString());
        i.putExtras(b);
        startActivity(i);

    }

    public void OnclickbtnPaymentUploaded(View v)
    {
        //payment date wise
        Intent i = new Intent(CollectionHome.this, PaymentCommanReport.class);
        Bundle b = new Bundle();
        eduser = (EditText)findViewById(R.id.txtuser);
        b.putString("user","PULD#"+ eduser.getText().toString());
        i.putExtras(b);
        startActivity(i);

    }


    public void OnclickbtnPaymentNotUploaded(View v)
    {
        //payment date wise
        Intent i = new Intent(CollectionHome.this, PaymentCommanReport.class);
        Bundle b = new Bundle();
        eduser = (EditText)findViewById(R.id.txtuser);
        b.putString("user","PPFULD#"+ eduser.getText().toString());
        i.putExtras(b);
        startActivity(i);

    }



    public void onbtnbtnPaymentCollect(View v)
    {
        Intent i = new Intent(CollectionHome.this, Payment.class);
        Bundle b = new Bundle();
        eduser = (EditText)findViewById(R.id.txtuser);
        b.putString("Data"," #"+ eduser.getText().toString());
        i.putExtras(b);
        startActivity(i);

    }

}
