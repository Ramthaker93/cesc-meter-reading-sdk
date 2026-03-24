package com.npcl.com.vcpopdl;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.database.Cursor;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class Report extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_report);
        Bundle bundle = getIntent().getExtras();
        String text= bundle.getString("user");
        EditText eduser;
        eduser = (EditText)findViewById(R.id.txtuser);
        String Userid = text.toString().replace("Welcome:::","");
        //eduser.setText(text);
        Button btnmroDownload;
        Button btnReadTaken;
        Button btnReadUploaded;
        Button btnReadNotUploaded;
        Button btnPending;
        Button btnOPReading;
        Button btnMReading;

        btnmroDownload = (Button) findViewById(R.id.btnmroDownload);
        btnReadTaken = (Button) findViewById(R.id.btnReadingTaken);
        btnPending = (Button) findViewById(R.id.btnPending);
        btnReadUploaded = (Button) findViewById(R.id.btnReadUploaded);
        btnReadNotUploaded = (Button) findViewById(R.id.btnReadNotUploaded);
        btnOPReading = (Button) findViewById(R.id.btnOPReading);
        btnMReading = (Button) findViewById(R.id.btnMReading);

        String Role= GetRole(Userid.replace(": READER",""));
       // appendLog("Report:"+Role.toString()+":::"+Userid.toString());
        eduser.setText(Userid.replace(": READER","")+": "+Role);

        DatabaseHandler db = new DatabaseHandler(getApplicationContext());
        String Sql="";
        if (Role.equals("READER")) {
            Sql = " select 'MRO Download' Head ,Count(*) cnt  from MRO_Detail " +
                    " union " +
                    " Select 'Reading Taken' Head ,Count(*) cnt  from MR_Detail " +
                    " union " +
                    " Select 'MR Not Upload' Head ,Count(*) cnt  from MR_Detail where IsTransfer<>'Y' " +
                    " union " +
                    " Select 'MR Upload' Head ,Count(*) cnt  from MR_Detail where IsTransfer='Y' " +
                    " union " +
                    " Select 'Optical' Head ,Count(*) cnt  from MR_Detail where DataMode='OPTICAL' " +
                    " union " +
                    " Select 'MANUAL' Head ,Count(*) cnt  from MR_Detail where DataMode='MANUAL' ";
        }
        else    {
            Sql = " select 'MRO Download' Head ,Count(*) cnt  from MRO_Detail_Supervisor " +
                    " union " +
                    " Select 'Reading Taken' Head ,Count(*) cnt  from MRO_Detail_Supervisor where snotetype is not null" +
                    " union " +
                    " Select 'MR Not Upload' Head ,Count(*) cnt  from MRO_Detail_Supervisor where DataMode is not null and  IsTransfer<>'Y' " +
                    " union " +
                    " Select 'MR Upload' Head ,Count(*) cnt  from MRO_Detail_Supervisor where IsTransfer='Y' " +
                    " union " +
                    "Select 'Optical' Head ,Count(*) cnt  from MRO_Detail_Supervisor where DataMode='OPTICAL' "+
                    " union " +
                    "Select 'MANUAL' Head ,Count(*) cnt  from MRO_Detail_Supervisor where DataMode='MANUAL' " ;

        }
      // appendLog("SQL::"+Sql);

        try {
           // appendLog("try");
             Cursor c = db.GetData(Sql);

            //Move the cursor to the first row.
            if (c.moveToFirst()) {
                String TotMro="0";
                String TotRead="0";
              //  appendLog("Cursor");
                do {

                    String Head = c.getString(c.getColumnIndex("Head"));
                    String HeadCnt = c.getString(c.getColumnIndex("cnt"));
                    if (Role.equals("READER")) {
                        if (Head.equals("MRO Download" ))
                        {
                            btnmroDownload.setText("MRO Download :::::  "+ HeadCnt);
                            TotMro=HeadCnt;
                        }
                        if (Head.equals("Reading Taken" ))
                        {
                            btnReadTaken.setText("Reading Taken :::::  "+ HeadCnt);
                            TotRead=HeadCnt;
                        }
                        if (Head.equals("MR Not Upload" ))
                        {
                            btnReadNotUploaded.setText("Reading Not Uploaded :::::  "+ HeadCnt);
                        }
                        if (Head.equals("MR Upload" ))
                        {
                            btnReadUploaded.setText("Reading Uploaded :::::  "+ HeadCnt);
                        }
                        if (Head.equals("MANUAL" ))
                        {
                            btnMReading.setText("Manual Reading  :::::  "+ HeadCnt);
                        }
                        if (Head.equals("Optical" ))
                        {
                            btnOPReading.setText("Optical Uploaded :::::  "+ HeadCnt);
                        }

                    }
                    else {
                        if (Head.equals("MRO Download" ))
                        {
                            btnmroDownload.setText("MRO Download :::::  "+ HeadCnt);
                            TotMro=HeadCnt;
                        }
                        if (Head.equals("Reading Taken" ))
                        {
                            btnReadTaken.setText("Reading Taken :::::  "+ HeadCnt);
                            TotRead=HeadCnt;
                        }
                        if (Head.equals("MR Not Upload" ))
                        {
                            btnReadNotUploaded.setText("Reading Not Uploaded :::::  "+ HeadCnt);
                        }
                        if (Head.equals("MR Upload" ))
                        {
                            btnReadUploaded.setText("Reading Uploaded :::::  "+ HeadCnt);
                        }
                        if (Head.equals("MANUAL" ))
                        {
                            btnMReading.setText("Manual Reading  :::::  "+ HeadCnt);
                        }
                        if (Head.equals("Optical" ))
                        {
                            btnOPReading.setText("Optical Download :::::  "+ HeadCnt);
                        }

                    }



                } while (c.moveToNext());//Move the cursor to the next row.

                int inum = Integer.parseInt(TotMro) - Integer.parseInt(TotRead);
                btnPending.setText("Pending Reading :::" +inum ) ;

            }
            else {
                appendLog("Else Cursor");
            }
        }
        catch (Exception ex){
            appendLog("Error::"+ex.getMessage());
        }


    }

    public void onbtnDownloadClicked(View v)
    {}
    public void onbtnReadTakenClicked(View v)
    {}
    public void onbtnReadUploadedClicked(View v)
    {}
    public void onbtnReadNotUploadedClicked(View v)
    {}
    public String GetRole(String VCPID) {
        String Role = "-";
        try {
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            String Sql = "select Role from Login where Userid='" + VCPID + "'";
            Cursor c = db.GetData(Sql);
            if (c.moveToFirst()) {
                do {
                    Role = c.getString(c.getColumnIndex("Role"));

                } while (c.moveToNext());
            }
            return Role;
        } catch (Exception ex) {
            return Role;

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

}
