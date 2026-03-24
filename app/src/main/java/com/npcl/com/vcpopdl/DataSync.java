package com.npcl.com.vcpopdl;

import android.database.Cursor;
import android.os.Environment;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.os.StrictMode;
import 	android.app.ProgressDialog;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Calendar;
import android.widget.ProgressBar;


import java.io.FileInputStream;
import java.io.FileOutputStream;

public class DataSync extends AppCompatActivity {
    private Spinner spinner, spinner1;
    private Button btnSubmit;
    private CheckBox Chkpayment;
    ProgressBar progressbar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            if (android.os.Build.VERSION.SDK_INT > 9) {
                StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
                StrictMode.setThreadPolicy(policy);
            }

        } catch (Exception e) {
// TODO: handle exception
        }

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_data_sync);
        Bundle bundle = getIntent().getExtras();
        String text = bundle.getString("user").replace("Welcome:::", "");
        TextView Vcpid;
        Vcpid = (TextView) findViewById(R.id.lbVcpId);
        Vcpid.setText(text);
        Button btnGetDBLoaction;
        Button btnClearData;
        if (text.equals("admin")) {

        } else {
            btnGetDBLoaction = (Button) findViewById(R.id.btnGetDBLoaction);
            btnGetDBLoaction.setVisibility(View.GONE);

            btnClearData = (Button) findViewById(R.id.btnClearData);
            btnClearData.setVisibility(View.GONE);
        }

        spinner = (Spinner) findViewById(R.id.ddmonth);
        List<String> list = new ArrayList<String>();
        list.add("01: Jan");
        list.add("02: Feb");
        list.add("03: Mar");
        list.add("04: Apr");
        list.add("05: May");
        list.add("06: Jun");
        list.add("07: Jul");
        list.add("08: Aug");
        list.add("09: Sep");
        list.add("10: Oct");
        list.add("11: Nov");
        list.add("12: Dec");
        ArrayAdapter<String> dataAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, list);
        dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(dataAdapter);

        spinner1 = (Spinner) findViewById(R.id.ddyear);
        List<String> list1 = new ArrayList<String>();
        Calendar calendar = Calendar.getInstance();
        int year = calendar.get(Calendar.YEAR);
        list1.add(String.valueOf(year - 1));
        list1.add(String.valueOf(year));
        list1.add(String.valueOf(year + 1));
        ArrayAdapter<String> dataAdapter1 = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, list1);
        dataAdapter1.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner1.setAdapter(dataAdapter1);


    }

    public void DownLoadMRO() {
        String Month = String.valueOf(spinner.getSelectedItem());
        Month = Month.split(":")[0].toString();
        String Year1 = String.valueOf(spinner1.getSelectedItem());
        String BillMonthYear = Year1 + Month;

        Chkpayment = (CheckBox) findViewById(R.id.chkpayment);
        TextView Vcpid;
        Vcpid = (TextView) findViewById(R.id.lbVcpId);
        String VCPID = Vcpid.getText().toString().toUpperCase();
        boolean paymentdata = false;
        if (Chkpayment.isChecked())
            paymentdata = true;
        DatabaseHandler db = new DatabaseHandler(getApplicationContext());
        String result = db.onCreate();
        if (paymentdata == false) {
            // Toast.makeText(getApplicationContext(), "OnClickListener : " + "\n Filter Type : "+ Month +"\n Filter Text"+ Year1,  Toast.LENGTH_SHORT).show();
            if (CheckNewDataDownload() == false) {
                TextView Message = (TextView) findViewById(R.id.lbMessage);
                Message.setText("Some Reading Pending to Upload, Please Upload First ");
                Toast.makeText(getApplicationContext(), "Some Reading Pending to Upload, Please Upload First ", Toast.LENGTH_SHORT).show();
            } else {
                ClearData();

                mrodownload com = new mrodownload();
                db = new DatabaseHandler(getApplicationContext());
                result = db.onCreate();
                String Role =GetRole( VCPID);

             // commented 10-08-2021 by Sheelendra for Supervisor  on opticl app
                // result = com.getMro("MRODownloadByReaderNew", BillMonthYear, VCPID, "1", getApplicationContext());

                if (Role.equals("READER")){
                    result = com.getMro("MRODownloadByReaderNew", BillMonthYear, VCPID, "1", getApplicationContext());
                }

                else {

                    if (CheckMRODataSupervisor() == false ) {

                        Toast.makeText(getApplicationContext(), "Some Reading Pending to Upload, Please Upload First ", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    else
                    {
                        ClearData();
                        MRODownloadBySupervisor Obj = new MRODownloadBySupervisor();
                        result = Obj.getMroSupervisor("SerMRODownloadBySupervisor", BillMonthYear, VCPID, getApplicationContext());

                    }
                }


                if (result.equals("1")) {
                    TextView Message = (TextView) findViewById(R.id.lbMessage);
                    Message.setText("MRO Downloading successfully...! ");
                } else {
                    TextView Message = (TextView) findViewById(R.id.lbMessage);
                    Message.setText("Problem in MRO Downloading...! ");
                }
            }
        } else {
            if (CheckPaymentData(VCPID) == true) {
                if (ClearPaymentData() == true) {

                    DownLoadPaymentData ObjPay = new DownLoadPaymentData();
                    String Result = ObjPay.getPaymentData("SerMRODownloadByReaderNew", BillMonthYear, VCPID, "1.1.2.2", getApplicationContext());
                    if (Result.equals("1")) {
                        TextView Message = (TextView) findViewById(R.id.lbMessage);
                        Message.setText("Payment Data Download...! ");
                    } else {
                        TextView Message = (TextView) findViewById(R.id.lbMessage);
                        Message.setText(Result);
                    }
                } else {
                    TextView Message = (TextView) findViewById(R.id.lbMessage);
                    Message.setText("Problem in Clear the Current Data...! ");
                }

            } else {
                TextView Message = (TextView) findViewById(R.id.lbMessage);
                return;
            }

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



    public void sheelendralogLog(String text)
    {
        File logFile = new File(Environment.getExternalStorageDirectory()+ "/" + "Developerlog.txt");
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


    public void UploalMRODetails() {

        try {


            String Sql = "select GPSCoordinate  ,Ablbelnr,NoteType, substr(a.EntryDate,7,2)||'.' || substr(a.EntryDate,5,2)||'.' || substr(a.EntryDate,1,4) Adat ,  " +
                    " sch_mr_date, substr(sch_mr_date,7,2)||'.' || substr(sch_mr_date,5,2)||'.' || substr(sch_mr_date,1,4) Adatsoll,substr(a.EntryDate,9,4) Atim, Billmonthyear,meterno Equnr,strftime('%d.%m.%Y',date('now'))Erdat ,a.consumerno Gpart,Photoid ImageName , " +
                    " reg1 Kwh ,  CASE reg2 WHEN NULL THEN 0 ELSE reg2 END  Kvrh, reg3 Kvah, reg4 Kva,  a.MobileNo,NewMeterNo,Portion,Serge, a.userid VcpId,'READER'  ReadBy  ,Ableinh Termschl ,DataMode,FileName  from mr_detail a, mro_Detail b where a.consumerno=b.consumerno ";

            String Ablbelnr = "";
            String Ablhinw = "";
            String Adat = "";
            String Adatsoll = "";
            String Atim = "";
            String Billmonthyear = "";
            String Equnr = "";
            String Erdat = "";
            String Gpart = "";
            String ImageName = "";
            String Kva = "";
            String Kvah = "";
            String Kvrh = "";
            String Kwh = "";
            String Latitude = "";
            String Longitude = "";
            String MobileNo = "";
            String NewMeterNo = "";
            String Portion = "";
            String ReadBy = "";
            String Serge = "";
            String Termschl = "";
            String VcpId = "";
            String DataMode="";
            String FileName="";
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            //DataMode

          // Sql="select GPSCoordinate,Ablbelnr,NoteType ,substr(a.EntryDate,7,2)||'.' || substr(a.EntryDate,5,2)||'.' || substr(a.EntryDate,1,4) Adat,substr(sch_mr_date,7,2)||'.' || substr(sch_mr_date,5,2)||'.' || substr(sch_mr_date,1,4) Adatsoll,substr(a.EntryDate,9,4) Atim,Billmonthyear,meterno Equnr,strftime('%d.%m.%Y',date('now'))Erdat ,a.consumerno Gpart,Photoid ImageName,reg1 Kwh ,  CASE reg2 WHEN NULL THEN 0 ELSE reg2 END  Kvrh, reg3 Kvah, reg4 Kva,  a.MobileNo,NewMeterNo,Portion,Serge, a.userid VcpId,'READER'  ReadBy  ,Ableinh Termschl,DataMode,a.FileName  from MR_Detail a, mro_Detail b where a.consumerno=b.consumerno  and istransfer<>'Y' order by a.EntryDate";
            Sql="select GPSCoordinate,Ablbelnr,NoteType ,substr(a.EntryDate,7,2)||'.' || substr(a.EntryDate,5,2)||'.' || substr(a.EntryDate,1,4) Adat,substr(sch_mr_date,7,2)||'.' || substr(sch_mr_date,5,2)||'.' || substr(sch_mr_date,1,4) Adatsoll,substr(a.EntryDate,9,4) Atim,Billmonthyear,meterno Equnr,strftime('%d.%m.%Y',date('now'))Erdat ,a.consumerno Gpart,Photoid ImageName,reg1 Kwh ,  CASE reg2 WHEN NULL THEN 0 ELSE reg2 END  Kvrh, reg3 Kvah, reg4 Kva,  a.MobileNo,NewMeterNo,Portion,Serge, a.userid VcpId,'READER'  ReadBy  ,Ableinh Termschl,DataMode,a.FileName  from MR_Detail a, mro_Detail b where a.consumerno=b.consumerno and IsTransfer<>'Y'  order by a.EntryDate";

            //   Sql="select GPSCoordinate,Ablbelnr,NoteType ,substr(a.EntryDate,7,2)||'.' || substr(a.EntryDate,5,2)||'.' || substr(a.EntryDate,1,4) Adat,substr(sch_mr_date,7,2)||'.' || substr(sch_mr_date,5,2)||'.' || substr(sch_mr_date,1,4) Adatsoll,substr(a.EntryDate,9,4) Atim,Billmonthyear,meterno Equnr,strftime('%d.%m.%Y',date('now'))Erdat ,a.consumerno Gpart,Photoid ImageName,reg1 Kwh ,  CASE reg2 WHEN NULL THEN 0 ELSE reg2 END  Kvrh, reg3 Kvah, reg4 Kva,  a.MobileNo,NewMeterNo,Portion,Serge, a.userid VcpId,'READER'  ReadBy  ,Ableinh Termschl,DataMode,a.FileName  from MR_Detail a, mro_Detail b where a.consumerno=b.consumerno  and istransfer<>'Y'";

            //appendLog(Sql);

           Cursor c = db.GetData(Sql);

             int i=c.getCount();

           //sheelendra

         /*   if (c.moveToFirst())
            { do {
                    sheelendralogLog("------------------------------");
                  //  DataMode =c.getString(c.getColumnIndex("DataMode")).toString();
                    FileName =c.getString(c.getColumnIndex("FileName")).toString();
                    sheelendralogLog(FileName);
        } while (c.moveToNext());//Move the cursor to the next row.
    } */

         /*
g
            if (i >0 && cur!=null ) {
                cur.moveToNext();
            }

            if (i >0 && cur!=null )
             */
      if (c.moveToFirst())
            {
                do {
                    DataMode =c.getString(c.getColumnIndex("DataMode"));
                    FileName =c.getString(c.getColumnIndex("FileName"));
                   Ablbelnr = c.getString(c.getColumnIndex("Ablbelnr"));
                    Ablhinw = c.getString(c.getColumnIndex("NoteType"));
                    Adat = c.getString(c.getColumnIndex("Adat"));
                    Adatsoll = c.getString(c.getColumnIndex("Adatsoll"));
                    Atim = c.getString(c.getColumnIndex("Atim"));
                    Billmonthyear = c.getString(c.getColumnIndex("Billmonthyear"));
                    Equnr = c.getString(c.getColumnIndex("Equnr"));
                    Erdat = c.getString(c.getColumnIndex("Erdat"));
                    Gpart = c.getString(c.getColumnIndex("Gpart"));
                    ImageName = c.getString(c.getColumnIndex("ImageName"));
                    Kva = c.getString(c.getColumnIndex("Kva"));
                    Kvah = c.getString(c.getColumnIndex("Kvah"));

                    Kvrh="0";


                    Kwh = c.getString(c.getColumnIndex("Kwh"));

                    String[] Temp = c.getString(c.getColumnIndex("GPSCoordinate")).toString().split("~");
                    if (Temp.length >1) {
                        Latitude = Temp[0].toString();

                        Longitude = Temp[1].toString();
                    }
                    MobileNo = c.getString(c.getColumnIndex("MobileNo"));
                    NewMeterNo = c.getString(c.getColumnIndex("NewMeterNo"));
                    Portion = c.getString(c.getColumnIndex("Portion"));
                    ReadBy = c.getString(c.getColumnIndex("ReadBy"));
                    Serge = c.getString(c.getColumnIndex("Serge"));
                    Termschl = c.getString(c.getColumnIndex("Termschl"));
                    VcpId = c.getString(c.getColumnIndex("VcpId"));
                    sheelendralogLog("-----------START-------------------");
                    if (DataMode.equals("OPTICAL"))
                    {
                        FileName= FileName+".dml";
                        FileName= FileName.replace("\n","");
                        FileName= FileName.replace("\t","");
                        sheelendralogLog("FileName: "+FileName);
                        boolean Flag = TansferReadingFile(FileName);
                        sheelendralogLog("Flag: "+Flag);
                        if (Flag== true )
                        {
                           // UploadFileLog(String MethodName, String Filename,String APP_VERSION, Context context)
                            String filelogname=FileName.replace(".dml","");
                            sheelendralogLog("FILE_UPLOADLOg:"+filelogname);
                            mroupload com = new mroupload();
                             String res=com.UploadFileLog("ReadingFileUploadLog",filelogname,"Optical Download-Ver-Upsilon",getApplicationContext());
                             sheelendralogLog("FILE_UPLOADLOgNew:"+res);
                            if(res.equals("1")){
                                Sql = "update  mr_detail set IsPhototransfer ='Y' , istransfer='Y' , transferdatetime =strftime('%d.%m.%Y',date('now'))  where consumerno='" + Gpart + "'";
                                db.ExecuteQry(Sql);
                                DeletePhoto(ImageName);
                            }

                            sheelendralogLog("-----------END------------");

                        }


                    }
                    else {
                        sheelendralogLog("FileName: "+ImageName);
                        boolean Flag = TansferPhotoOtherNetwork(ImageName,Billmonthyear);
                        //boolean Flag= true;

                        if (Flag == false) {
                            Flag = TansferPhotoCescRajWiFi(ImageName,Billmonthyear);
                        }
                        sheelendralogLog("Flag: "+Flag);
                        if (Flag == true) {

                            {
                                mroupload com = new mroupload();
                                String Result = com.SetMro("SerMROUploadByReader", Ablbelnr, Ablhinw, Adat, Adatsoll, Atim, Billmonthyear, Equnr, Erdat, Gpart, ImageName, Kva, Kvah, Kvrh, Kwh, Latitude, Longitude, MobileNo, NewMeterNo, Portion, ReadBy, Serge, Termschl, VcpId.toUpperCase(), getApplicationContext());
                                String Data =Ablbelnr +"~" + Ablhinw +"~" + Adat +"~" + Adatsoll +"~" + Atim +"~" + Billmonthyear +"~" + Equnr +"~" + Erdat +"~"+ Gpart +"~" + ImageName +"~" + Kva +"~" + Kvah +"~" + Kvrh +"~" + Kwh +"~" + Latitude +"~" + Longitude +"~" + MobileNo +"~" + NewMeterNo +"~" + Portion +"~" + ReadBy+"~" + Serge+"~" + Termschl+"~" + VcpId.toUpperCase();

                                appendLog(Result +"#"+   Data);


                                if (Result.equals("S")) {
                                    Sql = "update  mr_detail set IsPhototransfer ='Y' , istransfer='Y' , transferdatetime =strftime('%d.%m.%Y',date('now'))  where consumerno='" + Gpart + "'";
                                    db.ExecuteQry(Sql);
                                    sheelendralogLog("----------END-------------");
                                    DeletePhoto(ImageName);

                                }
                            }
                        }

                    }
                  //    Sql = "update  mr_detail set IsPhototransfer ='Y' , istransfer='Y' , transferdatetime =strftime('%d.%m.%Y',date('now'))  where consumerno='" + Gpart + "'";
                    //   db.ExecuteQry(Sql);




                } while (c.moveToNext());//Move the cursor to the next row.


            }



         //   Toast.makeText(getApplicationContext(), "MRO Upload Sucessfully...!", Toast.LENGTH_SHORT).show();
        } catch (Exception ex) {

            String Error = ex.getMessage().toString();
            appendLog("error" +Error);
            sheelendralogLog("Fn_UploalMRODetails: "+Error);
        }


    }

    public void UploalAddhocReading() {

        try {


            String Sql ="";

            String FileName="";
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            Sql="select FileName from Addhocreading where IsUploaded <> 'Y'";
            sheelendralogLog("here");
            Cursor c = db.GetData(Sql);
           if (c.moveToFirst())
            {
                do {

                    FileName =c.getString(c.getColumnIndex("FileName"));

                    sheelendralogLog("-----------START Addhoc-------------------");

                        FileName= FileName+".dml";
                        FileName= FileName.replace("\n","");
                        FileName= FileName.replace("\t","");
                        sheelendralogLog("FileName: "+FileName);
                        boolean Flag = TansferReadingFile(FileName);
                        sheelendralogLog("Flag: "+Flag);
                        if (Flag== true )
                        {
                            // UploadFileLog(String MethodName, String Filename,String APP_VERSION, Context context)
                            String filelogname=FileName.replace(".dml","");
                            sheelendralogLog("FILE_UPLOADLOg:"+filelogname);

                                Sql = "update Addhocreading set IsUploaded='Y' where FileName='"+filelogname+"'";
                                db.ExecuteQry(Sql);


                            sheelendralogLog("-----------END------------");

                        }


                } while (c.moveToNext());//Move the cursor to the next row.


            }



            //   Toast.makeText(getApplicationContext(), "MRO Upload Sucessfully...!", Toast.LENGTH_SHORT).show();
        } catch (Exception ex) {

            String Error = ex.getMessage().toString();
            appendLog("error" +Error);
            sheelendralogLog("Fn_UploadAddhocDetails: "+Error);
        }


    }

    public void DeletePhoto(String FileName)
    {
        try {

            File[] files = Environment.getExternalStorageDirectory().listFiles();
            File imageFile = new File(Environment.getExternalStorageDirectory() +"/.vcpsysdata/"+ FileName);
            if (imageFile.exists()) {
                imageFile.delete();
            }
        }
        catch (Exception ex)
        {}

    }



    public void onbtnDownload(View v) {
       new Thread() {
            ProgressDialog ringProgressDialog = ProgressDialog.show(DataSync.this, "", "Downloading Data. Please wait...", true);

            //you usually don't want the user to stop the current process, and this will make sure of that
            //   ringProgressDialog.setCancelable(false);
            public void run() {

                try {
                    sleep(1000);
                    DownLoadMRO();
                    // do the background process or any work that takes time to see progreaa dialog
                    TextView Vcpid = (TextView) findViewById(R.id.lbVcpId);
                    String VCPID = Vcpid.getText().toString().toUpperCase();
                    MRNote Obj = new MRNote();
                    Obj.getMRNote("GetMRNote",VCPID, getApplicationContext());
                } catch (Exception e) {
                    String E = e.getMessage().toString();
                }
// dismiss the progressdialog
                ringProgressDialog.dismiss();
            }
        }.start();

    }

    public void onbtnUpload(View v) {
        //final ProgressDialog ringProgressDialog = ProgressDialog.show(getApplicationContext(), "Title ...", "Info ...", true);


        new Thread() {
            ProgressDialog ringProgressDialog = ProgressDialog.show(DataSync.this, "", "Uploading Data. Please wait...", true);

            //you usually don't want the user to stop the current process, and this will make sure of that
            //   ringProgressDialog.setCancelable(false);
            public void run() {

                try {
                    sleep(1000);
                  //  new Login().UploadReadingLog();
                    UploalAddhocReading();
                   //UploadReadingLog();
                    UploadLog();
                   // UploadLogOpt();
                    appendLog("I Am Consumer Here");
                    UploadConsumerInfo();

                //   UploadPayment();
                                     sleep(1000);
                    UploalMRODetails();

                    UploadMRODetailSupervisor();


                    // do the background process or any work that takes time to see progreaa dialog

                } catch (Exception e) {
                    String E = e.getMessage().toString();
                }
// dismiss the progressdialog
                ringProgressDialog.dismiss();
            }
        }.start();


    }

    public boolean TansferPhotoCescRajWiFi(String PhotoName,String Billmonthyear) {
        boolean result = false;
        String HostDir = "";
        String LoaclFullPath = "";

        try {
            File[] files = Environment.getExternalStorageDirectory().listFiles();
            File imageFile = new File(Environment.getExternalStorageDirectory() + "/.vcpsysdata/" + PhotoName);
            LoaclFullPath = imageFile.getPath();
            HostDir = "HHI//Tablet_Photo//";
            //FTPFunctions FTPObj = new FTPFunctions("192.158.1.194", 21, "hhi", "hhi0npcl");

            //result = FTPObj.uploadFTPFile(LoaclFullPath, PhotoName, HostDir);
            result= SFTP.uploadFile(LoaclFullPath, PhotoName, HostDir,true,Billmonthyear);

            return result;
        } catch (Exception ex) {
            String Error = ex.getMessage().toString();
            sheelendralogLog("Fn_TansferPhotoCescRajWiFi: "+Error);
            return false;
        }
    }

    public boolean TansferReadingFile(String FileName) {
        boolean result = false;
        String HostDir = "";
        String LoaclFullPath = "";

        try {
            File[] files = Environment.getExternalStorageDirectory().listFiles();
            File imageFile = new File(Environment.getExternalStorageDirectory() + "/" + FileName);
            LoaclFullPath = imageFile.getPath();
            HostDir = "Tablet_Photo//";
            // FTPFunctions FTPObj = new FTPFunctions("14.142.33.232", 21, "hhi", "hhi0npcl");
            // result = FTPObj.uploadFTPFile(LoaclFullPath, PhotoName, HostDir);

            result= SFTP.uploadMeterFile(LoaclFullPath, FileName, HostDir,false);
            //Toast.makeText(getApplicationContext(),Ans, Toast.LENGTH_SHORT).show();

            return result;
        } catch (Exception ex) {
            String Error = ex.getMessage().toString();
            sheelendralogLog("Fn_TansferReadingFile: "+Error);
            return false;
        }
    }


    public boolean TansferPhotoOtherNetwork(String PhotoName,String Billmonthyear) {
        boolean result = false;
        String HostDir = "";
        String LoaclFullPath = "";

        try {
            File[] files = Environment.getExternalStorageDirectory().listFiles();
            File imageFile = new File(Environment.getExternalStorageDirectory() + "/.vcpsysdata/" + PhotoName);
            LoaclFullPath = imageFile.getPath();
            HostDir = "Tablet_Photo//";
           // FTPFunctions FTPObj = new FTPFunctions("14.142.33.232", 21, "hhi", "hhi0npcl");
           // result = FTPObj.uploadFTPFile(LoaclFullPath, PhotoName, HostDir);

            result= SFTP.uploadFile(LoaclFullPath, PhotoName, HostDir,false,Billmonthyear);
            //Toast.makeText(getApplicationContext(),Ans, Toast.LENGTH_SHORT).show();

            return result;
        } catch (Exception ex) {
            String Error = ex.getMessage().toString();
            sheelendralogLog("Fn_TansferPhotoOtherNetwork: "+Error);
            return false;
        }
    }

    public void UploadConsumerInfo() {

        /*try{*/
        String VCONSUMERNO="";
        String VBILLMONTHYEAR="";
        String VMCB="";
        String VMETER_LOC ="";
        String VVCP_ID="";
        String Sql = "Select CONSUMERNO,BILLMONTHYEAR,MCB,METER_LOC,VCP_ID from Consumer_Add_Info where IsUpload<>'Y' ";
        // String Sql = "Select MeterNo,MeterMake,StartTime,Status,EndTime,UserId,FileName,IsUploaded,LATILONG from ReadingLog where MeterNo='SS10364044' ";
        //  String Sql = "Select * from ReadingLog where IsUploaded='N' ";
        DatabaseHandler db = new DatabaseHandler(getApplicationContext());
        appendLog("Consumer info In 1.111");
        Cursor c = db.GetData(Sql);
        int reccount = c.getCount();
        appendLog("Consumer info In" +reccount);
        if (reccount > 0) {
            if (c.moveToFirst()) {
                do {
                    appendLog("Consumer info In" );
                    VCONSUMERNO=c.getString(c.getColumnIndex("CONSUMERNO"));
                    VBILLMONTHYEAR=c.getString(c.getColumnIndex("BILLMONTHYEAR"));
                    VMCB=c.getString(c.getColumnIndex("MCB"));
                    VMETER_LOC =c.getString(c.getColumnIndex("METER_LOC"));
                    VVCP_ID=c.getString(c.getColumnIndex("VCP_ID"));

                    ConsumerAddInfo com = new ConsumerAddInfo();


                    String Result = com.SetAddInfo("UploadConsumerAddInfo",VCONSUMERNO , VBILLMONTHYEAR, VMCB, VMETER_LOC, VVCP_ID, getApplicationContext());
                     appendLog("Web Response " + Result);
                    if (Result.equals("0")) {
                        Sql = "update Consumer_Add_Info set IsUpload='Y'  where CONSUMERNO='" + VCONSUMERNO + "' ";
                        db.ExecuteQry(Sql);
                    }
                } while (c.moveToNext());
            }
        }
    }

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
            String LATILONG = "";
            String CREATE_CONTACTS_TABLE = "alter table ReadingLog add  LATILONG TEXT";

            String Sql = "Select MeterNo,MeterMake,StartTime,Status,EndTime,UserId,FileName,IsUploaded,LATILONG from ReadingLog where IsUploaded<>'Y' ";


            // String Sql = "Select MeterNo,MeterMake,StartTime,Status,EndTime,UserId,FileName,IsUploaded,LATILONG from ReadingLog where MeterNo='SS10364044' ";
            //  String Sql = "Select * from ReadingLog where IsUploaded='N' ";
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            db.ExecuteQry(CREATE_CONTACTS_TABLE);
            Cursor c = db.GetData(Sql);
            int reccount = c.getCount();
            if (c.moveToFirst())
            {
                do {

                    IsUploaded = c.getString(c.getColumnIndex("IsUploaded"));
                    MeterNo = c.getString(c.getColumnIndex("MeterNo"));
                    MeterMake = c.getString(c.getColumnIndex("MeterMake"));
                    StartTime = c.getString(c.getColumnIndex("StartTime"));
                    Status = c.getString(c.getColumnIndex("Status"));
                    EndTime = c.getString(c.getColumnIndex("EndTime"));
                    UserId = c.getString(c.getColumnIndex("UserId"));
                    FileName = c.getString(c.getColumnIndex("FileName"));
                    LATILONG = c.getString(c.getColumnIndex("LATILONG"));
                    UploadDLLog com = new UploadDLLog();


                    String Result = com.SetDLLog("UploadReadingLogNew1", MeterNo, MeterMake, StartTime, Status, EndTime, UserId, FileName, LATILONG, getApplicationContext());
                    if (Result.equals("0")) {
                        Sql = "update ReadingLog set IsUploaded='Y'  where MeterNo='" + MeterNo + "' ";
                        db.ExecuteQry(Sql);
                    }
                } while (c.moveToNext());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
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
            String CREATE_CONTACTS_TABLE = "alter table Reading_Remarks";


            String Sql = "Select Meter_number, Created_on, Consumer_number,  VCP_ID, Main_mr_code, Sub_remarks, MRMODE, IsTransfer from Reading_Remarks where IsTransfer = 'N'";

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
                    Reading_Mode = c.getString(c.getColumnIndex("MRMODE"));
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
        }
        catch (Exception e)
        {
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
            String CREATE_CONTACTS_TABLE = "alter table Reading_Remarks";


            String Sql = "Select Meter_number, Created_on, Consumer_number,  VCP_ID, Main_mr_code, Sub_remarks, MRMODE, IsTransfer from Reading_Remarks where IsTransfer = 'N'";

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
                    Reading_Mode = c.getString(c.getColumnIndex("MRMODE"));
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
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }


    public boolean CheckNewDataDownload() {

        boolean result = false;
        try {
            String RecCount = "";

            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            String Sql = "Select Count(*) cnt  from MR_Detail where IsTransfer<>'Y' ";
            Cursor c = db.GetData(Sql);
            if (c.moveToFirst()) {
                do {
                    RecCount = c.getString(c.getColumnIndex("cnt"));
                } while (c.moveToNext());

                if (RecCount.equals("0")) {
                    result = true;

                } else {
                    result = false;
                }
            }
            return result;
        } catch (Exception ex) {
            return false;
        }
    }

    public boolean CheckPaymentData(String VcpId) {
        boolean result = false;

        try {
            String RecCount = "";

            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            String Sql = "select Count(*) cntpmt from PaymentDetails where UploadFlag=0";
            Cursor cur = db.GetData(Sql);
            int i=cur.getCount();
            if (i >0 && cur!=null ) {
                cur.moveToNext();
            }

            if (i >0 && cur!=null )  {
                do {
                    RecCount = cur.getString(cur.getColumnIndex("cntpmt"));
                } while (cur.moveToNext());

                if (RecCount.equals("0")) {
                    DownLoadPaymentData ObjPay = new DownLoadPaymentData();
                    String Ans = ObjPay.CheckPaymentData("CheckPayment", VcpId, getApplicationContext());

                    if (Ans.equals("TRUE")) {

                        Toast.makeText(getApplicationContext(), "Data can not be downloaded.Please first submit the cash....!!!!!", Toast.LENGTH_SHORT).show();

                        return false;
                    } else if (Ans.equals("ERROR")) {
                        Toast.makeText(getApplicationContext(), "Data can not be downloaded.Please contact Admin....!!!!!", Toast.LENGTH_SHORT).show();
                        return false;
                    } else {
                        return true;
                    }


                } else {
                    Toast.makeText(getApplicationContext(), "Unable to Download New Data, Because some Data in Device Which need to Transfer First...!", Toast.LENGTH_LONG).show();
                    return false;

                }
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public boolean ClearData() {
        boolean Result = false;
        try {
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            String Sql = "delete from MR_Detail";
            db.ExecuteQry(Sql);
            Sql = "delete from MRO_Detail";
            db.ExecuteQry(Sql);

            Sql = "delete from MRO_Detail_Supervisor";
            db.ExecuteQry(Sql);
            /*
            Sql = "delete from PaymentData";
            db.ExecuteQry(Sql);
            */
            TextView Vcpid = (TextView) findViewById(R.id.lbVcpId);
            String VCPID = Vcpid.getText().toString().toUpperCase();
            MRNote Obj = new MRNote();
            Obj.getMRNote("GetMRNote",VCPID, getApplicationContext());
            return true;
        } catch (Exception ex) {
            return Result;
        }
    }

    public boolean ClearPaymentData() {
        boolean Result = false;
        try {
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            String Sql = "delete from PaymentDetails";
            db.ExecuteQry(Sql);
            Sql = "delete from PaymentData";
            db.ExecuteQry(Sql);

            return true;
        } catch (Exception ex) {
            return Result;
        }
    }

    public void onbtnClearClick(View v) {
        if (ClearData() == true) {
            Toast.makeText(getApplicationContext(), "Data Clear Sucessfully...!", Toast.LENGTH_SHORT).show();
        } else {

            Toast.makeText(getApplicationContext(), "Problen in Data Cleaning...!", Toast.LENGTH_SHORT).show();
        }
    }

    public void UploadMRODetailSupervisor()
    {
        //substr(sch_mr_date,7,2) || '.'|| substr(sch_mr_date,5,2) || '.'|| substr(sch_mr_date,1,4)


        try
        {
            String Sql = "select Ablbelnr,snotetype Ablhinw ,  substr(smrdatetime,7,2)||'.'||substr(smrdatetime,5,2)||'.'|| substr(smrdatetime,1,4)  Adat  "+
                    " ,Sch_MR_Date Adatsoll ,substr(smrdatetime,9,4) Atim,Billmonthyear,MeterNo, "+
                    " substr(smrdatetime,7,2)||'.'||substr(smrdatetime,5,2)||'.'|| substr(smrdatetime,1,4) Erdat, ConsumerNo,sphotoid ImageName "+
                    " sreg1 Kwh ,sreg2 Kvrh ,sreg3 Kvah,sreg4  Kva , Filename gpscoordinate ,''MobileNo , SnewMeterNo NewMeterNo ,Portion,'SUPERVISOR' ReadBy, "+
                    //" Ableinh Termschl,UserId VcpId,Serge,NoofShop,PremisesType,NoofFloor,NoofRoom,Area,OpenHr,NoofAC,ifnull(DataMode,'') DataMode,FileNameOptical Filename " +
                    "  from mro_Detail_Supervisor where IsTransfer='N'";



Sql="select  FileName, snotetype Ablhinw ,Ableinh,UserId VcpId,Serge ,'SUPERVISOR' ReadBy, SnewMeterNo NewMeterNo,Portion,"+
        "sreg1 Kwh,sreg2 Kvrh,sreg3 Kvah,sreg4 Kva,sphotoid ImageName," +
        " substr(smrdatetime,7,2)||'.'||substr(smrdatetime,5,2)||'.'|| substr(smrdatetime,1,4) Erdat, ConsumerNo,MeterNo,"+
        "Billmonthyear,substr(smrdatetime,9,4) Atim,Sch_MR_Date, IsTransfer,DataMode,FileName, Ablbelnr,snotetype,"+
        "substr(smrdatetime,7,2)||'.'||substr(smrdatetime,5,2)||'.'|| substr(smrdatetime,1,4)  Adat from MRO_Detail_Supervisor " +
        "where DataMode is not null and IsTransfer<>'Y'";
//Sql ="Select IsTransfer,DataMode,FileName  from mro_Detail_supervisor where istransfer<>'Y' ";
        //"where 1=1";

//    // MRO_Detail_Supervisor(ConsumerNo,FileName,mrdatetime,IsTransfer,UserId,EntryDate,DataMode,FileNameOptical)
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            Cursor c = db.GetData(Sql);

         //   int i=c.getCount();

            if (c.moveToFirst())
            {
                do {
                    String istransfer=c.getString(c.getColumnIndex("IsTransfer"));
                    String   DataMode = c.getString(c.getColumnIndex("DataMode"));
                    String   FileNameOptical = c.getString(c.getColumnIndex("FileName"));

                    String Ablbelnr = c.getString(c.getColumnIndex("Ablbelnr"));
                    String Ablhinw = c.getString(c.getColumnIndex("Ablhinw"));
                    String Adatsoll = c.getString(c.getColumnIndex("Adat"));
                    String Billmonthyear = c.getString(c.getColumnIndex("Billmonthyear"));
                    String Equnr = c.getString(c.getColumnIndex("MeterNo"));
                    String Gpart = c.getString(c.getColumnIndex("ConsumerNo"));
                    String ImageName = c.getString(c.getColumnIndex("ImageName"));

                    String Erdat = c.getString(c.getColumnIndex("Erdat"));
                    String Adat = c.getString(c.getColumnIndex("Adat"));
                    String Atim = c.getString(c.getColumnIndex("Atim"));
                    String Kva = "0";
                    String Kvah = "0";
                    String Kvrh = "0";
                    String Kwh = "0";
                    if (DataMode.equals("OPTICAL")) {
                         Kva = "0";
                         Kvah = "0";
                         Kvrh = "0";
                        Kwh = "0";

                    }
                    else {
                         Kva = c.getString(c.getColumnIndex("Kva"));
                         Kvah = "0";
                         Kvrh = "0";
                         Kwh = c.getString(c.getColumnIndex("Kwh"));
                    }
                    String NewMeterNo = c.getString(c.getColumnIndex("NewMeterNo"));
                    String Portion = c.getString(c.getColumnIndex("Portion"));
                    String ReadBy = c.getString(c.getColumnIndex("ReadBy"));
                    String Serge = c.getString(c.getColumnIndex("Serge"));
                   String Termschl = "";
                    String VcpId = c.getString(c.getColumnIndex("VcpId")) ;




                    String Latitude = "";
                    String Longitude = "";
                    Latitude = "NaN";
                    Longitude = "NaN";
                  /*  String[] Temp = c.getString(c.getColumnIndex("GpsCoordinate")).split("~");
                    if (Temp.length >1) {
                        Latitude = Temp[0].toString();
                        Longitude = Temp[1].toString();
                    }
                    else
                    {
                        Latitude = "NaN";
                        Longitude = "NaN";
                    }
*/
                    //if (DataMode.equals(null)){
                    if (DataMode == null){
                        DataMode="";
                    }

                    if (DataMode.equals("OPTICAL"))
                    {
                        String   FileName =c.getString(c.getColumnIndex("FileName"));

                        FileName= FileName+".dml";
                        FileName= FileName.replace("\n","");
                        FileName= FileName.replace("\t","");
                        boolean Flag = TansferReadingFile(FileName);
                        if (Flag== true )
                        {
                            String filelogname=FileName.replace(".dml","");
                            sheelendralogLog("FILE_UPLOADLOg:"+filelogname);
                            mroupload com = new mroupload();
                            //String res=com.UploadFileLog("ReadingFileUploadLog",filelogname,"Optical Download-Ver-Zeta 1.1",getApplicationContext());
                            //sheelendralogLog("FILE_UPLOADLOg:"+res);
                            //if(res.equals("1"))
                            {
                            Sql = "update  MRO_Detail_Supervisor set IsTransfer='Y' , TransferDateTime =strftime('%d.%m.%Y',date('now'))  where ConsumerNo='" + Gpart + "'";
                            db.ExecuteQry(Sql);
                            DeletePhoto(ImageName);
                            }

                        }

                    }
                    else    {
                        boolean Flag = TansferPhotoOtherNetwork (ImageName,Billmonthyear);
                        if (Flag == false ) {
                            Flag = TansferPhotoCescRajWiFi(ImageName,Billmonthyear);
                        }

                        if (Flag == true )

                        {

                            mrouploadbysupervisor com = new mrouploadbysupervisor();
                            String Result = com.SetMroSupervisor("MROUploadBySupervisor", Ablbelnr, Ablhinw, Adat, Adatsoll, Atim, Billmonthyear, Equnr, Erdat, Gpart, ImageName, Kva, Kvah, Kvrh, Kwh, Latitude, Longitude, "", NewMeterNo, Portion, ReadBy, Serge, Termschl, VcpId.toUpperCase(), getApplicationContext());
                            if (Result.equals("S")) {

                                //    Sql = "update  mr_detail set IsPhototransfer ='Y' , istransfer='Y' , transferdatetime =strftime('%d.%m.%Y',date('now'))  where consumerno='" + Gpart + "'";
                                //   db.ExecuteQry(Sql);


                                Sql = "update  MRO_Detail_Supervisor set IsTransfer='Y' , TransferDateTime =strftime('%d.%m.%Y',date('now'))  where ConsumerNo='" + Gpart + "'";
                                db.ExecuteQry(Sql);

/*
                                String Partner = c.getString(c.getColumnIndex("ConsumerNo"));
                                String MrDate = c.getString(c.getColumnIndex("Erdat"));
                                Ablbelnr = c.getString(c.getColumnIndex("Ablbelnr"));
                                String NoOfShops =c.getString(c.getColumnIndex("NoofShop"));
                                String PremiseType= c.getString(c.getColumnIndex("PremisesType"));
                                String FloorNo= c.getString(c.getColumnIndex("NoofFloor"));
                                String RoomNo=c.getString(c.getColumnIndex("NoofRoom"));
                                String Area= c.getString(c.getColumnIndex("Area"));
                                String OpenHr= c.getString(c.getColumnIndex("OpenHr"));
                                String NoofAC=c.getString(c.getColumnIndex("NoofAC"));
                                Result = com.SetSurvey("UpLoadSurveyData", Ablbelnr, "", "", MrDate,
                                        Partner, ImageName,  NoOfShops, PremiseType,
                                        FloorNo, RoomNo,Area,NoofAC,OpenHr,"LCC",VcpId , getApplicationContext());
                                if (Result.equals("S")) {
                                    String trap="Me";
                                }
                                */
                            }
                        }


                    }



                    /// Web service call


                } while (c.moveToNext());//Move the cursor to the next row.
            }

        }
        catch (Exception ex)
        {
            String exx=ex.getMessage().toString();
        }
    }


    public void onbtnGetDBLocationClick(View v) {

        String filename = "TextureFolder";
        //File[] files = Environment.getExternalStorageDirectory().listFiles();
        File f = new File("/data/data/com.npcl.com.npclvcpapp/databases/MeterReading");
      /*  String path ="/data/data/com.npcl.com.npclvcpapp/databases/";
        File directory = new File(path);
        File[] files = directory.listFiles();
          path ="/";
         directory = new File(path);
         files = directory.listFiles();
*/
        FileInputStream fis = null;
        FileOutputStream fos = null;

        try {
            fis = new FileInputStream(f);
            fos = new FileOutputStream("/sdcard/MeterReading");
            while (true) {
                int i = fis.read();
                if (i != -1) {
                    fos.write(i);
                } else {
                    break;
                }
            }
            fos.flush();
            Toast.makeText(this, "DB dump OK", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "DB dump ERROR", Toast.LENGTH_LONG).show();
        } finally {
            try {
                fos.close();
                fis.close();
            } catch (Exception ioe) {
            }
        }

    }
/*
    public void UploadPayment() {

try{
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
        //  Toast.makeText(getApplicationContext(), "Payment Data Upload Sucessfully...!", Toast.LENGTH_SHORT).show();
     }
        catch (Exception ex)
        {
            String Err= ex.getMessage().toString();
            Toast.makeText(getApplicationContext(), "Problem While Data Uploading ...!", Toast.LENGTH_SHORT).show();
        }

    }
*/
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

    public boolean CheckMRODataSupervisor() {
        boolean result = false;
        try {
            String RecCount = "";

            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            String Sql = "select *  from mro_Detail_supervisor where  istransfer='N' and sphotoid is not null";
            Cursor cur = db.GetData(Sql);
            int i = cur.getCount();
            if (i >0)
            {
                return false ;
            }
            return  true;
        } catch (Exception ex) {

            return false;
        }
    }

}
