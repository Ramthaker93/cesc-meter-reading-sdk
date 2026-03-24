
package com.npcl.com.vcpopdl;

import android.database.Cursor;
import androidx.appcompat.app.AppCompatActivity;



import androidx.core.app.ActivityCompat;
import 	androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import android.os.Bundle;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;
import android.widget.TextView;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.View;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import android.content.Intent;
import android.provider.MediaStore;
import android.net.Uri;



import  android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;

import android.graphics.Bitmap;
import android.widget.ImageView;
import java.io.File;

import android.os.Environment;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;

import android.graphics.Matrix;
import android.os.Build;

public class MReading extends AppCompatActivity {

    // ===== CHILD DROPDOWN DATA BASED ON PARENT =====
    private final Map<String, List<String>> subMRMap = new HashMap<String, List<String>>() {{
        put("11", Arrays.asList("Cable-Supply On", "Cable-No Supply", "Meter not seen", "Suspected Misuse of Supply"));
        put("16", Arrays.asList("Meter Box Sealed", "Optical Reading Failed (CMRI)", "Remote Reading Failed (BLE/LPR)",
                "LPR Modem cable in Port", "Meter Panel Locked/ Inside Fence", "Honey Bee Inside Box",
                "No Power in Area", "TD at Site", "Suspected Misuse of Supply", "Tariff Misuse"));
        put("17", Arrays.asList("No Display in Meter", "No meter at Site", "Meter Burnt"));
        put("21", Arrays.asList("Defective Display", "Glass Blur/Broken", "Cement on Glass", "Suspected Misuse of Supply"));
        put("23", Arrays.asList("METER BOX DAMAGED", "METER BROKEN", "Push Button Not Working", "Suspected Misuse of Supply"));
        put("26", Arrays.asList("Terminal Burnt", "Meter Burnt"));
        put("60", Arrays.asList("INPUT CABLE NOT CONNECTED", "NO SUPPLY IN INPUT CABLE", "Additional Meter Found"));
        put("90", Arrays.asList("INPUT CABLE NOT CONNECTED FROM POLE", "INPUT CABLE NOT AVAILABLE",
                "Direct Supply", "Line not available in the area"));
        put("91", Arrays.asList("WRONG GPS", "PDC", "Consumer NOT TRACEBLE IN LOCALITY"));
        put("92", Arrays.asList("SUSPECTED ACTIVITY AT SITE", "SELF READING SUBMITED", "LEGAL DISPUTE AREA"));
        put("93", Arrays.asList("Meter Box Lock", "Panel Lock", "Consumer Locked the meter"));
        put("94", Arrays.asList("WATER LOGGING", "Meter At Height", "Obstacle by consumer", "Road Not approachable"));
        put("95", Arrays.asList("NO METER IN BOX-(Blue Cable Available)", "NO METER AT SITE-(Blue Cable Missing)",
                "Permanent Disconnected", "DIRECT SUPPLY WITH INPUT CABLE"));
        put("97", Arrays.asList("DIRECT THEFT", "SUPPLY IN USE FROM NEIGHBOUR", "LINE DISCONNECTED FROM POLE", "INPUT CABLE DISCONNECTED",
                "OUTPUT CABLE DISCONNECTED", "NO SUPPLY IN INPUT CABLE"));
        put("98", Arrays.asList("CONFIRMED FROM OFFICE", "CONFIRMED FROM CescRaj CREW"));
        put("SR", Arrays.asList("NOT IN USE", "LOCKED"));
        put("40", Arrays.asList("N/A", "THEFT"));
    }};
    private ImageView imageHolder;
    private final int requestCode = 20;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            RadioGroup radioGroup;
            //TextView ConsNo;
            EditText ConsNo;
            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_mreading);
            Bundle bundle = getIntent().getExtras();
            String text = bundle.getString("Data");
            String[] Row2 = text.split("#");
            if (Row2.length > 0) {
                EditText eduser = (EditText) findViewById(R.id.txtuser);
                String LoginUser = Row2[1].toString();
                eduser.setText(LoginUser);
            }


            BindMRNote();

            // =====================================================================
// CHILD DROPDOWN HANDLING
            Spinner ddlMRNote = findViewById(R.id.ddlMRNote);
            Spinner ddlSubMRNote = findViewById(R.id.ddlSubMRNote);
            View rowSubMR = findViewById(R.id.tableRow10_1);

