package com.npcl.com.vcpopdl;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import 	androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.OnItemSelectedListener;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;

public class Reading_Supervisor extends AppCompatActivity {

    private ImageView imageHolder;
    private final int requestCode = 20;
    private int CallCamera;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        try {
            RadioGroup radioGroup;
            //TextView ConsNo;
            EditText ConsNo;
            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_reading__supervisor);
//---------************************************************************
       /*     EditText editText = (EditText) findViewById(R.id.txtNewMeterNo);
            editText.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean hasFocus) {
                    if (hasFocus) {
                        Spinner MRNote;
                        MRNote = (Spinner) findViewById(R.id.ddlMRNote);
                        String MRCode= String.valueOf(MRNote.getSelectedItem());
                        String[] temp = MRCode.split(":");
                        MRCode= temp[0].toString();

                        Toast.makeText(getApplicationContext(), MRCode, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(getApplicationContext(), "Lost the focus", Toast.LENGTH_LONG).show();
                    }
                }
            });*/
//--------******************************
            Bundle bundle = getIntent().getExtras();
            String text = bundle.getString("Data");



            String[] Row2 = text.split("#");
            if (Row2.length > 0) {
                EditText eduser = (EditText) findViewById(R.id.txtuser);
                String LoginUser = Row2[1].toString();
                eduser.setText(LoginUser);
            }
            BindMRNote();

            BindPremiseType();
            ConsNo = (EditText) findViewById(R.id.txtConsumerNo);
            String[] Row = text.split(":");
            if (Row.length > 0)

