package com.npcl.com.vcpopdl;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;

import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class Payment_Detail extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_payment__detail);
        Bundle bundle = getIntent().getExtras();
        String text = bundle.getString("user");

        String User = text;
        EditText txtuser = (EditText) findViewById(R.id.txtuser)  ;
        txtuser.setText(User);
        String Sql = "select ConsumerNo,ReceiptNo,PaymentMode, AmountPaid, substr(PaymentDate,1,2)||'-'|| substr(PaymentDate,3,2) ||'-'|| substr(PaymentDate,5,4) PaymentDate  from PaymentDetails " +
                "order by PaymentDate";

        DatabaseHandler db = new DatabaseHandler(getApplicationContext());
        GridView gd;
        ArrayList list=new ArrayList<String>();
        gd=(GridView)findViewById(R.id.grid_view);

        int i=0;

        Cursor c=db.GetData(Sql);
        //Move the cursor to the first row.
        if(c.moveToFirst())
        {
            do
            {
                String Data="";
                String  ConsumerNo=c.getString(c.getColumnIndex("ConsumerNo"));
                String  ReceiptNo=c.getString(c.getColumnIndex("ReceiptNo"));

                String  PaymentMode=c.getString(c.getColumnIndex("PaymentMode"));
                String  AmountPaid=c.getString(c.getColumnIndex("AmountPaid"));
                String  PaymentDate=c.getString(c.getColumnIndex("PaymentDate"));


                Data =  ConsumerNo+"::"+ ReceiptNo +"::"+ PaymentMode +"::"+ AmountPaid +"::"+ PaymentDate;
                //add in to array list

                list.add(Data);

            }while(c.moveToNext());//Move the cursor to the next row.

            ArrayAdapter<String> adp=new ArrayAdapter<String>  (Payment_Detail.this ,android.R.layout.simple_list_item_1,list);
            gd.setAdapter(adp);


            gd=(GridView)findViewById(R.id.grid_view);
            gd.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    EditText eduser = (EditText)findViewById(R.id.txtuser);
                    String User =eduser.getText().toString();
                    String data=((TextView)view).getText().toString();
                    Intent i = new Intent(Payment_Detail.this, Reprint.class);
                    Bundle b = new Bundle();
                    b.putString("Data", data +"#"+ User);
                    i.putExtras(b);
                    startActivity(i);


                }
            });
        }
        else
        {
            Toast.makeText(getApplicationContext(), "No data found", Toast.LENGTH_LONG).show();
        }




    }

}
