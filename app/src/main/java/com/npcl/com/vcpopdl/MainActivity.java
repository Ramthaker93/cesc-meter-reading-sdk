package com.npcl.com.vcpopdl;


import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.content.Intent;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;


public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Bundle bundle = getIntent().getExtras();

        String UserData= bundle.getString("user");
        String[] Temp = UserData.split(":");

        String text= Temp[0].toString();
        // Toast.makeText(getApplicationContext(),text,Toast.LENGTH_SHORT).show();
        EditText ed1 = (EditText) findViewById(R.id.txtuser);
        ed1.setText(text);

        onbtnDemo();

    }
    public void onbtnDatasync(View v) {
        EditText ed1 = (EditText) findViewById(R.id.txtuser);
       Intent i = new Intent(MainActivity.this, DataSync.class);
        Bundle b = new Bundle();
        b.putString("user", ed1.getText().toString());
        i.putExtras(b);
        startActivity(i);
    }
    public void onbtnsearch(View v) {
        EditText ed1 = (EditText) findViewById(R.id.txtuser);
        Intent i = new Intent(MainActivity.this, Serach.class);
        Bundle b = new Bundle();
        b.putString("user", ed1.getText().toString());
        i.putExtras(b);
        startActivity(i);
    }
    public void onbtnMReading(View v) {
        EditText ed1 = (EditText) findViewById(R.id.txtuser);
        Intent i = new Intent(MainActivity.this, Reading.class);
        Bundle b = new Bundle();
        String data = ":";
        String User = ed1.getText().toString();
        b.putString("Data", data + "#" + User);
        i.putExtras(b);
        startActivity(i);
    }
    public void onbtnReport(View v) {
        EditText ed1 = (EditText) findViewById(R.id.txtuser);
        Intent i = new Intent(MainActivity.this, Report.class);
        Bundle b = new Bundle();
        b.putString("user", ed1.getText().toString()+ ": READER");
        i.putExtras(b);
        startActivity(i);
    }

    public void onbtnaddhocreading(View v) {
        EditText ed1 = (EditText) findViewById(R.id.txtuser);
        Intent i = new Intent(MainActivity.this, AddhocReading.class);
        Bundle b = new Bundle();
        b.putString("user", ed1.getText().toString()+ ": READER");
        i.putExtras(b);
        startActivity(i);
    }


    public void onbtnpayment(View v) {
        EditText ed1 = (EditText) findViewById(R.id.txtuser);
        Intent i = new Intent(MainActivity.this, CollectionHome.class);
        Bundle b = new Bundle();
        b.putString("user", ed1.getText().toString());
       // b.putString("user", ed1.getText().toString()+ ": READER");
        i.putExtras(b);
        startActivity(i);
    }



    public void onbtnDemo() {

        DatabaseHandler Obj = new DatabaseHandler(getApplicationContext());
        try {
            String Sql = "";
            appendLog("Try ------1");
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000150753','Na~Na','20200906104112','43824','0','0','1','2000150753_20200906_1040.jpg','N','IECSAMIR','20200906104112','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000139361','Na~Na','20200906104442','0','0','0','0','2000139361_20200906_1044.jpg','N','IECSAMIR','20200906104442','40','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            appendLog("Try ------111");
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000158728','Na~Na','20200906104547','1489','0','0','3','2000158728_20200906_1045.jpg','N','IECSAMIR','20200906104547','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000011450','Na~Na','20200906104642','48316','0','0','1','2000011450_20200906_1046.jpg','N','IECSAMIR','20200906104642','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000133406','Na~Na','20200906104757','7175','0','0','2','2000133406_20200906_1047.jpg','N','IECSAMIR','20200906104757','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000044608','Na~Na','20200906104958','3998','0','0','1','2000044608_20200906_1049.jpg','N','IECSAMIR','20200906104958','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000057091','Na~Na','20200906105041','0','0','0','0','2000057091_20200906_1050.jpg','N','IECSAMIR','20200906105041','40','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000027787','Na~Na','20200906105211','4857','0','0','1','2000027787_20200906_1051.jpg','N','IECSAMIR','20200906105211','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000028414','Na~Na','20200906105256','0','0','0','0','2000028414_20200906_1052.jpg','N','IECSAMIR','20200906105256','40','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000060513','Na~Na','20200906105615','13226','0','0','1','2000060513_20200906_1056.jpg','N','IECSAMIR','20200906105615','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000049482','Na~Na','20200906110218','2656','8','0','1','2000049482_20200906_1102.jpg','N','IECSAMIR','20200906110218','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000011707','Na~Na','20200906110321','5251','0','0','2','2000011707_20200906_1102.jpg','N','IECSAMIR','20200906110321','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000047203','Na~Na','20200906110421','0','0','0','0','2000047203_20200906_1104.jpg','N','IECSAMIR','20200906110421','40','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000125018','Na~Na','20200906110520','2675','0','0','1','2000125018_20200906_1105.jpg','N','IECSAMIR','20200906110520','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000034826','Na~Na','20200906110638','1884','0','0','3','2000034826_20200906_1106.jpg','N','IECSAMIR','20200906110638','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000049298','Na~Na','20200906110741','2363','0','0','1','2000049298_20200906_1107.jpg','N','IECSAMIR','20200906110741','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000039226','Na~Na','20200906110832','5861','0','0','2','2000039226_20200906_1108.jpg','N','IECSAMIR','20200906110832','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000137444','Na~Na','20200906110943','1607','0','0','2','2000137444_20200906_1109.jpg','N','IECSAMIR','20200906110943','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000027744','Na~Na','20200906111024','11096','0','0','3','2000027744_20200906_1110.jpg','N','IECSAMIR','20200906111024','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000043643','Na~Na','20200906111059','14271','0','0','1','2000043643_20200906_1110.jpg','N','IECSAMIR','20200906111059','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000060191','Na~Na','20200906111135','0','0','0','0','2000060191_20200906_1111.jpg','N','IECSAMIR','20200906111135','40','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000056635','Na~Na','20200906111212','10090','0','0','1','2000056635_20200906_1111.jpg','N','IECSAMIR','20200906111212','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000135627','Na~Na','20200906111252','2666','0','0','1','2000135627_20200906_1112.jpg','N','IECSAMIR','20200906111252','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000044280','Na~Na','20200906111355','2789','0','0','3','2000044280_20200906_1113.jpg','N','IECSAMIR','20200906111355','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000026195','Na~Na','20200906111429','2017','0','0','1','2000026195_20200906_1114.jpg','N','IECSAMIR','20200906111429','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000011513','Na~Na','20200906111506','2847','0','0','2','2000011513_20200906_1114.jpg','N','IECSAMIR','20200906111506','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000148306','Na~Na','20200906111542','15966','0','0','0','2000148306_20200906_1115.jpg','N','IECSAMIR','20200906111542','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000125832','Na~Na','20200906111614','9420','0','0','2','2000125832_20200906_1116.jpg','N','IECSAMIR','20200906111614','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000055792','Na~Na','20200906111655','6568','0','0','1','2000055792_20200906_1116.jpg','N','IECSAMIR','20200906111655','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000026163','Na~Na','20200906111728','3029','0','0','1','2000026163_20200906_1117.jpg','N','IECSAMIR','20200906111728','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000056535','Na~Na','20200906111805','3018','0','0','2','2000056535_20200906_1117.jpg','N','IECSAMIR','20200906111805','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000027736','Na~Na','20200906111834','22008','0','0','0','2000027736_20200906_1118.jpg','N','IECSAMIR','20200906111834','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000130400','Na~Na','20200906111908','5341','0','0','1','2000130400_20200906_1118.jpg','N','IECSAMIR','20200906111908','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000058284','Na~Na','20200906112001','0','0','0','0','2000058284_20200906_1119.jpg','N','IECSAMIR','20200906112001','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000049989','Na~Na','20200906112127','2128','0','0','1','2000049989_20200906_1121.jpg','N','IECSAMIR','20200906112127','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000044750','Na~Na','20200906112200','2012','0','0','1','2000044750_20200906_1121.jpg','N','IECSAMIR','20200906112200','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000039105','Na~Na','20200906112235','2473','0','0','2','2000039105_20200906_1122.jpg','N','IECSAMIR','20200906112235','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000050013','Na~Na','20200906112313','4499','0','0','2','2000050013_20200906_1123.jpg','N','IECSAMIR','20200906112313','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000059302','Na~Na','20200906112343','34867','0','0','0','2000059302_20200906_1123.jpg','N','IECSAMIR','20200906112343','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000057070','Na~Na','20200906112418','20333','0','0','0','2000057070_20200906_1124.jpg','N','IECSAMIR','20200906112418','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000054199','Na~Na','20200906112509','8648','0','0','1','2000054199_20200906_1124.jpg','N','IECSAMIR','20200906112509','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000060409','Na~Na','20200906112645','4432','0','0','3','2000060409_20200906_1126.jpg','N','IECSAMIR','20200906112645','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000027724','Na~Na','20200906112739','26672','0','0','3','2000027724_20200906_1127.jpg','N','IECSAMIR','20200906112739','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000043779','Na~Na','20200906113145','1277','0','0','1','2000043779_20200906_1130.jpg','N','IECSAMIR','20200906113145','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000047997','Na~Na','20200906113340','1699','0','0','1','2000047997_20200906_1132.jpg','N','IECSAMIR','20200906113340','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000050485','Na~Na','20200906113459','2304','0','0','1','2000050485_20200906_1134.jpg','N','IECSAMIR','20200906113459','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000053920','Na~Na','20200906113536','11630','0','0','1','2000053920_20200906_1135.jpg','N','IECSAMIR','20200906113536','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000011361','Na~Na','20200906113612','6196','0','0','1','2000011361_20200906_1136.jpg','N','IECSAMIR','20200906113612','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000047852','Na~Na','20200906113726','880','0','0','2','2000047852_20200906_1137.jpg','N','IECSAMIR','20200906113726','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000048017','Na~Na','20200906113808','34529','0','0','0','2000048017_20200906_1137.jpg','N','IECSAMIR','20200906113808','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000034812','Na~Na','20200906113847','1836','0','0','1','2000034812_20200906_1138.jpg','N','IECSAMIR','20200906113847','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000060221','Na~Na','20200906113950','5495','0','0','2','2000060221_20200906_1139.jpg','N','IECSAMIR','20200906113950','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000060221','Na~Na','20200906114022','5495','0','0','2','2000060221_20200906_1140.jpg','N','IECSAMIR','20200906114022','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000057114','Na~Na','20200906114059','6377','0','0','1','2000057114_20200906_1140.jpg','N','IECSAMIR','20200906114059','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000011478','Na~Na','20200906114337','1797','0','0','3','2000011478_20200906_1143.jpg','N','IECSAMIR','20200906114337','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000034821','Na~Na','20200906114409','2895','0','0','1','2000034821_20200906_1144.jpg','N','IECSAMIR','20200906114409','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000041369','Na~Na','20200906114454','20755','0','0','2','2000041369_20200906_1144.jpg','N','IECSAMIR','20200906114454','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000053776','Na~Na','20200906114531','6822','0','0','3','2000053776_20200906_1145.jpg','N','IECSAMIR','20200906114531','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000052661','Na~Na','20200906114608','0','0','0','0','2000052661_20200906_1145.jpg','N','IECSAMIR','20200906114608','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000061488','Na~Na','20200906114636','1437','0','0','1','2000061488_20200906_1146.jpg','N','IECSAMIR','20200906114636','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000061488','Na~Na','20200906141121','1437','0','0','1','2000061488_20200906_1411.jpg','N','IECSAMIR','20200906141121','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000151564','Na~Na','20200906141212','4498','0','0','2','2000151564_20200906_1411.jpg','N','IECSAMIR','20200906141212','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000049246','Na~Na','20200906141252','34076','0','0','0','2000049246_20200906_1412.jpg','N','IECSAMIR','20200906141252','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000043865','Na~Na','20200906141328','14667','0','0','3','2000043865_20200906_1413.jpg','N','IECSAMIR','20200906141328','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000043491','Na~Na','20200906141359','41503','0','0','0','2000043491_20200906_1413.jpg','N','IECSAMIR','20200906141359','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000056904','Na~Na','20200906141443','13307','0','0','1','2000056904_20200906_1414.jpg','N','IECSAMIR','20200906141443','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000049873','Na~Na','20200906141522','1312','0','0','3','2000049873_20200906_1415.jpg','N','IECSAMIR','20200906141522','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000059979','Na~Na','20200906141728','7004','0','0','2','2000059979_20200906_1417.jpg','N','IECSAMIR','20200906141728','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000011454','Na~Na','20200906141800','6675','0','0','1','2000011454_20200906_1417.jpg','N','IECSAMIR','20200906141800','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000136687','Na~Na','20200906141833','12322','0','0','2','2000136687_20200906_1418.jpg','N','IECSAMIR','20200906141833','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000155871','Na~Na','20200906142112','0','0','0','0','2000155871_20200906_1420.jpg','N','IECSAMIR','20200906142112','40','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000055030','Na~Na','20200906142158','9559','0','0','1','2000055030_20200906_1421.jpg','N','IECSAMIR','20200906142158','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000050512','Na~Na','20200906142249','5903','0','0','3','2000050512_20200906_1422.jpg','N','IECSAMIR','20200906142249','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000053726','Na~Na','20200906142330','4516','0','0','1','2000053726_20200906_1423.jpg','N','IECSAMIR','20200906142330','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000047688','Na~Na','20200906142418','32660','8','0','0','2000047688_20200906_1424.jpg','N','IECSAMIR','20200906142418','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000056793','Na~Na','20200906142449','7786','0','0','2','2000056793_20200906_1424.jpg','N','IECSAMIR','20200906142449','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000057051','Na~Na','20200906142540','9148','0','0','3','2000057051_20200906_1425.jpg','N','IECSAMIR','20200906142540','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000026199','Na~Na','20200906142718','82437','0','8','0','2000026199_20200906_1426.jpg','N','IECSAMIR','20200906142718','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000050295','Na~Na','20200906142753','0','0','0','0','2000050295_20200906_1427.jpg','N','IECSAMIR','20200906142753','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000052910','Na~Na','20200906142912','7334','0','0','2','2000052910_20200906_1429.jpg','N','IECSAMIR','20200906142912','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000047999','Na~Na','20200906142940','2243','0','0','1','2000047999_20200906_1429.jpg','N','IECSAMIR','20200906142940','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000157595','Na~Na','20200906143017','2237','0','0','3','2000157595_20200906_1430.jpg','N','IECSAMIR','20200906143017','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000045105','Na~Na','20200906143057','28565','0','0','1','2000045105_20200906_1430.jpg','N','IECSAMIR','20200906143057','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000011456','Na~Na','20200906143130','0','0','0','0','2000011456_20200906_1431.jpg','N','IECSAMIR','20200906143130','40','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000055305','Na~Na','20200906143203','5388','0','0','1','2000055305_20200906_1431.jpg','N','IECSAMIR','20200906143203','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000011441','Na~Na','20200906143302','30733','0','0','1','2000011441_20200906_1432.jpg','N','IECSAMIR','20200906143302','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000040293','Na~Na','20200906143428','10690','0','0','2','2000040293_20200906_1434.jpg','N','IECSAMIR','20200906143428','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000053958','Na~Na','20200906143541','9771','0','0','1','2000053958_20200906_1435.jpg','N','IECSAMIR','20200906143541','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000043891','Na~Na','20200906143647','3858','0','0','1','2000043891_20200906_1436.jpg','N','IECSAMIR','20200906143647','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000048705','Na~Na','20200906143734','15899','0','0','0','2000048705_20200906_1437.jpg','N','IECSAMIR','20200906143734','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);
            Sql = " insert into MR_Detail (ConsumerNo,GPSCoordinate,MRDateTime, Reg1, Reg2, Reg3,Reg4,PhotoID, IsTransfer, UserID, EntryDate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode,FileName )Values ('2000044358','Na~Na','20200906143833','444','0','0','0','2000044358_20200906_1438.jpg','N','IECSAMIR','20200906143833','16','','','N','MANUAL','~')";
            Obj.ExecuteQry(Sql);

        } catch (Exception ex) {

            appendLog(ex.getMessage());
        }
    }
    public void appendLog(String text)
    {
        File logFile = new File(Environment.getExternalStorageDirectory()+ "/" + "OPDLlog.txt");
        if (!logFile.exists())
        {
            try
            {
                logFile.createNewFile();
            }
            catch (IOException e)
            {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        try
        {
            //BufferedWriter for performance, true to set append to file flag
            BufferedWriter buf = new BufferedWriter(new FileWriter(logFile, true));
            buf.append(text);
            buf.newLine();
            buf.close();
        }
        catch (IOException e)
        {
            // TODO Auto-generated catch block
            Toast.makeText(getApplicationContext(), e.getMessage().toString() , Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    public void SavePaymentDataMRO( )
    {
        try
        {
            appendLog("IN");
            String VcpId="NITESHC";
            String Data="575822000163939~SS14399530~123456~RENU .~W/O PRAVEEN KUMAR~~KULESRA~GREATER NOIDA~FEB-2022~VC05~744.00~10.04.2022~         720.00~744.00~LIVE~~~222596#2000001300~SS10915211~0510D  011 08~YASHIN KHAN~ASRUDDIN~.~HALDAUNI~GREATER NOIDA~FEB-2022~VC12~1,215.00~10.04.2022~       1,118.00~1,215.00~LIVE~~~1298#2000138705~SS12181872~124526~VICKEY KUMAR~S/O MAHIPAL~~TILPATA KARANWAS~GREATER NOIDA~FEB-2022~VC02~718.00~17.03.2021~      13,000.00~30,332.00~TD Non Payment~29,614.00~~203132#";
            String [] Row = Data.split("#");
            String Cols = " (ConsumerNo  ,  MeterNo  ,  PoleNo  ,  Name  ,  Co  ,  HouseNo  ,  Street  , City  ,  Billmonthyear  ,  Portion  ,  LastPmt  ,  LastPmtDate  ,  LastBillAmt  ,  TotalAmt  ,  Status  ,  Arrear  ,  MobileNo,TabId,EntryDate,UserId ) ";
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String SDate=timeStamp.toString().split("_")[0].toString();
            DatabaseHandler Obj = new DatabaseHandler(getApplicationContext());
            for(int i=0;i< Row.length ;i++) {
                String Vals = "";
                String Qry="";

                String[] temp = Row[i].split("~");
                for (int j = 0; j < temp.length; j++) {
                    Vals = Vals + "'" + temp[j].toString() + "',";
                }
                Vals = Vals + "'" + SDate + "',";
                Vals = Vals + "'" + VcpId + "'";
                Qry = "insert into PaymentData " + Cols + "values (" + Vals + ");";

                java.lang.Thread.sleep(5);
                Obj.ExecuteQry(Qry) ;
                appendLog(Qry);

            }

        }
        catch ( Exception ex)
        {
            appendLog("error" +ex.getMessage().toString());

        }
    }


}