// hide child dropdown first
            rowSubMR.setVisibility(View.GONE);

            ddlMRNote.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {

                    String selected = ddlMRNote.getSelectedItem().toString();

                    if (selected.startsWith("--Select")) {
                        rowSubMR.setVisibility(View.GONE);
                        return;
                    }

                    // extract number before ":"
                    String key = selected.split(":")[0].trim();

                    if (subMRMap.containsKey(key)) {
                        // show child dropdown
                        rowSubMR.setVisibility(View.VISIBLE);

                        List<String> items = new ArrayList<>();
                        items.add("--Select Sub MR Code--");
                        items.addAll(subMRMap.get(key));

                        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                                MReading.this,
                                android.R.layout.simple_spinner_item,
                                items
                        );
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                        ddlSubMRNote.setAdapter(adapter);
                    } else {
                        rowSubMR.setVisibility(View.GONE);
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) { }
            });
// =====================================================================




            // Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show();
            ConsNo = (EditText) findViewById(R.id.txtConsumerNo);
            //text="229299292:222";
            String[] Row = text.split(":");
            if (Row.length > 0) {
                RadioButton rbConsNo = (RadioButton) findViewById(R.id.rbConsNo);
                rbConsNo.setChecked(true);
                String ConsumerNo = Row[0].toString();
                ConsNo.setText(ConsumerNo);

                DatabaseHandler db = new DatabaseHandler(getApplicationContext());
                String Sql;

                rbConsNo = (RadioButton) findViewById(R.id.rbConsNo);
                RadioButton rbMeterNo = (RadioButton) findViewById(R.id.rbMeterNo);
                ConsNo = (EditText) findViewById(R.id.txtConsumerNo);
                String Fileter = ConsNo.getText().toString();
                if (rbConsNo.isChecked())
                    Sql = "Select ablbelnr,ConsumerNo,MeterNo,Name,Co,HouseNo,Street,City,Portion from mro_detail  where ConsumerNo='" + Fileter + "'";
                else
                    Sql = "Select ablbelnr,ConsumerNo,MeterNo,Name,Co,HouseNo,Street,City,Portion from mro_detail  where MeterNo='" + Fileter + "'";
                String Cons = "";
                String Mtrno;
                String Name = "";
                String Co = "";
                String HouseNo = "";
                String Street = "";
                String city = "";
                String portion = "";
                Cursor c = db.GetData(Sql);
                if (c.moveToFirst()) {
                    do {
                        Cons = c.getString(c.getColumnIndex("ConsumerNo"));
                        Mtrno = c.getString(c.getColumnIndex("MeterNo"));
                        Name = c.getString(c.getColumnIndex("Name"));
                        Co = c.getString(c.getColumnIndex("Co"));
                        HouseNo = c.getString(c.getColumnIndex("HouseNo"));
                        Street = c.getString(c.getColumnIndex("Street"));
                        city = c.getString(c.getColumnIndex("City"));
                        portion = c.getString(c.getColumnIndex("Portion"));

                    } while (c.moveToNext());//Move the cursor to the next row.

                    /// Already Read Check
                    String RecCount = "";
                    Sql = "Select Count(*) cnt from MR_Detail where ConsumerNo ='" + Cons + "'";
                    c = db.GetData(Sql);
                    if (c.moveToFirst()) {
                        do {
                            RecCount = c.getString(c.getColumnIndex("cnt"));
                        } while (c.moveToNext());

                        if (RecCount.equals("0")) {
                            String X="0";

                        } else {
                            Toast.makeText(getApplicationContext(), "Consumer Reading Already Taken, Please move to next Consumer", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }

                    ///
                    TextView lbConsNo = (TextView) findViewById(R.id.lbConsumerNo);
                    TextView lbMeterNo = (TextView) findViewById(R.id.lbMeterNo);
                    TextView lbName = (TextView) findViewById(R.id.lbName);
                    TextView lbAddress = (TextView) findViewById(R.id.lbAddress);
                    TextView lbStreet = (TextView) findViewById(R.id.lbStreet);


                    lbConsNo.setText(Cons);
                    lbMeterNo.setText(Mtrno);
                    lbName.setText(Name);
                    lbAddress.setText(Co + "," + HouseNo);
                    lbStreet.setText(Street);

                } else {


                    TextView lbConsNo = (TextView) findViewById(R.id.lbConsumerNo);
                    TextView lbMeterNo = (TextView) findViewById(R.id.lbMeterNo);
                    TextView lbName = (TextView) findViewById(R.id.lbName);
                    TextView lbAddress = (TextView) findViewById(R.id.lbAddress);
                    TextView lbStreet = (TextView) findViewById(R.id.lbStreet);
                    lbConsNo.setText("");
                    lbMeterNo.setText("");
                    lbName.setText("");
                    lbAddress.setText("");
                    lbStreet.setText("");
                }

                String LastKWHVal = "";
                Sql = "Select ExpectedReading, CurrentReading  from mro_Detail where Unit ='KWH' and  ConsumerNo='" + Cons + "'";
                c = db.GetData(Sql);
                if (c.moveToFirst()) {
                    do {
                        LastKWHVal = c.getString(c.getColumnIndex("CurrentReading"));


                    } while (c.moveToNext());//Move the cursor to the next row.
                    // TextView LASTKWH = (TextView) findViewById(R.id.hidden_KWH);
                    // LASTKWH.setText(LastKWHVal);
                }
            }

            String[] Row1 = text.split("#");
            if (Row.length > 0) {
                EditText eduser = (EditText) findViewById(R.id.txtuser);
                String LoginUser = Row1[1].toString();
                eduser.setText(LoginUser);
            }
        }
        catch (Exception ex)
        {
            String Error= ex.getMessage().toString();


        }
    }

    public void BindMRNote()
    {
        try {
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            String result = db.onCreate();
            Cursor c = db.GetData("Select NoteId||\" : \"|| Desc Note  from Note_type  ");
            List<String> list = new ArrayList<String>();
            if (c.moveToFirst()) {
                list.add("--Select MR Code--");
                do {
                    list.add(c.getString(c.getColumnIndex("Note")));

                } while (c.moveToNext());//Move the cursor to the next row.

                Spinner ddlMRNote = (Spinner) findViewById(R.id.ddlMRNote);
                ArrayAdapter<String> dataAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, list);
                dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                ddlMRNote.setAdapter(dataAdapter);
            }
        }
        catch ( Exception ex)
        {
            String Msg= ex.getMessage().toString();

        }
    }

    public  void onShowPhotoClicked(View v)
    {
        try {
            int MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 123;
            Uri mCapturedImageURI;
            int REQUEST_IMAGE_CAPTURE = 2;
            int REQUEST_EXTERNAL_STORAGE = 1;
            Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            int permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
            appendLog("Test  1  ");
            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                String Permission= "Fail";
                appendLog("Test  2  ");
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_EXTERNAL_STORAGE);
            }
            else
            {

                //ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_EXTERNAL_STORAGE);
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_EXTERNAL_STORAGE);
                appendLog("Test  3  ");
            }
            permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
            ActivityCompat.requestPermissions(this,new String[] { Manifest.permission.READ_EXTERNAL_STORAGE }, MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE);
            permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
            ///New Code
            appendLog("Test  4  ");
            String partFilename = currentDateFormat();
            TextView  lbConsNo = (TextView) findViewById(R.id.lbConsumerNo);
            String Fileter = lbConsNo.getText().toString();
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmm",Locale.getDefault()).format(new Date());
            String fileName =    Fileter +"_"+ timeStamp+".jpg";
            File file = new File(Environment.getExternalStorageDirectory(), fileName);
            // mCapturedImageURI = Uri.fromFile(file);

            appendLog("Test  5  ");

            TextView ImageName = (TextView) findViewById(R.id.hidden_Image);
            ImageName.setText(fileName);


            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            appendLog("Test  6  ");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mCapturedImageURI = FileProvider.getUriForFile(getApplicationContext(), "com.npcl.com.vcpopdl.EzetapFileProvider", file);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                mCapturedImageURI = Uri.fromFile(file);
            }
            appendLog("Test  7  ");
            intent.putExtra(MediaStore.EXTRA_OUTPUT, mCapturedImageURI);
            startActivityForResult(intent, requestCode);

            //------
            imageHolder = (ImageView)findViewById(R.id.imageView1);
            // Intent photoCaptureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            // startActivityForResult(photoCaptureIntent.putExtra(MediaStore.EXTRA_OUTPUT,true ) , requestCode);
            appendLog("Test  8  ");


        }
        catch (Exception ex)
        {
            String Result = ex.getMessage().toString();
            appendLog("TestCamara"+ ex.getMessage());
        }
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        try {
            super.onActivityResult(requestCode, resultCode, data);
            if (this.requestCode == requestCode && resultCode == RESULT_OK)
            {

                TextView ImageName = (TextView) findViewById(R.id.hidden_Image);
                String fileName = ImageName.getText().toString();

                //// My New Code

                File[] files = Environment.getExternalStorageDirectory().listFiles();
                String path= Environment.getExternalStorageDirectory()+ "/" + fileName;
                File imageFile = new File(Environment.getExternalStorageDirectory()+ "/" + fileName);
                FileInputStream fis = new FileInputStream(imageFile );


                Bitmap bitmap = BitmapFactory.decodeStream(fis);

                if ( storeCameraPhotoInSDCard(bitmap, fileName) == true) {
                    Bitmap mBitmap = getImageFileFromSDCard(fileName);
                    imageHolder.setImageBitmap(mBitmap);
                }
                else
                {


                }

            }
        }
        catch (Exception ex)
        {
            String Error= ex.getMessage().toString();

        }
    }

    private String currentDateFormat(){
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HH_mm_ss");
        String  currentTimeStamp = dateFormat.format(new Date());
        return currentTimeStamp;
    }

    public void DeletePhoto(String FileName)
    {
        try {

            File[] files = Environment.getExternalStorageDirectory().listFiles();
            File imageFile = new File(Environment.getExternalStorageDirectory() +"/"+ FileName);
            if (imageFile.exists()) {
                imageFile.delete();
            }
        }
        catch (Exception ex)
        {}

    }

    private boolean  storeCameraPhotoInSDCard(Bitmap bitmap, String currentDate){
        File myDirectory = new File(Environment.getExternalStorageDirectory(), ".vcpsysdata");

        if(!myDirectory.exists()) {
            myDirectory.mkdirs();
        }

        //File outputFile = new File(Environment.getExternalStorageDirectory(),  currentDate );
        File outputFile = new File(myDirectory, currentDate );

        try {
            outputFile.setReadOnly();
            FileOutputStream fileOutputStream = new FileOutputStream(outputFile);
            Matrix matrix = new Matrix();
            matrix.postRotate(90);
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, fileOutputStream);

            fileOutputStream.flush();
            fileOutputStream.close();

            ///New Code Check Photo Save Or Not
            File imageFile = new File(Environment.getExternalStorageDirectory() + "/.vcpsysdata/" + currentDate);
            if(imageFile.exists())
            {
                DeletePhoto(currentDate);
                return true;
            }
            else
            {
                return false;
            }
            //****
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return false;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        catch (Exception ex)
        {
            return false;
        }
    }

    private Bitmap getImageFileFromSDCard(String filename) {
        Bitmap bitmap = null;

        File[] files = Environment.getExternalStorageDirectory().listFiles();
        File imageFile = new File(Environment.getExternalStorageDirectory() +"/.vcpsysdata/"+ filename);
        try {
            FileInputStream fis = new FileInputStream(imageFile );
            bitmap = BitmapFactory.decodeStream(fis);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        TextView ImageName = (TextView) findViewById(R.id.hidden_Image);
        ImageName.setText(filename);
        return bitmap;
    }

    public void onbtnSaveClicked(View v)
    {
        try {

            if (ValidateData()== true)
            {
                DatabaseHandler db = new DatabaseHandler(getApplicationContext());
                String result= db.onCreate();
                EditText KWH;
                EditText KVARH;
                EditText KVAH;
                EditText KvA;
                TextView ImgName;
                Spinner spinner1;
                TextView Cons;
                TextView MeterNumber;
                EditText eduser = (EditText)findViewById(R.id.txtuser);

                KWH = (EditText) findViewById(R.id.txtKWH);
                KvA = (EditText) findViewById(R.id.txtKVA);
                ImgName = (TextView) findViewById(R.id.hidden_Image);
                spinner1 = (Spinner) findViewById(R.id.ddlMRNote);
                KVARH=(EditText) findViewById(R.id.txtKVARH);
                KVAH      =(EditText) findViewById(R.id.txtKVAH);
                Cons = (TextView) findViewById(R.id.lbConsumerNo) ;
                MeterNumber = (TextView) findViewById(R.id.lbMeterNo) ;

                String KWHVal= KWH.getText().toString();
                String KVARHVal= KVARH.getText().toString();
                String KVAHVal= KVAH.getText().toString();
                String KvAVal= KvA.getText().toString();
                String MRCode= String.valueOf(spinner1.getSelectedItem());
                String ImageName= ImgName.getText().toString();
                String ConsumerNo= Cons.getText().toString();
                String MeterNo = MeterNumber.getText().toString();
                String User=eduser.getText().toString().replace("Welcome:::","")  ;
                String[] temp = MRCode.split(":");
                MRCode= temp[0].toString();

                if (KVARHVal.equals("") ){KVARHVal="0";}
                if (KVAHVal.equals("") ){KVAHVal="0";}

                if (SaveData1(ConsumerNo,KWHVal,KVARHVal,KVAHVal,KvAVal,ImageName,User,MRCode,"") == true) {

                    SaveData(ConsumerNo,KWHVal,KVARHVal,KVAHVal,KvAVal,ImageName,User,MRCode,MeterNo,"");

                    Toast.makeText(getApplicationContext(),"Record Save Successfully..! ", Toast.LENGTH_LONG).show();
                }
                else
                {
                    Toast.makeText(getApplicationContext(),"Error While Record Saving..! ", Toast.LENGTH_LONG).show();
                }

            }
        }
        catch (Exception ex)
        {
            Toast.makeText(getApplicationContext(),"Error While Record Saving..! ", Toast.LENGTH_SHORT).show();
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
    public boolean SaveData1(String ConsumerNo, String Reg1, String Reg2, String Reg3, String Reg4, String photoId, String userid, String NoteType, String MobileNo) {
        try {
            String GpsCoordinate = GetLocation();
            String NewMeterNo = "";
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HH_mm_ss");
            String currentTimeStamp = dateFormat.format(new Date());
            String SysDate = dateFormat.format(new Date()).replace("_", "");

         /*   if (GpsCoordinate.equals("") )
            {
                Toast.makeText(getApplicationContext(),"Please On the GPS Location First ...! ", Toast.LENGTH_SHORT).show();
                return false;
            }
*/

            String Sql = "insert into mr_detail (ConsumerNo,gpscoordinate,Mrdatetime, Reg1, Reg2, Reg3,reg4,photoId, Istransfer, userid, entrydate,NoteType,NewMeterNo,MobileNo,IsPhototransfer,DataMode ) " +
                    " Values (" +
                    "'" + ConsumerNo + "','" + GpsCoordinate + "' , '" + SysDate + "', '" + Reg1 + "','" + Reg2 + "','" + Reg3 + "','" + Reg4 + "',  '" + photoId + "','N' ,'" + userid + "' ,'" + SysDate + "','" + NoteType + "','" + NewMeterNo + "' ,'" + MobileNo + "','N','MANUAL')";

            CreateLog(ConsumerNo + ":" + SysDate + ":" + Reg1 + ":" + Reg2 + ":" + Reg3 + ":" + Reg4 + ":" + photoId + ":" + NoteType);
            DatabaseHandler Obj = new DatabaseHandler(getApplicationContext());

            Obj.ExecuteQry(Sql);
            ClearData();
            return true;
        } catch (Exception ex) {

            String Error = ex.getMessage().toString();
            return false;
        }
    }
    public boolean SaveData(String ConsumerNo, String Reg1, String Reg2, String Reg3, String Reg4,
                            String photoId, String userid, String NoteType, String MeterNo,String MobileNo) {

        try {
            // Timestamp
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HH_mm_ss");
            String SysDate = dateFormat.format(new Date()).replace("_", "");

            // ===== GET Meter Number again =====
            //TextView meterNoText = findViewById(R.id.lbMeterNo);
            //String MeterNo = meterNoText.getText().toString().trim();

            // ===== GET Parent MR Code again =====
            //Spinner spinner1 = findViewById(R.id.ddlMRNote);
            //String MRCodeFull = spinner1.getSelectedItem().toString();
            //String MRCode = MRCodeFull.split(":")[0].trim();

            // ===== GET Sub MR (Child MR) =====
            Spinner ddlSubMRNote = findViewById(R.id.ddlSubMRNote);
            String SubRemark = "";

            if (ddlSubMRNote.getVisibility() == View.VISIBLE) {
                SubRemark = ddlSubMRNote.getSelectedItem().toString();
            }

            // Avoid SQL crash for apostrophes
            SubRemark = SubRemark.replace("'", "''");

            // ===== FINAL SQL INSERT =====
            String Sql = "INSERT INTO Reading_Remarks " +
                    "(Consumer_number, Meter_number, Created_on, VCP_ID, Main_mr_code, Sub_remarks, MRMODE, IsTransfer) " +
                    "VALUES (" +
                    "'" + ConsumerNo + "'," +
                    "'" + MeterNo + "'," +
                    "'" + SysDate + "'," +
                    "'" + userid + "'," +
                    "'" + NoteType + "'," +
                    "'" + SubRemark + "'," +
                    "'Manual'," +
                    "'N')";

            DatabaseHandler Obj = new DatabaseHandler(getApplicationContext());
            Obj.ExecuteQry(Sql);

            ClearData();
            return true;

        } catch (Exception ex) {
            Toast.makeText(getApplicationContext(), "Insert Error: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    public void CreateLog(String Data)
    {
        try
        {
            String rootPath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/VCPlog/";
            File root = new File(rootPath);
            if (!root.exists()) {
                root.mkdirs();
            }

            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
            String  currentTimeStamp = dateFormat.format(new Date());
            String  SysDate = dateFormat.format(new Date()).replace("_","");

            File f = new File(rootPath + "ReadingData_" +SysDate +".Log");
            if (!f.exists()) {
                f.createNewFile();
            }

            FileOutputStream fileOutputStream = new FileOutputStream(f,true);
            fileOutputStream.write((Data + System.getProperty("line.separator")).getBytes());

            fileOutputStream.close();


        }
        catch (Exception ex)
        {

            String Err=ex.getMessage().toString();
        }
    }
    public boolean ValidateData()
    {
        TextView Cons;
        EditText KWH;
        EditText KvA;
        Spinner spinner1;
        TextView ImgName;
        TextView LastKwh;
        try {
            KWH = (EditText) findViewById(R.id.txtKWH);
            KvA = (EditText) findViewById(R.id.txtKVA);
            ImgName = (TextView) findViewById(R.id.hidden_Image);
            Cons = (TextView) findViewById(R.id.lbConsumerNo);
            spinner1 = (Spinner) findViewById(R.id.ddlMRNote);

            String KWHVal= KWH.getText().toString();
            String KvAVal= KvA.getText().toString();
            String MRCode= String.valueOf(spinner1.getSelectedItem());
            String ImageName= ImgName.getText().toString();
            String Consumerno= Cons.getText().toString();

            String GpsCoordinate =GetLocation();



            // Toast.makeText(getApplicationContext(),GpsCoordinate, Toast.LENGTH_SHORT).show();
        /*    if (GpsCoordinate.equals("0.0~0.0"))
            {
                Toast.makeText(getApplicationContext(),"Please On the GPS Location First ...! ", Toast.LENGTH_SHORT).show();
                return false;

            }
            if (GpsCoordinate.equals(""))
            {
                Toast.makeText(getApplicationContext(),"Please On the GPS Location First ...! ", Toast.LENGTH_SHORT).show();
                return false;

            }
            */
            if (Consumerno.equals("") )
            {
                Toast.makeText(getApplicationContext(),"Please Search Consumer No...! ", Toast.LENGTH_SHORT).show();
                return false;
            }

            // String LastKwhVal= LastKwh.getText().toString();
            if (KWHVal.equals("") )
            {
                Toast.makeText(getApplicationContext(),"Please Enter KWh Reading..! ", Toast.LENGTH_SHORT).show();
                return false;
            }
            if ( KvAVal.equals("") )
            {
                Toast.makeText(getApplicationContext(),"Please Enter KvA Reading..! ", Toast.LENGTH_SHORT).show();
                return false;
            }

            if ( MRCode== "--Select MR Code--")
            {

                Toast.makeText(getApplicationContext(),"Please Select MR Code..! ", Toast.LENGTH_SHORT).show();
                return false;
            }
            if( ImageName.equals("hidden value") || ImageName.equals("") )
            {
                Toast.makeText(getApplicationContext(),"Please Take Photo..! ", Toast.LENGTH_SHORT).show();
                return false;

            }

            String[] temp = MRCode.split(":");
            MRCode= temp[0].toString().trim();
            if (MRCode.equals("16"))
            {
              /*  String CurrentReading = GetCurrentReading(Consumerno);
                double CurrentReadingVal = Double.parseDouble(CurrentReading);
                double PutKWHVal = Double.parseDouble(KWHVal);
                if (CurrentReadingVal >= PutKWHVal )
                {

                    Toast.makeText(getApplicationContext(),"Please Check, Current Reading Can't Less then Previous Reading ..! ", Toast.LENGTH_SHORT).show();
                    return false;


                }
                */
            }

            // ============= CHILD MR VALIDATION ===================
            Spinner ddlSubMRNote = findViewById(R.id.ddlSubMRNote);

            if (ddlSubMRNote.getVisibility() == View.VISIBLE &&
                    ddlSubMRNote.getSelectedItem().toString().startsWith("--Select")) {

                Toast.makeText(getApplicationContext(), "Please Select Sub MR Code..!", Toast.LENGTH_SHORT).show();
                return false;
            }
            // ======================================================


            return true;
        }
        catch ( Exception ex)
        {
            String Error= ex.getMessage().toString();
            return false;
        }

    }

    private  String Chooes="X";
    private boolean confirmDialogDemo() {

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Confirm Reading Cceck !");
        builder.setMessage("Current Reading Can't be less then Previous Reading..! Do you really want to proceed ?");
        builder.setCancelable(false);
        builder.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                Chooes="Y";
            }
        });

        builder.setNegativeButton("No", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                Chooes="N";
            }
        });
        builder.show();

        if (Chooes.equals("Y"))
        {
            return true;
        }
        else
        {
            return false;
        }




    }

    public void onShowButtonClicked(View v) {
        try {
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            String Sql;
            EditText ConsNo;
            RadioButton rbConsNo = (RadioButton) findViewById(R.id.rbConsNo);
            RadioButton rbMeterNo = (RadioButton) findViewById(R.id.rbMeterNo);
            ConsNo = (EditText) findViewById(R.id.txtConsumerNo);
            String Fileter = ConsNo.getText().toString();
            if (rbConsNo.isChecked())
                Sql = "Select ConsumerNo,MeterNo,Name,Co,HouseNo,Street,City,Portion from mro_detail  where ConsumerNo='" + Fileter + "'";
            else
                Sql = "Select ConsumerNo,MeterNo,Name,Co,HouseNo,Street,City,Portion from mro_detail  where MeterNo='" + Fileter + "'";
            String Cons = "";
            String Mtrno;
            String Name = "";
            String Co = "";
            String HouseNo = "";
            String Street = "";
            String city = "";
            String portion = "";
            Cursor c = db.GetData(Sql);
            if (c.moveToFirst()) {
                do {
                    Cons = c.getString(c.getColumnIndex("ConsumerNo"));
                    Mtrno = c.getString(c.getColumnIndex("MeterNo"));
                    Name = c.getString(c.getColumnIndex("Name"));
                    Co = c.getString(c.getColumnIndex("Co"));
                    HouseNo = c.getString(c.getColumnIndex("HouseNo"));
                    Street = c.getString(c.getColumnIndex("Street"));
                    city = c.getString(c.getColumnIndex("City"));
                    portion = c.getString(c.getColumnIndex("Portion"));

                } while (c.moveToNext());//Move the cursor to the next row.

                /// Already Read Check
                String RecCount="";
                Sql = "Select Count(*) cnt from MR_Detail where ConsumerNo ='" + Cons + "'";
                c = db.GetData(Sql);
                if (c.moveToFirst()) {
                    do {
                        RecCount = c.getString(c.getColumnIndex("cnt"));
                    } while (c.moveToNext());

                    if (RecCount.equals("0")) {


                    } else {
                        Toast.makeText(getApplicationContext(), "Consumer Reading Already Taken, Please move to next Consumer", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }

                ///
                TextView lbConsNo = (TextView) findViewById(R.id.lbConsumerNo);
                TextView lbMeterNo = (TextView) findViewById(R.id.lbMeterNo);
                TextView lbName = (TextView) findViewById(R.id.lbName);
                TextView lbAddress = (TextView) findViewById(R.id.lbAddress);
                TextView lbStreet = (TextView) findViewById(R.id.lbStreet);


                lbConsNo.setText(Cons);
                lbMeterNo.setText(Mtrno);
                lbName.setText(Name);
                lbAddress.setText(Co + "," + HouseNo);
                lbStreet.setText(Street);

            } else {


                TextView lbConsNo = (TextView) findViewById(R.id.lbConsumerNo);
                TextView lbMeterNo = (TextView) findViewById(R.id.lbMeterNo);
                TextView lbName = (TextView) findViewById(R.id.lbName);
                TextView lbAddress = (TextView) findViewById(R.id.lbAddress);
                TextView lbStreet = (TextView) findViewById(R.id.lbStreet);
                lbConsNo.setText("");
                lbMeterNo.setText("");
                lbName.setText("");
                lbAddress.setText("");
                lbStreet.setText("");
            }

            String LastKWHVal = "";
            Sql = "Select ExpectedReading, CurrentReading  from mro_Detail where Unit ='KWH' and  ConsumerNo='" + Cons + "'";
            c = db.GetData(Sql);
            if (c.moveToFirst()) {
                do {
                    LastKWHVal = c.getString(c.getColumnIndex("CurrentReading"));


                } while (c.moveToNext());//Move the cursor to the next row.
                // TextView LASTKWH = (TextView) findViewById(R.id.hidden_KWH);
                // LASTKWH.setText(LastKWHVal);
            }
        } catch (Exception ex) {

            String Msg = ex.getMessage().toString();
        }
    }
    public void onRadioButtonClicked(View v) {

        RadioButton rbConsNo = (RadioButton) findViewById(R.id.rbConsNo);
        RadioButton rbMeterNo = (RadioButton) findViewById(R.id.rbMeterNo);


        //is the current radio button now checked?
        boolean  checked = ((RadioButton) v).isChecked();

        //now check which radio button is selected
        //android switch statement
        switch(v.getId()){
            case R.id.rbConsNo:
                if(checked)
                    rbMeterNo.setChecked(false);
                break;
            case R.id.rbMeterNo:
                if(checked)
                    rbConsNo.setChecked(false);
                break;
        }
    }

    public void ClearData()
    {
        EditText ConsNo;
        TextView lbConsno;
        TextView lbMeterno;
        TextView lbName;
        TextView lbAddress;
        TextView lbStreet;
        EditText KWH;
        EditText KVARH;
        EditText KVAH;
        EditText KVA;
        Spinner MRnote;
        ImageView ImgVw;
        try
        {
            ConsNo = (EditText) findViewById(R.id.txtConsumerNo);
            lbConsno = (TextView) findViewById(R.id.lbConsumerNo);
            lbMeterno = (TextView) findViewById(R.id.lbMeterNo);
            lbName = (TextView) findViewById(R.id.lbName);
            lbAddress = (TextView) findViewById(R.id.lbAddress);
            lbStreet = (TextView) findViewById(R.id.lbStreet);
            KWH     = (EditText) findViewById(R.id.txtKWH);
            KVARH     = (EditText) findViewById(R.id.txtKVARH);
            KVAH     = (EditText) findViewById(R.id.txtKVAH);
            KVA     = (EditText) findViewById(R.id.txtKVA);
            MRnote =(Spinner) findViewById(R.id.ddlMRNote);
            ImgVw = (ImageView) findViewById(R.id.imageView1);
            TextView   ImgName = (TextView) findViewById(R.id.hidden_Image);

            ImgName.setText("");
            ConsNo.setText("");
            lbConsno.setText("");
            lbMeterno.setText("");
            lbName.setText("");
            lbAddress.setText("");
            lbStreet.setText("");
            KWH.setText("");
            KVARH.setText("");
            KVAH.setText("");
            KVA.setText("");
            MRnote.setSelection(0);
            ImgVw.setImageDrawable(null);




        }
        catch (Exception ex)
        {

        }
    }

    public String GetCurrentReading(String Consumerno)
    {
        try
        {
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            String Sql ="select  CurrentReading  from  MRO_Detail where ConsumerNo='"+Consumerno +"'";
            String CurrentReading ="0";
            Cursor c = db.GetData(Sql);
            if (c.moveToFirst()) {
                do {
                    CurrentReading = c.getString(c.getColumnIndex("CurrentReading"));
                } while (c.moveToNext());//Move the cursor to the next row.
            }
            return CurrentReading;
        }
        catch (Exception ex)
        {
            return "0";
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
