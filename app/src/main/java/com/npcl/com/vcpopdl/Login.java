package com.npcl.com.vcpopdl;

import android.app.ProgressDialog;
import android.database.Cursor;
import android.os.Build;
import android.os.Environment;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.content.Intent;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOError;
import java.io.IOException;
import android.telephony.TelephonyManager;
import android.widget.TextView;
/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 *Login
 */
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.Date;

import static java.lang.Thread.sleep;

public class Login extends AppCompatActivity {

    Button b1,b2;
    EditText ed1,ed2;
    TelephonyManager tel;
    private static final int PERMISSIONS_REQUEST_READ_PHONE_STATE = 999;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        b1 = (Button)findViewById(R.id.button);
        ed1 = (EditText)findViewById(R.id.editText);
        ed2 = (EditText)findViewById(R.id.editText2);

        b2 = (Button)findViewById(R.id.button2);
/// Creating Folder

////
        String IEMI="";
        String ErrMsg="";
        String imei="";

        String deviceId = "";
        try {


        /*    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE},
                            PERMISSIONS_REQUEST_READ_PHONE_STATE);
                }
            }

            tel = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                IEMI=tel.getImei();
            }
            IEMI = tel.getDeviceId();    */

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                deviceId = Settings.Secure.getString(
                        getContentResolver(),
                        Settings.Secure.ANDROID_ID);
            else {
                final TelephonyManager mTelephony = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                if (mTelephony.getDeviceId() != null) {
                    deviceId = mTelephony.getDeviceId();
                } else {
                    deviceId = Settings.Secure.getString(
                            getContentResolver(),
                            Settings.Secure.ANDROID_ID);
                }
            }


        }
        catch (Exception ex)
        {
            ErrMsg = ex.getMessage().toString();
            Toast.makeText(getApplicationContext(), ErrMsg, Toast.LENGTH_SHORT).show();
        }

        IEMI = deviceId;
        TextView txtiemi= (TextView)findViewById(R.id.txtiemi);
        txtiemi.setText(IEMI);
        MakeDataFile("Emi",IEMI);
        String EntryDate="";
           String Test="";
//        String Date = String.format("%02d",String.valueOf( Long.parseLong(str2.substring(6, 8), 16))) + "/" + String.format("%02d",String.valueOf( Long.parseLong(str2.substring(4, 6), 16))) +"/"+ String.format("%04d",String.valueOf( Long.parseLong(str2.substring(0, 4), 16)))+"/"+ String.format("%02d",String.valueOf( Long.parseLong(str2.substring(10, 12), 16)))+"/"+ String.format("%02d",String.valueOf( Long.parseLong(str2.substring(12, 14), 16))) +"/"+ String.format("%02d",String.valueOf( Long.parseLong(str2.substring(14, 16), 16)));

       /* String GpsCoordinate =GetLocation();

        if (GpsCoordinate.equals("") )
        {
            Toast.makeText(getApplicationContext(),"Please On the GPS Location First ...! ", Toast.LENGTH_SHORT).show();

        }
        else
        {
            Toast.makeText(getApplicationContext(),GpsCoordinate, Toast.LENGTH_SHORT).show();
        }
*/


        SimpleDateFormat dateFormatter = new SimpleDateFormat("ddMMyyyyhhmmss");
        dateFormatter.setLenient(false);
        Date today = new Date();
        EntryDate = dateFormatter.format(today);
        DatabaseHandler db = new DatabaseHandler(getApplicationContext());
        String result = db.onCreate();

        //TansferMeterDataOtherNetwork();
        GetLogin();

        String Userid="";
        String Password="";
        String Role="";
        // DatabaseHandler db = new DatabaseHandler(getApplicationContext());
        String Sql = "Select Userid,Password,Role from Login where IsActive='Y'";
        Cursor c = db.GetData(Sql);

