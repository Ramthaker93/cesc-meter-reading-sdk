package com.npcl.com.vcpopdl;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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



public class Serach extends AppCompatActivity {
    private Spinner spinner;
    private Button btnSubmit;
    EditText ed1;
    EditText eduser;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_serach);
        Bundle bundle = getIntent().getExtras();
        String text= bundle.getString("user");

        eduser = (EditText)findViewById(R.id.txtuser);
        eduser.setText(text);
        spinner = (Spinner) findViewById(R.id.ddfileter);

        //


        //

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

        //btnSubmit.setOnClickListener(new OnClickListener() {

        btnSubmit.setOnClickListener(new View.OnClickListener(){


            @Override
            public void onClick(View v) {
                eduser = (EditText)findViewById(R.id.txtuser);
                String User = eduser.getText().toString().replace("Welcome:::","");
                String Role = GetRole(User);

                if (Role.equals("READER")) {
                    String Fileter = String.valueOf(spinner.getSelectedItem());
                    String FileterVal = ed1.getText().toString();
                    //   Toast.makeText(getApplicationContext(), "OnClickListener : " + "\n Filter Type : "+ Fileter ,  Toast.LENGTH_SHORT).show();
                    //   Toast.makeText(getApplicationContext(), "OnClickListener : " + "\n Filter Value : "+ FileterVal ,  Toast.LENGTH_SHORT).show();

                    //    String pp="update mro_detail set MeterNo ='SS12137633'  where ConsumerNo='2000144863'";
                    DatabaseHandler db = new DatabaseHandler(getApplicationContext());
                    //    db.ExecuteQry(pp);


                    String Sql = "";
                    if (Fileter == "Consumer No")
                        Sql = "Select ConsumerNo,MeterNo,Name,Co,HouseNo,Street,City,Portion from mro_detail  where ConsumerNo not in (select ConsumerNo from MR_Detail) and  ConsumerNo like '%" + FileterVal + "%' order by Street,HouseNo";
                    else if (Fileter == "Consumer Name")
                        Sql = "Select ConsumerNo,MeterNo,Name,Co,HouseNo,Street,City,Portion from mro_detail  where ConsumerNo not in (select ConsumerNo from MR_Detail) and Name like '%" + FileterVal + "%' order by Street,HouseNo";
                    else if (Fileter == "Meter No")
                        Sql = "Select ConsumerNo,MeterNo,Name,Co,HouseNo,Street,City,Portion from mro_detail  where ConsumerNo not in (select ConsumerNo from MR_Detail) and MeterNo like '%" + FileterVal + "%' order by Street,HouseNo";
                    else if (Fileter == "Street")
                        Sql = "Select ConsumerNo,MeterNo,Name,Co,HouseNo,Street,City,Portion from mro_detail  where ConsumerNo not in (select ConsumerNo from MR_Detail) and Street like '%" + FileterVal + "%' order by Street,HouseNo";
                    else if (Fileter == "CO")
                        Sql = "Select ConsumerNo,MeterNo,Name,Co,HouseNo,Street,City,Portion from mro_detail  where ConsumerNo not in (select ConsumerNo from MR_Detail) and Co like '%" + FileterVal + "%' order by Street,HouseNo";

                    //DatabaseHandler db = new DatabaseHandler(getApplicationContext());
                    GridView gd;
                    ArrayList list = new ArrayList<String>();
                    gd = (GridView) findViewById(R.id.grid_view);

                    Cursor c = db.GetData(Sql);
                    //Move the cursor to the first row.
                    if (c.moveToFirst()) {
                        do {
                            String Cons = c.getString(c.getColumnIndex("ConsumerNo"));
                            String Mtrno = c.getString(c.getColumnIndex("MeterNo"));
                            String Name = c.getString(c.getColumnIndex("Name"));
                            String Co = c.getString(c.getColumnIndex("Co"));
                            String HouseNo = c.getString(c.getColumnIndex("HouseNo"));
                            String Street = c.getString(c.getColumnIndex("Street"));
                            String city = c.getString(c.getColumnIndex("Portion"));
                            String portion = c.getString(c.getColumnIndex("Portion"));
                            String Data = Cons + ":::" + Mtrno + ":::" + Name + " , " + Co + " , " + HouseNo + " , " + Street + " , " + city + " , " + portion;
                            //add in to array list
                            list.add(Data);

                        } while (c.moveToNext());//Move the cursor to the next row.

                        ArrayAdapter<String> adp = new ArrayAdapter<String>(Serach.this, android.R.layout.simple_list_item_1, list);
                        gd.setAdapter(adp);
                    } else {
                        Toast.makeText(getApplicationContext(), "No data found", Toast.LENGTH_LONG).show();
                    }


                } else
                {
                    ///SuperVisor
                    String Fileter = String.valueOf(spinner.getSelectedItem());
                    String FileterVal = ed1.getText().toString();
                    //   Toast.makeText(getApplicationContext(), "OnClickListener : " + "\n Filter Type : "+ Fileter ,  Toast.LENGTH_SHORT).show();
                    //   Toast.makeText(getApplicationContext(), "OnClickListener : " + "\n Filter Value : "+ FileterVal ,  Toast.LENGTH_SHORT).show();

                    String Sql = "";
                    if (Fileter == "Consumer No")
                        Sql = "Select ConsumerNo,MeterNo,Name,Co,HouseNo,Street,City,Portion from mro_Detail_supervisor  where  snotetype is null  and ConsumerNo like '%" + FileterVal + "%'";
                    else if (Fileter == "Consumer Name")
                        Sql = "Select ConsumerNo,MeterNo,Name,Co,HouseNo,Street,City,Portion from mro_Detail_supervisor  where  snotetype is null  and Name like '%" + FileterVal + "%'";
                    else if (Fileter == "Meter No")
                        Sql = "Select ConsumerNo,MeterNo,Name,Co,HouseNo,Street,City,Portion from mro_Detail_supervisor  where  snotetype is null  and MeterNo like '%" + FileterVal + "%'";
                    else if (Fileter == "Street")
                        Sql = "Select ConsumerNo,MeterNo,Name,Co,HouseNo,Street,City,Portion from mro_Detail_supervisor  where  snotetype is null  and Street like '%" + FileterVal + "%'";
                    else if (Fileter == "CO")
                        Sql = "Select ConsumerNo,MeterNo,Name,Co,HouseNo,Street,City,Portion from mro_Detail_supervisor  where  snotetype is null  and Co like '%" + FileterVal + "%' order by Street,HouseNo,ConsumerNo";
                    DatabaseHandler db = new DatabaseHandler(getApplicationContext());
                    GridView gd;
                    ArrayList list = new ArrayList<String>();
                    gd = (GridView) findViewById(R.id.grid_view);


                    Cursor c = db.GetData(Sql);
                    //Move the cursor to the first row.
                    if (c.moveToFirst()) {
                        do {
                            String Cons = c.getString(c.getColumnIndex("ConsumerNo"));
                            String Mtrno = c.getString(c.getColumnIndex("MeterNo"));
                            String Name = c.getString(c.getColumnIndex("Name"));
                            String Co = c.getString(c.getColumnIndex("Co"));
                            String HouseNo = c.getString(c.getColumnIndex("HouseNo"));
                            String Street = c.getString(c.getColumnIndex("Street"));
                            String city = c.getString(c.getColumnIndex("Portion"));
                            String portion = c.getString(c.getColumnIndex("Portion"));
                            String Data = Cons + ":::" + Mtrno + ":::" + Name + " , " + Co + " , " + HouseNo + " , " + Street + " , " + city + " , " + portion;
                            //add in to array list
                            list.add(Data);

                        } while (c.moveToNext());//Move the cursor to the next row.

                        ArrayAdapter<String> adp = new ArrayAdapter<String>(Serach.this, android.R.layout.simple_list_item_1, list);
                        gd.setAdapter(adp);
                    } else {
                        Toast.makeText(getApplicationContext(), "No data found", Toast.LENGTH_LONG).show();
                    }

                }



            }
        });

        GridView gd;
        gd=(GridView)findViewById(R.id.grid_view);
        gd.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                eduser = (EditText)findViewById(R.id.txtuser);
                String Userid = eduser.getText().toString().replace("Welcome:::","");
               // String Role = GetRole(Userid);


                    eduser = (EditText) findViewById(R.id.txtuser);
                    String User = eduser.getText().toString();
                    String data = ((TextView) view).getText().toString();
                    Intent i = new Intent(Serach.this, Reading.class);
                    Bundle b = new Bundle();
                    b.putString("Data", data + "#" + User);
                    i.putExtras(b);
                    startActivity(i);

            }
        });


    }
    public String GetRole(String VCPID)
    {
        String Role ="-";
        try
        {
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            String Sql ="select Role from Login where Userid='"+ VCPID +"'";
            Cursor c = db.GetData(Sql);
            if (c.moveToFirst()) {
                do {
                    Role = c.getString(c.getColumnIndex("Role"));

                } while (c.moveToNext());
            }
            return Role;
        }
        catch (Exception ex)
        {
            return Role;

        }
    }
}
