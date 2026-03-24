package com.npcl.com.vcpopdl;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.content.Intent;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;
import android.widget.GridView;
import android.widget.AdapterView;
import android.widget.TextView;
import android.database.Cursor;

public class PaySearch extends AppCompatActivity {
    private Spinner spinner;
    private Button btnSubmit;
    EditText ed1;
    EditText eduser;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pay_search);
        Bundle bundle = getIntent().getExtras();
        String text= bundle.getString("user");

        eduser = (EditText)findViewById(R.id.txtuser);
        eduser.setText(text);
        spinner = (Spinner) findViewById(R.id.ddfileter);
        List<String> list = new ArrayList<String>();
        list.add("Consumer No");
        list.add("Consumer Name");
        list.add("Meter No");
        list.add("Street");
        list.add("CO");
        ArrayAdapter<String> dataAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, list);
        dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(dataAdapter);

        btnSubmit = (Button) findViewById(R.id.btnSubmit);
        ed1 = (EditText)findViewById(R.id.txtfilter);
        eduser = (EditText)findViewById(R.id.txtuser);
        //btnSubmit.setOnClickListener(new OnClickListener() {

        btnSubmit.setOnClickListener(new View.OnClickListener(){


            @Override
            public void onClick(View v) {

                String Fileter = String.valueOf(spinner.getSelectedItem());
                String FileterVal = ed1.getText().toString();
                String LogUser = eduser.getText().toString().replace("Welcome:::","").toUpperCase();

                String Sql= "";
                if (Fileter=="Consumer No")
                    Sql="select distinct Consumerno, Name, Co, MeterNo, TotalAmt  as 'PayableAmount', (HouseNo ||','|| Street||',' ||City ) address,UserId from PaymentData where consumerno like  '%"+ FileterVal +"%'";//  and UserId='" + LogUser + "'";
                else if (Fileter=="Consumer Name")
                    Sql="select distinct Consumerno, Name, Co, MeterNo, TotalAmt  as 'PayableAmount', (HouseNo ||','|| Street||',' ||City ) address,UserId from PaymentData where Name like  '%"+ FileterVal +"%' and UserId='" + LogUser + "'";
                else if (Fileter=="Meter No")
                    Sql="select distinct Consumerno, Name, Co, MeterNo, TotalAmt  as 'PayableAmount', (HouseNo ||','|| Street||',' ||City ) address,UserId from PaymentData where MeterNo like  '%"+ FileterVal +"%' and UserId='" + LogUser + "'";
                else if (Fileter=="Street")
                    Sql="select distinct Consumerno, Name, Co, MeterNo, TotalAmt  as 'PayableAmount', (HouseNo ||','|| Street||',' ||City ) address,UserId from PaymentData where Street like  '%"+ FileterVal +"%' and UserId='" + LogUser + "'";
                else if (Fileter=="CO")
                    Sql="select distinct Consumerno, Name, Co, MeterNo, TotalAmt  as 'PayableAmount', (HouseNo ||','|| Street||',' ||City ) address,UserId from PaymentData where Co like  '%"+ FileterVal +"%' and UserId='" + LogUser + "'";


                DatabaseHandler db = new DatabaseHandler(getApplicationContext());
                GridView gd;
                ArrayList list=new ArrayList<String>();
                gd=(GridView)findViewById(R.id.grid_view);

                String UserId="";

                Cursor c=db.GetData(Sql);
                //Move the cursor to the first row.
                if(c.moveToFirst())
                {
                    do
                    {
                        String  Cons=c.getString(c.getColumnIndex("ConsumerNo"));
                        String  Mtrno=c.getString(c.getColumnIndex("MeterNo"));
                        String  Name=c.getString(c.getColumnIndex("Name"));
                        String  Co=c.getString(c.getColumnIndex("Co"));
                        String  address=c.getString(c.getColumnIndex("address"));
                        String  PayableAmount=c.getString(c.getColumnIndex("PayableAmount"));
                        UserId= c.getString(c.getColumnIndex("UserId"));
                        String Data= Cons +":::" + Mtrno +":::"+Name +" , "+Co +" , "+ address +" , "+PayableAmount ;
                        //add in to array list
                        list.add(Data);

                    }while(c.moveToNext());//Move the cursor to the next row.

                    ArrayAdapter<String> adp=new ArrayAdapter<String>  (PaySearch.this ,android.R.layout.simple_list_item_1,list);
                    gd.setAdapter(adp);
                }
                else
                {
                    Toast.makeText(getApplicationContext(), "No data found", Toast.LENGTH_LONG).show();
                }



            }

        });

        GridView gd;
        gd=(GridView)findViewById(R.id.grid_view);
        gd.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                eduser = (EditText)findViewById(R.id.txtuser);
                String User =eduser.getText().toString();
                String data=((TextView)view).getText().toString();
                Intent i = new Intent(PaySearch.this, Payment.class);
                Bundle b = new Bundle();
                b.putString("Data", data +"#"+ User);
                i.putExtras(b);
                startActivity(i);


            }
        });

    }
}