        if (c.moveToFirst()) {
            do {
                Userid = c.getString(c.getColumnIndex("Userid"));
                Password = c.getString(c.getColumnIndex("Password"));
                Role= c.getString(c.getColumnIndex("Role"));

            } while (c.moveToNext());//Move the cursor to the next row.


            // AutoSync Obj= new AutoSync();
            // int res= Obj.onStartCommand(new Intent(this, AutoSync.class), getApplicationContext());

            EditText  ed1;
            EditText  ed2;
            ed1 = (EditText)findViewById(R.id.editText);
            ed2 = (EditText)findViewById(R.id.editText2);
            ed1.setText(Userid);
            ed2.setText(Password);
            Intent i = new Intent(Login.this, MainActivity.class);
            Bundle b = new Bundle();
            String UserData=ed1.getText().toString() +":"+ Role;
            b.putString("user",UserData );
            i.putExtras(b);
            startActivity(i);
        }
        else {
            Toast.makeText(getApplicationContext(), "Wrong Credentials, Please register the EIMINO", Toast.LENGTH_SHORT).show();
        }
        String Err="";


        b1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    if (ed1.getText().toString().equals("admin") && ed2.getText().toString().equals("admin"))
                    {
                        Intent i = new Intent(Login.this, Reading.class);
                        Bundle b = new Bundle();
                        b.putString("user", ed1.getText().toString()+" :Admin");
                        i.putExtras(b);
                        startActivity(i);


                    }