            {
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
                    Sql = "Select Ablbelnr,ConsumerNo,MeterNo,Name,Co,HouseNo,Street,City,Portion from MRO_Detail_Supervisor  where ConsumerNo='" + Fileter + "'";
                else
                    Sql = "Select Ablbelnr,ConsumerNo,MeterNo,Name,Co,HouseNo,Street,City,Portion from MRO_Detail_Supervisor  where MeterNo='" + Fileter + "'";

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

                }
                else
                {
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
            }



        }
        catch ( Exception ex)
        {

        }

    }

    private void appendLog(String text) {
        StringBuilder sb = new StringBuilder();
        sb.append(Environment.getExternalStorageDirectory());
        sb.append("/OPDLlog.txt");
        File logFile = new File(sb.toString());
        if (!logFile.exists()) {
            try {
                logFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        try {
            BufferedWriter buf = new BufferedWriter(new FileWriter(logFile, true));
            buf.append(text);
            buf.newLine();
            buf.close();
        } catch (IOException e2) {
            Toast.makeText(getApplicationContext(), e2.getMessage().toString(),Toast.LENGTH_LONG).show();
            e2.printStackTrace();
        }
    }


    public void BindMRNote()
    {
        try {
            Spinner ddlMRNote = (Spinner) findViewById(R.id.ddlMRNote);
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            String result = db.onCreate();
            Cursor c = db.GetData("Select NoteId||\" : \"|| Desc Note  from Note_type  ");
            List<String> list = new ArrayList<String>();
            if (c.moveToFirst()) {
                list.add("16 : ACTUAL METER READING");
                //list.add("--Select MR Code--");
       /*         do {
                    list.add(c.getString(c.getColumnIndex("Note")));

                } while (c.moveToNext());//Move the cursor to the next row.
*/

                ArrayAdapter<String> dataAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, list);
                dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                ddlMRNote.setAdapter(dataAdapter);

            }
/*
            OnItemSelectedListener countrySelectedListener = new OnItemSelectedListener() {

                @Override
                public void onItemSelected(AdapterView<?> spinner, View container,
                                           int position, long id) {
                    Toast.makeText(getApplicationContext(),position, Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onNothingSelected(AdapterView<?> arg0) {
                    // TODO Auto-generated method stub
                }
            };
            ddlMRNote.setSelection(0);
            ddlMRNote.setOnItemSelectedListener(countrySelectedListener);
*/
        }
        catch ( Exception ex)
        {
            String Msg= ex.getMessage().toString();

        }
    }


    public void BindPremiseType()
    {
        try
        {
            List<String> list = new ArrayList<String>();
            list.add("--Select Premise Type--");
            list.add("Domestic");
            list.add("Commercial");
            list.add("Industrial");
            list.add("Mixed");
            Spinner ddlPremiseSurvey = (Spinner) findViewById(R.id.ddlPremiseSurvey);
            ArrayAdapter<String> dataAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, list);
            dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            ddlPremiseSurvey.setAdapter(dataAdapter);

        }
        catch (Exception e)
        {



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

    public void onShowButtonClicked(View v)
    {
        try
        {
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            String Sql;
            RadioButton rbConsNo = (RadioButton) findViewById(R.id.rbConsNo);
            RadioButton rbMeterNo = (RadioButton) findViewById(R.id.rbMeterNo);
            EditText ConsNo = (EditText) findViewById(R.id.txtConsumerNo);
            String Fileter = ConsNo.getText().toString();
            if (rbConsNo.isChecked())
                Sql = "Select ablbelnr,ConsumerNo,MeterNo,Name,Co,HouseNo,Street,City,Portion from MRO_Detail_Supervisor  where snotetype is null  and ConsumerNo='" + Fileter + "'";
            else
                Sql = "Select ablbelnr,ConsumerNo,MeterNo,Name,Co,HouseNo,Street,City,Portion from MRO_Detail_Supervisor  where snotetype is null  and MeterNo='" + Fileter + "'";


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

            }
            else
            {
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
        }
        catch (Exception ex) {

        }
    }

    public  void onShowPhotoClicked (View v)
    {
        try {
            appendLog("Starrrrrrrrring");
            int MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 123;
            Uri mCapturedImageURI;
            int REQUEST_IMAGE_CAPTURE = 2;
            int REQUEST_EXTERNAL_STORAGE = 1;
            Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            int permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);

            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                String Permission= "Fail";
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_EXTERNAL_STORAGE);
            }
            else
            {

                //ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_EXTERNAL_STORAGE);
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_EXTERNAL_STORAGE);

            }
            permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
            ActivityCompat.requestPermissions(this,new String[] { Manifest.permission.READ_EXTERNAL_STORAGE }, MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE);
            permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
            ///New Code
            String partFilename = currentDateFormat();
            TextView lbConsNo = (TextView) findViewById(R.id.lbConsumerNo);
            String Fileter = lbConsNo.getText().toString();
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(new Date());
            String fileName =    Fileter +"_"+ timeStamp+".jpg";
            File file = new File(Environment.getExternalStorageDirectory(), fileName);
            mCapturedImageURI = Uri.fromFile(file);

            TextView ImageName = (TextView) findViewById(R.id.hidden_Image);
            ImageName.setText(fileName);
            appendLog("Starrrrrrrrring111");

            //intent.putExtra(MediaStore.EXTRA_OUTPUT, mCapturedImageURI);
            //Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            appendLog("Starrrrrrrrring222222");
         /* if (intent.resolveActivity(getPackageManager()) != null) {

                //ContentValues values = new ContentValues(1);
                //values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpg");
                //mCapturedImageURI = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                appendLog("Starrrrrrrrring33333");
                intent.putExtra(MediaStore.EXTRA_OUTPUT, mCapturedImageURI);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                startActivityForResult(intent, requestCode);
            }   */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mCapturedImageURI = FileProvider.getUriForFile(getApplicationContext(), "com.npcl.com.vcpopdl.EzetapFileProvider", file);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                mCapturedImageURI = Uri.fromFile(file);
            }

            intent.putExtra(MediaStore.EXTRA_OUTPUT, mCapturedImageURI);
            startActivityForResult(intent, requestCode);

            appendLog("Starrrrrrrrring4444");
            //------
            imageHolder = (ImageView)findViewById(R.id.imageView1);
            // Intent photoCaptureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            // startActivityForResult(photoCaptureIntent.putExtra(MediaStore.EXTRA_OUTPUT,true ) , requestCode);



        }
        catch (Exception ex)
        {
            String Result = ex.getMessage().toString();
            appendLog("errrrrrr"+Result);
        }
    }

    //new cam
   /*public void onShowPhotoClicked1   (View v) {
      try {
          CallCamera =1;
          int MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 123;
          Uri mCapturedImageURI;
          int REQUEST_IMAGE_CAPTURE = 2;
          int REQUEST_EXTERNAL_STORAGE = 1;
          Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
          int permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);

          if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
              String Permission= "Fail";
              ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_EXTERNAL_STORAGE);
          }
          else
          {

              //ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_EXTERNAL_STORAGE);
              ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_EXTERNAL_STORAGE);

          }
          permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
          ActivityCompat.requestPermissions(this,new String[] { Manifest.permission.READ_EXTERNAL_STORAGE }, MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE);
          permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
          ///New Code
          String partFilename = currentDateFormat();
         // TextView  lbConsNo = (TextView) findViewById(R.id.lbAccountNo);
          String Fileter = "2000012345";

          String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.getDefault()).format(new Date());
          String fileName =    Fileter +"_"+ timeStamp+".jpg";
          appendLog("Sal" +fileName);

          File file = new File(Environment.getExternalStorageDirectory(), fileName);
          // mCapturedImageURI = Uri.fromFile(file);



          TextView ImageName = (TextView) findViewById(R.id.hidden_Image);
          ImageName.setText(fileName);


          Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
              mCapturedImageURI = FileProvider.getUriForFile(getApplicationContext(), "com.npcl.com.npclvcpapp.EzetapFileProvider", file);
              intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
          } else {
              mCapturedImageURI = Uri.fromFile(file);
          }

          intent.putExtra(MediaStore.EXTRA_OUTPUT, mCapturedImageURI);
          startActivityForResult(intent, requestCode);

          //------
          imageHolder = (ImageView)findViewById(R.id.imageView1);
          // Intent photoCaptureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
          // startActivityForResult(photoCaptureIntent.putExtra(MediaStore.EXTRA_OUTPUT,true ) , requestCode);


      }
      catch (Exception ex)
      {
          String Result = ex.getMessage().toString();

      }
  }

*/

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        try {
            super.onActivityResult(requestCode, resultCode, data);
            if (this.requestCode == requestCode && resultCode == RESULT_OK) {
                // Bitmap bitmap = (Bitmap) data.getExtras().get("data");
                // String partFilename = currentDateFormat();


                TextView ImageName = (TextView) findViewById(R.id.hidden_Image);
                String fileName = ImageName.getText().toString();

                //// My New Code
                File sd = Environment.getExternalStorageDirectory();
                File image = new File(Environment.getExternalStorageDirectory(), fileName);
                BitmapFactory.Options bmOptions = new BitmapFactory.Options();
                Bitmap bitmap = BitmapFactory.decodeFile(image.getAbsolutePath(),bmOptions);

                if ( storeCameraPhotoInSDCard(bitmap, fileName) == true) {
                    Bitmap mBitmap = getImageFileFromSDCard(fileName);
                    imageHolder.setImageBitmap(mBitmap);
                }
                else
                {


                }

                // display the image from SD Card to ImageView Control

                //String storeFilename = "photo_" + partFilename + ".jpg";
                Bitmap mBitmap = getImageFileFromSDCard(fileName);
                imageHolder.setImageBitmap(mBitmap);
            }
            else
            {

                TextView ImageName = (TextView) findViewById(R.id.hidden_Image);
                ImageName.setText("");
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
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fileOutputStream);

            fileOutputStream.flush();
            fileOutputStream.close();

            ///New Code Check Photo Save Or Not
            File imageFile = new File(Environment.getExternalStorageDirectory() + "/.vcpsysdata/" + currentDate);
            if(imageFile.exists())
            {
                // DeletePhoto(currentDate);
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
    private Bitmap getImageFileFromSDCard(String filename) {
        Bitmap bitmap = null;

        File[] files = Environment.getExternalStorageDirectory().listFiles();
        File imageFile = new File(Environment.getExternalStorageDirectory() +"/"+ filename);
        try {
            FileInputStream fis = new FileInputStream(imageFile);
            bitmap = BitmapFactory.decodeStream(fis);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        TextView ImageName = (TextView) findViewById(R.id.hidden_Image);
        ImageName.setText(filename);
        return bitmap;
    }


    public boolean ValidateData()
    {

        TextView lbConsumerNo;
        EditText txtKWH;
        EditText txtKVA;
        Spinner ddlMRNote;
        TextView ImgName;
        EditText txtNewMeterNo;

        try {
            lbConsumerNo = (TextView) findViewById(R.id.lbConsumerNo);
            txtKWH = (EditText) findViewById(R.id.txtKWH);
            txtKVA = (EditText) findViewById(R.id.txtKVA);
            txtNewMeterNo= (EditText) findViewById(R.id.txtNewMeterNo);
            ImgName = (TextView) findViewById(R.id.hidden_Image);
            ddlMRNote = (Spinner) findViewById(R.id.ddlMRNote);

            String KWHVal= txtKWH.getText().toString();
            String KvAVal= txtKVA.getText().toString();
            String MRCode= String.valueOf(ddlMRNote.getSelectedItem());
            String ImageName= ImgName.getText().toString();
            String ConsumerNoL = lbConsumerNo.getText().toString();
            String NewMeterNo = txtNewMeterNo.getText().toString();

            if (ConsumerNoL.equals("") )
            {
                Toast.makeText(getApplicationContext(),"Please Click On Show Button..! ", Toast.LENGTH_SHORT).show();
                return false;
            }

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


                Toast.makeText(getApplicationContext(), "Please Select MR Code..! ", Toast.LENGTH_SHORT).show();
                return false;

            }

            Spinner MRNote;
            MRNote = (Spinner) findViewById(R.id.ddlMRNote);
            MRCode= String.valueOf(MRNote.getSelectedItem());
            String[] temp = MRCode.split(":");
            MRCode= temp[0].toString().trim();
            if (MRCode.equals("60"))
            {
                if (NewMeterNo.length()> 0)
                {

                }
                else {
                    Toast.makeText(getApplicationContext(), "Please Enter New Meter No ..! ", Toast.LENGTH_SHORT).show();
                    return false;
                }

            }
            if( ImageName.equals("hidden value") || ImageName.equals("") )
            {
                Toast.makeText(getApplicationContext(),"Please Take Photo..! ", Toast.LENGTH_SHORT).show();
                return false;

            }

            return true;
        }
        catch ( Exception ex)
        {
            String Error= ex.getMessage().toString();
            return false;
        }

    }


    public boolean SaveData(String consumerno, String GpsCoordinate,String sreg1,String sreg2,String sreg3,String sreg4 , String sphotoid,String snotetype,String premisesType, String nooffloor, String noofroom ,String OpenHr,String Area ,String NoofAC , String NewMeterNo, String Userid)
    {
        boolean Result =false;
        try
        {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HH_mm_ss");
            String  currentTimeStamp = dateFormat.format(new Date());
            String  smrdatetime = dateFormat.format(new Date()).replace("_","");

            String noofshop="0";

            String Sql = "update MRO_Detail_Supervisor  set FileName ='~',  smrdatetime ='" + smrdatetime + "' , sreg1 ='" + sreg1 + "', sreg2='" + sreg2 + "', sreg3='" + sreg3 + "', sreg4 ='" + sreg4 + "'," +
                    " sphotoid ='" + sphotoid + "', snotetype ='" + snotetype + "' ,premisesType ='"+ premisesType +"' , nooffloor='" + nooffloor + "',noofshop ='" + noofshop + "', noofroom='" + noofroom + "'  ," +
                    " OpenHr ='" + OpenHr + "', Area ='" + Area + "', NoofAC ='" + NoofAC + "',  IsTransfer='N' ,SnewMeterNo ='"+ NewMeterNo +"' ,UserId ='"+ Userid +"' , DataMode='MANUAL' ,IsTransfer='N' where ConsumerNo='" + consumerno + "' ";
appendLog("Manual save " +Sql);
            CreateLog(consumerno +":"+ smrdatetime +":"+sreg1+":"+sreg2+":"+sreg3+":"+sreg4+ ":"+sphotoid +":"+ snotetype );
            DatabaseHandler Obj = new DatabaseHandler(getApplicationContext());

            Obj.ExecuteQry(Sql) ;

            Result = true;
            return Result;
        }
        catch (Exception ex)
        {
            appendLog("Manual save " +ex.getMessage().toString());
            return Result;

        }
    }

    public void onbtnSaveClicked(View v)
    {
        try
        {
            if (ValidateData() == true)
            {
                DatabaseHandler db = new DatabaseHandler(getApplicationContext());
                String result= db.onCreate();
                EditText KWH;
                EditText KvA;
                TextView ImgName;
                Spinner mrnote;
                Spinner PremiseSurvey;
                TextView Cons;
                EditText NewMeterNo;

                EditText NewMeterNotxt = (EditText)findViewById(R.id.txtNewMeterNo);
                EditText eduser = (EditText)findViewById(R.id.txtuser);
                EditText  NoFloors = (EditText)findViewById(R.id.txtNoFloors);
                EditText  Area = (EditText)findViewById(R.id.txtArea);
                EditText  NoAC = (EditText)findViewById(R.id.txtNoAC);
                EditText  NoofRooms = (EditText)findViewById(R.id.txtNoofRooms);
                EditText  OperatingHRs = (EditText)findViewById(R.id.txtOperatingHRs);

                KWH = (EditText) findViewById(R.id.txtKWH);
                KvA = (EditText) findViewById(R.id.txtKVA);
                ImgName = (TextView) findViewById(R.id.hidden_Image);
                mrnote = (Spinner) findViewById(R.id.ddlMRNote);
                Cons = (TextView) findViewById(R.id.lbConsumerNo) ;
                PremiseSurvey = (Spinner) findViewById(R.id.ddlPremiseSurvey);

                String NoFloorsVal=  NoFloors.getText().toString();
                String AreaVal = Area.getText().toString();
                String NoACVal = NoAC.getText().toString();
                String NoofRoomsVal = NoofRooms.getText().toString();
                String OperatingHRsVal = OperatingHRs.getText().toString();
                String KWHVal= KWH.getText().toString();
                String KvAVal= KvA.getText().toString();
                String NewMeterNoVal= NewMeterNotxt.getText().toString();
                String MRCode= String.valueOf(mrnote.getSelectedItem());
                String PremiseSurveyVal= String.valueOf(PremiseSurvey.getSelectedItem());



                String ImageName= ImgName.getText().toString();
                String ConsumerNo= Cons.getText().toString();
                String User=eduser.getText().toString().replace("Welcome:::","")  ;
                String[] temp = MRCode.split(":");
                MRCode= temp[0].toString();
                String GpsCoordinate =GetLocation();
                if (GpsCoordinate.equals( "") )
                {
                    GpsCoordinate="Na~Na";
                }
                if ( SaveData(ConsumerNo,GpsCoordinate,KWHVal,"0","0",KvAVal,ImageName,MRCode,PremiseSurveyVal,NoFloorsVal,NoofRoomsVal,OperatingHRsVal, AreaVal , NoACVal,NewMeterNoVal,User) == true)
                {
                    Toast.makeText(getApplicationContext(),"Record Save Successfully..! ", Toast.LENGTH_LONG).show();
                    ClearData();
                }
                else
                {
                    Toast.makeText(getApplicationContext(),"Error While Record Saving..! ", Toast.LENGTH_LONG).show();
                }


            }
        }
        catch (Exception ex)
        {
            Toast.makeText(getApplicationContext(),"Error While Record Saving..! ", Toast.LENGTH_LONG).show();
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
        EditText KVA;
        Spinner MRnote;
        Spinner PremiseSurvey;
        ImageView ImgVw;
        EditText NoFloors;
        EditText Area;
        EditText NoAC;
        EditText NoofRooms;
        EditText OperatingHRs;
        try
        {








            ConsNo = (EditText) findViewById(R.id.txtConsumerNo);
            lbConsno = (TextView) findViewById(R.id.lbtConsumerNo);
            lbMeterno = (TextView) findViewById(R.id.lbtMeterNo);
            lbName = (TextView) findViewById(R.id.lbtName);
            lbAddress = (TextView) findViewById(R.id.lbtAddress);
            lbStreet = (TextView) findViewById(R.id.lbtStreet);
            KWH     = (EditText) findViewById(R.id.txtKWH);
            KVA     = (EditText) findViewById(R.id.txtKVA);
            MRnote =(Spinner) findViewById(R.id.ddlMRNote);
            PremiseSurvey =(Spinner) findViewById(R.id.ddlPremiseSurvey);
            ImgVw = (ImageView) findViewById(R.id.imageView1);
            NoFloors  = (EditText) findViewById(R.id.txtNoFloors);
            Area  = (EditText) findViewById(R.id.txtArea);
            NoAC  = (EditText) findViewById(R.id.txtNoAC);
            NoofRooms  = (EditText) findViewById(R.id.txtNoofRooms);
            OperatingHRs  = (EditText) findViewById(R.id.txtOperatingHRs);
            ConsNo.setText("");
            lbConsno.setText("");
            lbMeterno.setText("");
            lbName.setText("");
            lbAddress.setText("");
            lbStreet.setText("");
            KWH.setText("");
            KVA.setText("");
            MRnote.setSelection(0);
            PremiseSurvey.setSelection(0);
            ImgVw.setImageDrawable(null);

            NoFloors.setText("");
            Area.setText("");
            NoAC.setText("");
            NoofRooms.setText("");
            OperatingHRs.setText("");


        }
        catch (Exception ex)
        {

        }
    }

    public void onItemSelected(View v)
    {
        Toast.makeText(getApplicationContext(),"Please Click On Show Button..! ", Toast.LENGTH_SHORT).show();
        /*Spinner MRNote;
        MRNote = (Spinner) findViewById(R.id.ddlMRNote);
        String MRCode= String.valueOf(MRNote.getSelectedItem());
        String[] temp = MRCode.split(":");
        MRCode= temp[0].toString();
*/

    }
}