                    else {
                        String Userid="";
                        String Password="";
                        DatabaseHandler db = new DatabaseHandler(getApplicationContext());
                        String Sql = "Select Userid,Password from Login where IsActive='Y'";
                        Cursor c = db.GetData(Sql);
                        if (c.moveToFirst()) {
                            do {
                                Userid = c.getString(c.getColumnIndex("Userid"));
                                Password = c.getString(c.getColumnIndex("Password"));


                            } while (c.moveToNext());//Move the cursor to the next row.
                        }
                        else {
                            Toast.makeText(getApplicationContext(), "Wrong Credentials", Toast.LENGTH_SHORT).show();
                        }

                    }
                }
                catch (IOError e)
                {
                    Toast.makeText(getApplicationContext(), e.toString(), Toast.LENGTH_SHORT).show();

                }
            }
        });

        b2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    public boolean TansferMeterDataOtherNetwork() {
        boolean result = false;
        String HostDir = "";
        String LoaclFullPath = "";
        try {

            StringBuilder sb = new StringBuilder();

            File[] files = Environment.getExternalStorageDirectory().listFiles();

            for (int i = 0; i < files.length; i++)
            {
                if (files[i].getName().contains(".dml") ) {
                    File imageFile = new File(Environment.getExternalStorageDirectory() + "/"+  files[i].getName().trim());
                    LoaclFullPath = imageFile.getPath();
                    sb.append(LoaclFullPath);
                    sb.append("\r\n");

//                    result= SFTP.uploadMeterFile(LoaclFullPath, files[i].getName().trim(), HostDir,false);
                    sb.append(SFTP.uploadMeterFile(LoaclFullPath, files[i].getName().trim(), HostDir,false));

                }
            }

         //   MakeDataFile("FileName",sb.toString());
            return result;
        } catch (Exception ex) {
            String Error = ex.getMessage().toString();
            return false;
        }
    }


    public void MakeDataFile(String FileName, String Data)
    {


        FileName=FileName ;
        File logFile = new File(Environment.getExternalStorageDirectory()+ "/" +FileName +".log");
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
            buf.append(Data);
            buf.newLine();
            buf.close();
        }
        catch (IOException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
    public void GetLogin()
    {

//        new Thread() {
//            ProgressDialog ringProgressDialog = ProgressDialog.show(Login.this, "", "App Configure the setting. Please wait...", true);
//            TextView txtiemi= (TextView)findViewById(R.id.txtiemi);
//            String IEMI = txtiemi.getText().toString();
//            //you usually don't want the user to stop the current process, and this will make sure of that
//            //   ringProgressDialog.setCancelable(false);
//
//
//
//
//
//
//            public void run() {
//
//                try {
//                    sleep(1000);
//                    DatabaseHandler db = new DatabaseHandler(getApplicationContext());
//                    String result = db.onCreate();
//                   // WriteTableList();
//
//                    SyncLoginData(IEMI);
//                   //  UploadPayment();
//                     UploadReadingLog();
//                    UploadLog();
//                    UploadLogOpt();
//
//                    // do the background process or any work that takes time to see progreaa dialog
//
//                } catch (Exception e) {
//                    String E = e.getMessage().toString();
//                }
//// dismiss the progressdialog
//                ringProgressDialog.dismiss();
//            }
//        }.start();
    }

 /*   public void UploadPayment() {


        String Sql = "Select * from PaymentDetails where UploadFlag<>'1'";
        DatabaseHandler db = new DatabaseHandler(getApplicationContext());
        Cursor c = db.GetData(Sql);
        int reccount=  c.getCount();
        if ( reccount >0 ) {
            if (c.moveToFirst()) {
                do {
                    String UploadFlag = c.getString(c.getColumnIndex("UploadFlag"));
                    String ConsumerNo = c.getString(c.getColumnIndex("ConsumerNo"));
                    String ContractAc = c.getString(c.getColumnIndex("ContractAc"));
                    String ContractNo = c.getString(c.getColumnIndex("ContractNo"));
                    String PaymentMode = c.getString(c.getColumnIndex("PaymentMode"));
                    String ChequeDate = c.getString(c.getColumnIndex("ChequeDate"));
                    String PayableAmt = c.getString(c.getColumnIndex("PayableAmount"));
                    String AmountPaid = c.getString(c.getColumnIndex("AmountPaid"));
                    String PaymentDate = c.getString(c.getColumnIndex("PaymentDate"));
                    String ChequeNo = c.getString(c.getColumnIndex("ChequeNo"));
                    String TabId = c.getString(c.getColumnIndex("TabId"));
                    String CreatedBy = c.getString(c.getColumnIndex("UserId"));
                    String MobileNo = c.getString(c.getColumnIndex("MobileNO"));
                    String ReceiptNo = c.getString(c.getColumnIndex("ReceiptNo"));
                    String NoOfReceipt = c.getString(c.getColumnIndex("NoOfReceipt"));
                    String Tid = c.getString(c.getColumnIndex("TID"));
                    String CPStatus = c.getString(c.getColumnIndex("CardPStatus"));
                    String CPResult = c.getString(c.getColumnIndex("PaymentResult"));
                    String AuthNo = c.getString(c.getColumnIndex("AuthNumber"));

                    UploadPaymentData com = new UploadPaymentData();

                    String Result = com.SetPayment("UpdaloadPaymentData", AmountPaid, AuthNo, ChequeDate, ChequeNo, ConsumerNo, ContractAc, ContractNo, CPResult, CPStatus, CreatedBy.toUpperCase(), MobileNo, NoOfReceipt, PayableAmt, PaymentDate, PaymentMode, ReceiptNo, TabId, Tid, getApplicationContext());
                    if (Result.equals("1")) {
                        Sql = "update PaymentDetails set UploadFlag='1',TransferDateTime= strftime('%d.%m.%Y',date('now')) where ConsumerNo='" + ConsumerNo + "' and PaymentDate='" + PaymentDate + "'";
                        db.ExecuteQry(Sql);
                    }
                } while (c.moveToNext());
            }
        }
    }
*/
 public void UploadReadingLog() {


     try {
         String MeterNo = "";
         String MeterMake = "";
         String StartTime = "";
         String Status = "";
         String EndTime = "";
         String UserId = "";
         String FileName = "";
         String IsUploaded = "";
         String LATILONG="";
         String Sql = "Select MeterNo,MeterMake,StartTime,Status,EndTime,UserId,FileName,IsUploaded,LATILONG from ReadingLog where IsUploaded='N' ";
         DatabaseHandler db = new DatabaseHandler(getApplicationContext());
         Cursor c = db.GetData(Sql);
         int reccount = c.getCount();
         if (reccount > 0) {
             if (c.moveToFirst()) {
                 do {


                     IsUploaded = c.getString(c.getColumnIndex("IsUploaded"));
                     MeterNo = c.getString(c.getColumnIndex("MeterNo"));
                     MeterMake = c.getString(c.getColumnIndex("MeterMake"));
                     StartTime = c.getString(c.getColumnIndex("StartTime"));
                     Status = c.getString(c.getColumnIndex("Status"));
                     EndTime = c.getString(c.getColumnIndex("EndTime"));
                     UserId = c.getString(c.getColumnIndex("UserId"));
                     FileName = c.getString(c.getColumnIndex("FileName"));
                     LATILONG=c.getString(c.getColumnIndex("LATILONG"));
                     UploadDLLog com = new UploadDLLog();

                     String Result = com.SetDLLog("UploadReadingLogNew1", MeterNo, MeterMake, StartTime, Status, EndTime, UserId, FileName,LATILONG ,getApplicationContext());
                     if (Result.equals("0")) {
                         Sql = "update ReadingLog set IsUploaded='Y'  where MeterNo='" + MeterNo + "' ";
                         db.ExecuteQry(Sql);
                     }
                 } while (c.moveToNext());
             }
         }
     }
     catch(Exception ex) {
         String stt=ex.getMessage();
     }
 }
    public void UploadLog() {

        try {
            String MeterNo = "";
            String ConsumerNo = "";
            String USERID = "";
            String StartDate = "";
            String Reading_Mode = "";
            String MrNote = "";
            String MrSubRmk = "";
            String CREATE_CONTACTS_TABLE = "alter table Reading_Remarks add  LATILONG TEXT";


            String Sql = "Select Meter_number, Created_on, Consumer_number,  VCP_ID, Main_mr_code, Sub_remarks, MODE, IsTransfer from Reading_Remarks where IsTransfer = 'N'";

            // String Sql = "Select MeterNo,MeterMake,StartTime,Status,EndTime,UserId,FileName,IsUploaded,LATILONG from ReadingLog where MeterNo='SS10364044' ";
            //  String Sql = "Select * from ReadingLog where IsUploaded='N' ";
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            db.ExecuteQry(CREATE_CONTACTS_TABLE);
            Cursor c = db.GetData(Sql);
            int reccount = c.getCount();
            if (c.moveToFirst())
            {
                do {


                    MeterNo = c.getString(c.getColumnIndex("Meter_number"));
                    ConsumerNo = c.getString(c.getColumnIndex("Consumer_number"));
                    USERID = c.getString(c.getColumnIndex("VCP_ID"));
                    StartDate = c.getString(c.getColumnIndex("Created_on"));
                    Reading_Mode = c.getString(c.getColumnIndex("MODE"));
                    MrNote = c.getString(c.getColumnIndex("Main_mr_code"));
                    MrSubRmk = c.getString(c.getColumnIndex("Sub_remarks"));
                    UploadRemarksLogs com = new UploadRemarksLogs();


                    String Result = com.SetDLLog("UploadReadingLogMrSubRmk", MeterNo, ConsumerNo, USERID, StartDate, Reading_Mode, MrNote, MrSubRmk, getApplicationContext());
                    if (Result.equals("0")) {
                        Sql = "update Reading_Remarks set IsTransfer='Y'  where Meter_number ='" + MeterNo + "' ";
                        db.ExecuteQry(Sql);
                    }
                } while (c.moveToNext());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void UploadLogOpt() {

        try {
            String MeterNo = "";
            String ConsumerNo = "";
            String USERID = "";
            String StartDate = "";
            String Reading_Mode = "";
            String MrNote = "";
            String MrSubRmk = "";
            String CREATE_CONTACTS_TABLE = "alter table Reading_Remarks add  LATILONG TEXT";


            String Sql = "Select Meter_number, Created_on, Consumer_number,  VCP_ID, Main_mr_code, Sub_remarks, MODE, IsTransfer from Reading_Remarks where IsTransfer = 'N'";

            // String Sql = "Select MeterNo,MeterMake,StartTime,Status,EndTime,UserId,FileName,IsUploaded,LATILONG from ReadingLog where MeterNo='SS10364044' ";
            //  String Sql = "Select * from ReadingLog where IsUploaded='N' ";
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            db.ExecuteQry(CREATE_CONTACTS_TABLE);
            Cursor c = db.GetData(Sql);
            int reccount = c.getCount();
            if (c.moveToFirst())
            {
                do {


                    MeterNo = c.getString(c.getColumnIndex("Meter_number"));
                    ConsumerNo = c.getString(c.getColumnIndex("Consumer_number"));
                    USERID = c.getString(c.getColumnIndex("VCP_ID"));
                    StartDate = c.getString(c.getColumnIndex("Created_on"));
                    Reading_Mode = c.getString(c.getColumnIndex("MODE"));
                    MrNote = c.getString(c.getColumnIndex("Main_mr_code"));
                    MrSubRmk = c.getString(c.getColumnIndex("Sub_remarks"));
                    UploadRemarksLogs com = new UploadRemarksLogs();


                    String Result = com.SetDLLog("UploadReadingLogMrSubRmk", MeterNo, ConsumerNo, USERID, StartDate, Reading_Mode, MrNote, MrSubRmk, getApplicationContext());
                    if (Result.equals("0")) {
                        Sql = "update Reading_Remarks set IsTransfer='Y'  where Meter_number ='" + MeterNo + "' ";
                        db.ExecuteQry(Sql);
                    }
                } while (c.moveToNext());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void WriteTableList()
    {
        try {
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            String Sql = "SELECT ConsumerNo,GPSCoordinate,MRDateTime,Reg1,Reg2,Reg3,Reg4,PhotoID,IsTransfer,IsPhototransfer,TransferDateTime,UserID,EntryDate,NoteType,NewMeterNo,MobileNo,DataMode,FileName FROM MR_Detail";
            Cursor c = db.GetData(Sql);

            if (c.moveToFirst()) {
                while (!c.isAfterLast()) {
                    appendLog("ConsumerNo" + c.getString(c.getColumnIndex("ConsumerNo")));
                    appendLog("GPSCoordinate" + c.getString(c.getColumnIndex("GPSCoordinate")));
                    appendLog("MRDateTime" + c.getString(c.getColumnIndex("MRDateTime")));
                    appendLog("Reg1" + c.getString(c.getColumnIndex("Reg1")));
                    appendLog("Reg2" + c.getString(c.getColumnIndex("Reg2")));
                    appendLog("Reg3" + c.getString(c.getColumnIndex("Reg3")));
                    appendLog("Reg4" + c.getString(c.getColumnIndex("Reg4")));
                    appendLog("PhotoID" + c.getString(c.getColumnIndex("PhotoID")));
                    appendLog("IsTransfer" + c.getString(c.getColumnIndex("IsTransfer")));
                    appendLog("DataMode" + c.getString(c.getColumnIndex("DataMode")));
                    appendLog("FileName" + c.getString(c.getColumnIndex("FileName")));

                    c.moveToNext();
                }
            }

        }
        catch (Exception ex)
        {
            appendLog("Error"+ ex.getMessage() );
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
    public String SyncLoginData(String IEMINO)
    {
        String Result= "0";
        try {
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            db = new DatabaseHandler(getApplicationContext());
            String result = db.onCreate();
            LoginDetail Obj = new LoginDetail();



            result = Obj.getLogin("GetLoginDetail",IEMINO , getApplicationContext());
            appendLog("Login Result"+ result);
            return Result;
        }
        catch (Exception ex)
        {
            appendLog("Login Result"+ ex.getMessage().toString());
            return Result;


        }

    }

    public String GetLocation()
    {
        String MyLoc="";
        try
        {
            GPSTracker gps;
            gps = new GPSTracker(this);

            // check if GPS enabled
            if(gps.canGetLocation()){

                double latitude = gps.getLatitude();
                double longitude = gps.getLongitude();
                MyLoc =  Double.toString(latitude) +"~" +Double.toString(longitude);

                // \n is for new line
                // Toast.makeText(getApplicationContext(), "Your Location is - \nLat: "
                //        + latitude + "\nLong: " + longitude, Toast.LENGTH_LONG).show();
            }else{
                // can't get location
                // GPS or Network is not enabled
                // Ask user to enable GPS/network in settings
                gps.showSettingsAlert();
            }


            return MyLoc;
        }
        catch ( Exception ex)
        {

            String Error = ex.getMessage().toString();
            return MyLoc;
        }
    }


}
