package com.npcl.com.vcpopdl;

import android.content.Intent;
import android.database.Cursor;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;
import android.widget.EditText;

import java.util.concurrent.TimeUnit ;
import java.util.*;
import java.lang.*;
import java.io.IOException;

import android.os.Environment;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import java.util.List;
import android.widget.TextView;

//import tw.com.prolific.app.pl2303terminal.R;
//import tw.com.prolific.app.pl2303terminal.R;
import tw.com.prolific.driver.pl2303.PL2303Driver;
import tw.com.prolific.driver.pl2303.PL2303Driver.FlowControl;

import android.app.PendingIntent;
import android.content.Context;
import android.text.format.Time;

import java.io.File;
import java.io.BufferedWriter;
import	java.io.FileWriter;

public class Home extends AppCompatActivity {
    EditText ed1;
    EditText ed2;
    private PL2303Driver.BaudRate mBaudrate = PL2303Driver.BaudRate.B300;
    private PL2303Driver.DataBits mDataBits = PL2303Driver.DataBits.D7;
    private PL2303Driver.Parity mParity = PL2303Driver.Parity.EVEN;
    private PL2303Driver.StopBits mStopBits = PL2303Driver.StopBits.S1;
    private PL2303Driver.FlowControl mFlowControl = FlowControl.OFF;
    private static final String ACTION_USB_PERMISSION = "com.npcl.com.npclvcpapp.USB_PERMISSION";
    private UsbManager mUsbManager;
    private boolean nNewAmmendment;
    private  String DataToBeRead = "Billing";




    PL2303Driver mSerial;


//// For DLMS Code





/////End
/////Variable for DLMS
private StringBuilder strbldDLMdata =new StringBuilder();
    private byte[] nPkt = new byte[1024];
    private byte[] nRcvPkt = new byte[1024];
    private byte bytAddMode;
    private byte nRecv;
    private byte nRecvLast;
    private byte nRecvCntr;
    private byte nSent;
    private byte nSentLast;
    private byte nSentCntr;


    private int nCounter;
    private byte nRetLSH;
    private byte[] buffer = new byte[1024];
    private int[] fcstab = new int[256];
    private byte bytTimOut = (byte) 15;
    private byte bytTryCnt = (byte) 5;
    private int bytWait = 30;
    private byte[] Ps = new byte[16];
    private byte[] keyBytes = new byte[16];
    private int pktLength;
   // public static final byte MaxValue = 255;
    //public static final byte MinValue = 0;
    /////End
    @Override
    protected void onCreate(Bundle savedInstanceState) {


        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        Bundle bundle = getIntent().getExtras();

        String UserData= bundle.getString("user");
        String[] Temp = UserData.split(":");

        String text= Temp[0].toString();
       // Toast.makeText(getApplicationContext(),text,Toast.LENGTH_SHORT).show();
        ed1 = (EditText) findViewById(R.id.txtuser);
        ed2 = (EditText) findViewById(R.id.txtRole);
        ed1.setText("Welcome:::" + text);
        ed2.setText("Role:::" + Temp[1].toString());


appendLog("Load Here");

        loadDriver();





    }


    public void loadDriver()
    {

        StringBuilder sb = new StringBuilder();
        try {

            // appendLog("*****I Am In****");
            PendingIntent mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
            //  mSerial = new PL2303Driver((UsbManager) getSystemService(Context.USB_SERVICE), this, ACTION_USB_PERMISSION);
            UsbManager manager = (UsbManager) getApplicationContext().getSystemService(Context.USB_SERVICE);
            //  appendLog("*****I Am Here....1****");
            List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
            UsbSerialDriver driver = availableDrivers.get(0);
            appendLog("Driver Name" +driver.toString());
            sb.append("\n");
            //   appendLog("*****I Am Here....2****");
            UsbDevice devices = driver.getDevice();
            manager.requestPermission(devices, mPermissionIntent);
            sb.append("Device Name--"+ devices.getDeviceName() );
            sb.append("\n");


            UsbDeviceConnection connection = manager.openDevice(driver.getDevice());

            if (connection == null) {
                sb.append("manager.openDevice(driver.getDevice() " );
                sb.append("\n");
                manager.requestPermission(devices,
                        PendingIntent.getBroadcast(getApplicationContext(), 0, new Intent(Home.ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE));
            }
            else {
                sb.append("Geting connection" + connection.getSerial());
                sb.append("\n");
            }

            //  appendLog("*****I Am Here****");

            sb.append("opening port" );
            sb.append("\n");
            UsbSerialPort port = driver.getPorts().get(0);
            sb.append("port Serrial " +port.getSerial());
            sb.append("\n");
            boolean parameter = true;
            sb.append("Port Connecting");
            sb.append("\n");
            port.open(connection);



        }
        catch (Exception ex)
        {
            String x= "Error:--" + ex.getMessage().toString();
            // appendLog(x);


        }
    }
    public static String hexToString(String hex) {
        StringBuilder sb = new StringBuilder();
        char[] hexData = hex.toCharArray();
        for (int count = 0; count < hexData.length - 1; count += 2) {
            int firstDigit = Character.digit(hexData[count], 16);
            int lastDigit = Character.digit(hexData[count + 1], 16);
            int decimal = firstDigit * 16 + lastDigit;
            sb.append((char)decimal);
        }
        return sb.toString();
    }
    public void onbtnsearch(View v) {
        StringBuilder sb = new StringBuilder();
        TextView tv = (TextView) findViewById(R.id.textView1);

        try {
            int num3 = 100;
            UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
            List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
            UsbSerialDriver driver = availableDrivers.get(0);
            Log.d("USB", "Has permission: " +
                    manager.hasPermission(driver.getDevice()));
            UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
            UsbSerialPort port = driver.getPorts().get(0);
            port.open(connection);

            port.setParameters(9600, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            Fcs_Tab();
            if (port != null)
            {
                appendLog("Port is available ");

            }
            sb.append("Set Port Setting");
            sb.append("\n");
            String s = Base64.getEncoder().encodeToString(nPkt);
            appendLog("Pkt Before: " +s);
            this.AddressInit();
             s = Base64.getEncoder().encodeToString(nPkt);
            appendLog("Pkt After: " +s);
            sb.append("\n");
            int Xms= nPkt[0] & 0xFF;
            appendLog("First Address Byte Val " + Xms);
            sb.append("Address Init & Sending NRM");
            sb.append("\n");
            boolean parameter = this.SetNRM(port,this.bytWait, (byte) 3, this.bytTimOut);



            if (parameter == true) {
                appendLog("SetNRM is ok");

            }
            else {

                Toast.makeText(getApplicationContext(), "Meter Unable to Communicate, Please Check Cable or take Manual Reading" , 0).show();
                return;
            }

            appendLog("Sending AARQ");
            num3 = this.AARQ(port,(byte)1, "1111111111111111", this.bytWait, (byte)3, this.bytTimOut);

            if (num3 == 1)
            {
                appendLog("Association Fail.");
                parameter = false;
            }
            else if (num3 == 2)
            {
               appendLog("Authentication Fail.");
                parameter = false;
            }
            else if (num3 == 0)
            {
                appendLog("Authentication Done");
                parameter = true;
            }


            StringBuilder strbldDLMdata = new StringBuilder();
            StringBuilder SbData =this.GetParameter(port,(byte)1, "0000600100FF", (byte)2, this.bytWait, this.bytTryCnt, this.bytTimOut, false, this.strbldDLMdata);
            if (SbData.toString() !="" )
            {
                String d = SbData.toString().replace("0A0A", "");
                //str = HexStringToString(d);
                String str=hexToString(d);
                str = str.replace("\b", "");
                sb.append("Meter No :---" +str);
                // str = !(((object) strbldDLMdata).ToString().Substring(0, 2) == "06") ? this.ASCIItoSerial(((object) strbldDLMdata).ToString().Substring(4)) : Convert.ToInt32(((object) strbldDLMdata).ToString().Substring(2), 16).ToString();
            }

            tv.setText(sb.toString());

            StringBuilder MeterData = new StringBuilder();
            StringBuilder sbNm = new StringBuilder();
            sbNm = ReadNamePlate(port);
            if (sbNm.toString() != "") {
                //  wait();
                //Status.setText("Read Name Plate Successfully...!");
//                publishProgress("Read Name Plate Successfully...!");
            } else {
                // wait();
                //Status.setText("unable to Read Name Plate Successfully...!");
//                publishProgress("unable to Read Name Plate Successfully...!");
            }

            MeterData.append(sbNm);

            appendLog("*****************NamePlate *******************************");
            appendLog(MeterData.toString());
            appendLog("------------------Instant-------------------------------------");

//            publishProgress("Downloading Instantaneous Data. Please wait...");
            MeterData.append(ReadInstantData(port));
            appendLog(MeterData.toString());
            //Status.setText("Read Instantaneous data Successfully...!");
//            publishProgress("Read Instantaneous data Successfully...!");
            String FileName = "";
            String DataFileName = "";

            if (DataToBeRead == "Billing") {
                FileName = "B";
                appendLog("Read Billing");
//                publishProgress("Downloading Billing Data. Please wait...");
                MeterData.append(ReadBillingData(port));

                //  wait();
                // Status.setText("Read Billing data Successfully...!");
//                publishProgress("Read Billing data Successfully...!");

            }

            if (DataToBeRead == "Event") {
                FileName = "E";
//                publishProgress("Downloading Event Data. Please wait...");
                MeterData.append(ReadEventData(port));

                //wait();
                //  Status.setText("Read Event data Successfully...!");
//                publishProgress("Read Event data Successfully...!");
            }
            if (DataToBeRead == "Load Profile") {
                FileName = "L";
//                publishProgress("Downloading Load Profile Data. Please wait...");
                MeterData.append(ReadLoadSurveyData(port));
                // Status.setText("Read Load Profile data Successfully...!");
            }//
            if (DataToBeRead.equals("Billing+Event")) {
                FileName = "BE";
//                publishProgress("Downloading Billing Data. Please wait...");
                MeterData.append(ReadBillingData(port));
                // wait();
                // Status.setText("Read Billing data Successfully...!");
//                publishProgress("Read Billing data Successfully...!");
//                publishProgress("Downloading Event Data. Please wait...!");
                MeterData.append(ReadEventData(port));
                // Status.setText("Read Event data Successfully...!");
            }
            if (DataToBeRead.equals("Billing+Event+LoadProfile5D")) {
                FileName = "BE";
//                publishProgress("Downloading Billing+Event+LoadProfile5D Data. Please wait...");
                MeterData.append(ReadBillingData(port));
                // wait();
                // Status.setText("Read Billing data Successfully...!");

//                publishProgress("Downloading Event Data. Please wait...!");
                MeterData.append(ReadEventData(port));
                // Status.setText("Read Event data Successfully...!");

//                publishProgress("Downloading Load Profile Data. Please wait...!");
                MeterData.append(ReadLoadSurveyDataDays(port,5));
//                publishProgress("Read Billing+Event+LoadProfile5D data Successfully...!");
            }

            if (DataToBeRead == "All") {
                FileName = "A";
//                publishProgress("Downloading Billing Data. Please wait...!");
                MeterData.append(ReadBillingData(port));
                // Status.setText("Read Billing data Successfully...!");
//                publishProgress("Downloading Load Profile Data. Please wait...!");
                MeterData.append(ReadLoadSurveyData(port));
                //  Status.setText("Read Load Prrofile data Successfully...!");
//                publishProgress("Downloading Event Data. Please wait...!");
                MeterData.append(ReadEventData(port));
                // Status.setText("Read Event data Successfully...!");
            }

        } catch (Exception ex)
        {
            appendLog("Error:-" +ex.getMessage());
            sb.append("\n");

        }
    }

    private StringBuilder ReadLoadSurveyData(UsbSerialPort port)
    {
        StringBuilder strbldDLMdata = new StringBuilder();
        StringBuilder DLMdata = new StringBuilder();
        boolean flag = false;

        DLMdata= this.ReadScalarUnit("BLOCKLOAD", port);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        DLMdata=this.GetParameter(port,(byte) 7, "0100630100FF", (byte) 7, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);

        DLMdata=this.GetParameter(port,(byte) 7, "0100630100FF", (byte) 8, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);

        DLMdata=this.GetParameter(port,(byte) 7, "0100630100FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);

        flag = false;

        DLMdata=this.GetParameter(port,(byte) 7, "0100630100FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);

        return strbldDLMdata;
    }

    private StringBuilder ReadLoadSurveyDataDays(UsbSerialPort port , int lsDays)
    {
        StringBuilder strbldDLMdata = new StringBuilder();
        StringBuilder DLMdata = new StringBuilder();
        boolean flag = false;

        DLMdata= this.ReadScalarUnit("BLOCKLOAD", port);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        //  appendLog("BLOCKLOAD" +DLMdata);
        DLMdata=this.GetParameter(port,(byte) 7, "0100630100FF", (byte) 7, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        //  appendLog("BLOCKLOAD 1" +DLMdata);
        DLMdata=this.GetParameter(port,(byte) 7, "0100630100FF", (byte) 8, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        // appendLog("BLOCKLOAD 2" +DLMdata);
        DLMdata=this.GetParameter(port,(byte) 7, "0100630100FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        // appendLog("BLOCKLOAD 3" +DLMdata);
        flag = false;

        if (1==1) {
            DLMdata = this.GetParameter(port, (byte) 7, "0100630100FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (DLMdata.toString() != "")
                strbldDLMdata.append("Load Survey Data");
            strbldDLMdata.append(DLMdata);
        }
        else
        {

            Date dateStartDate = Calendar.getInstance().getTime();

            for(int i=lsDays ; i >=0; i--)
            {
                dateStartDate = Until.AddDate(dateStartDate,-i);

                //   appendLog("Pass Date" +dateStartDate );
                this.nRecvCntr=1;
                this.nSentCntr=0;
                DLMdata = GetParameterSelective(port, (byte) 7, "0100630100FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true, dateStartDate, dateStartDate, 30);
                if (DLMdata.toString() != "")
                    strbldDLMdata.append(DLMdata);
                //   appendLog("BLOCKLOAD " +i  +DLMdata);
            }


        }




        return strbldDLMdata;
    }

    public String Hex2Digit(byte a)
    {
        String Result =  Integer.toHexString(0xff & a);
        if ( Result.length()==1)
            Result="0"+ Result;
        return Result;
    }
    private StringBuilder  GetParameterSelective(UsbSerialPort port, byte nClassID, String sOBISCode, byte nAttribID, int nWait, byte nTryCount, byte nTimeOut, boolean isDLM, Date  dateStartDate, Date  dateEndDate, int intProfilePd)
    {
        StringBuilder SbData = new StringBuilder();


        //appendLog("New Fun -1 " + dateStartDate);
        boolean flag1 = false;
        long num1 = 0L;
        /*for (int ma = 0; ma < 100 ; ma++) {
            appendLog( "Paket init -"+this.nPkt[ma] +" ~" + (this.nPkt[ma] & 0xff ) );
        }*/

        byte num2 = (byte) ((int) this.bytAddMode + 8);
        strbldDLMdata = new StringBuilder();
        this.nPkt[2] = (byte)((int) this.bytAddMode + 76);
        //  appendLog( "nRecvCntr -"  +nRecvCntr);
        this.nRetLSH = (byte)((int) this.nRecvCntr << 5);
        // appendLog( "nRecvCntr -"  +nRecvCntr);

        this.nPkt[(int) (byte)((int) this.bytAddMode + 5)] = (byte)((int) this.nRetLSH | 16);
        // appendLog( "BytaddMode -"  +this.bytAddMode +"~"+ this.nRetLSH);
        //  appendLog( "nSentCntr"  +nSentCntr);
        this.nRetLSH = (byte)((int) this.nSentCntr << 1);
        this.nPkt[(int) (byte)((int) this.bytAddMode + 5)] = (byte)((int) this.nRetLSH | (int) this.nPkt[(int) (byte)((int) this.bytAddMode + 5)]);

        //   for (int ma = 0; ma < 100 ; ma++) {
        //   appendLog( "Paket init Before OBIS -"+this.nPkt[ma] +" ~" + (this.nPkt[ma] & 0xff ) );
        //   }

        byte[] numArray1 = this.nPkt;
        int index1 = (int) num2;
        int num3 = 1;
        byte num4 = (byte) (index1 + num3);
        int num5 = 230;
        numArray1[index1] = (byte) num5;
        byte[] numArray2 = this.nPkt;
        int index2 = (int) num4;
        int num6 = 1;
        byte num7 = (byte) (index2 + num6);
        int num8 = 230;
        numArray2[index2] = (byte) num8;
        byte[] numArray3 = this.nPkt;
        int index3 = (int) num7;
        int num9 = 1;
        byte num10 = (byte) (index3 + num9);
        int num11 = 0;
        numArray3[index3] = (byte) num11;
        byte[] numArray4 = this.nPkt;
        int index4 = (int) num10;
        int num12 = 1;
        byte num13 = (byte) (index4 + num12);
        int num14 = 192;
        numArray4[index4] = (byte) num14;
        byte[] numArray5 = this.nPkt;
        int index5 = (int) num13;
        //appendLog("New Fun -2 " + dateStartDate);
        int num15 = 1;
        byte num16 = (byte) (index5 + num15);
        int num17 = 1;
        numArray5[index5] = (byte) num17;
        byte[] numArray6 = this.nPkt;
        int index6 = (int) num16;
        int num18 = 1;
        byte num19 = (byte) (index6 + num18);
        int num20 = 129;
        numArray6[index6] = (byte) num20;
        byte[] numArray7 = this.nPkt;
        int index7 = (int) num19;
        int num21 = 1;
        byte num22 = (byte) (index7 + num21);
        int num23 = 0;
        numArray7[index7] = (byte) num23;
        byte[] numArray8 = this.nPkt;
        int index8 = (int) num22;
        int num24 = 1;
        byte num25 = (byte) (index8 + num24);
        int num26 = (int) nClassID;
        numArray8[index8] = (byte) num26;
        // appendLog( "Before Paket Length " +this.nPkt.length);

        byte[] tempbyte1 = hexStringToByteArray((sOBISCode.substring(0, 12)));
        for (int index9 = 0; index9 < 6; ++index9) {
            //     appendLog("Command IN Loop : " +sOBISCode);
            this.nPkt[(int) num25++] = tempbyte1[index9];
            // appendLog("Command IN Loop : " + num25 + "::" + (int) tempbyte1[index9]);
        }

        //this.nPkt[6]=84;
        // this.nPkt[7]=87;
        // appendLog( "Paket Length " +this.nPkt.length);

        //  for (int ma = 0; ma < 100 ; ma++) {
        //    appendLog( "Paket After OBIS -"+this.nPkt[ma] +" ~" + (this.nPkt[ma] & 0xff ) );
        //  }

        byte[] numArray9 = this.nPkt;
        int index10 = (int) num25;
        int num27 = 1;
        byte num28 = (byte) (index10 + num27);
        int num29 = (int) nAttribID;
        numArray9[index10] = (byte) num29;
        byte[] numArray10 = this.nPkt;
        int index11 = (int) num28;
        int num30 = 1;
        byte num31 = (byte) (index11 + num30);
        int num32 = 1;
        numArray10[index11] = (byte) num32;
        byte[] numArray11 = this.nPkt;
        int index12 = (int) num31;
        int num33 = 1;
        byte num34 = (byte) (index12 + num33);
        int num35 = 1;
        numArray11[index12] = (byte) num35;
        byte[] numArray12 = this.nPkt;
        int index13 = (int) num34;
        int num36 = 1;
        byte num37 = (byte) (index13 + num36);
        int num38 = 2;
        numArray12[index13] = (byte) num38;
        byte[] numArray13 = this.nPkt;
        int index14 = (int) num37;
        int num39 = 1;
        byte num40 = (byte) (index14 + num39);
        int num41 = 4;
        numArray13[index14] = (byte) num41;
        byte[] numArray14 = this.nPkt;
        int index15 = (int) num40;
        int num42 = 1;
        byte num43 = (byte) (index15 + num42);
        int num44 = 2;
        numArray14[index15] = (byte) num44;
        byte[] numArray15 = this.nPkt;
        int index16 = (int) num43;
        int num45 = 1;
        byte num46 = (byte) (index16 + num45);
        int num47 = 4;
        numArray15[index16] = (byte) num47;
        byte[] numArray16 = this.nPkt;
        int index17 = (int) num46;
        int num48 = 1;
        byte num49 = (byte) (index17 + num48);
        int num50 = 18;
        numArray16[index17] = (byte) num50;
        byte[] numArray17 = this.nPkt;
        int index18 = (int) num49;
        int num51 = 1;
        byte num52 = (byte) (index18 + num51);
        int num53 = 0;
        numArray17[index18] = (byte) num53;
        byte[] numArray18 = this.nPkt;
        int index19 = (int) num52;
        int num54 = 1;
        byte num55 = (byte) (index19 + num54);
        int num56 = 8;
        numArray18[index19] = (byte) num56;
        byte[] numArray19 = this.nPkt;
        int index20 = (int) num55;
        int num57 = 1;
        byte num58 = (byte) (index20 + num57);
        int num59 = 9;
        numArray19[index20] = (byte) num59;
        byte[] numArray20 = this.nPkt;
        int index21 = (int) num58;
        int num60 = 1;
        byte num61 = (byte) (index21 + num60);
        int num62 = 6;
        numArray20[index21] = (byte) num62;
        byte[] numArray21 = this.nPkt;
        int index22 = (int) num61;
        int num63 = 1;
        byte num64 = (byte) (index22 + num63);
        int num65 = 0;
        numArray21[index22] = (byte) num65;
        byte[] numArray22 = this.nPkt;
        int index23 = (int) num64;
        int num66 = 1;
        byte num67 = (byte) (index23 + num66);
        int num68 = 0;
        numArray22[index23] = (byte) num68;
        byte[] numArray23 = this.nPkt;
        int index24 = (int) num67;
        int num69 = 1;
        byte num70 = (byte) (index24 + num69);
        int num71 = 1;
        numArray23[index24] = (byte) num71;
        byte[] numArray24 = this.nPkt;
        int index25 = (int) num70;
        int num72 = 1;
        byte num73 = (byte) (index25 + num72);
        int num74 = 0;
        numArray24[index25] = (byte) num74;
        byte[] numArray25 = this.nPkt;
        int index26 = (int) num73;
        int num75 = 1;
        byte num76 = (byte) (index26 + num75);
        int num77 = 0;
        numArray25[index26] = (byte) num77;
        byte[] numArray26 = this.nPkt;
        int index27 = (int) num76;
        int num78 = 1;
        byte num79 = (byte) (index27 + num78);
        int num80 = (int) 255;
        numArray26[index27] = (byte) num80;
        byte[] numArray27 = this.nPkt;
        int index28 = (int) num79;
        int num81 = 1;
        byte num82 = (byte) (index28 + num81);
        int num83 = 15;
        numArray27[index28] = (byte) num83;
        byte[] numArray28 = this.nPkt;
        int index29 = (int) num82;
        int num84 = 1;
        byte num85 = (byte) (index29 + num84);
        int num86 = 2;
        numArray28[index29] = (byte) num86;
        byte[] numArray29 = this.nPkt;
        int index30 = (int) num85;
        int num87 = 1;
        byte num88 = (byte) (index30 + num87);
        int num89 = 18;
        numArray29[index30] = (byte) num89;
        byte[] numArray30 = this.nPkt;
        int index31 = (int) num88;
        int num90 = 1;
        byte num91 = (byte) (index31 + num90);
        int num92 = 0;
        numArray30[index31] = (byte) num92;
        byte[] numArray31 = this.nPkt;
        int index32 = (int) num91;
        int num93 = 1;
        byte num94 = (byte) (index32 + num93);
        int num95 = 0;
        numArray31[index32] = (byte) num95;
        byte[] numArray32 = this.nPkt;
        int index33 = (int) num94;
        int num96 = 1;
        byte num97 = (byte) (index33 + num96);
        int num98 = 9;
        numArray32[index33] = (byte) num98;
        byte[] numArray33 = this.nPkt;
        int index34 = (int) num97;
        int num99 = 1;
        byte num100 = (byte) (index34 + num99);
        int num101 = 12;
        numArray33[index34] = (byte) num101;
        byte[] numArray34 = this.nPkt;
        int index35 = (int) num100;
        int num102 = 1;
        byte num103 = (byte) (index35 + num102);


        int num104 = (int) (byte)(  Until.getYear(dateStartDate) / 256);
        // appendLog("Year" +Until.getYear(dateStartDate) +"~" + num104);
        numArray34[index35] = (byte) num104;
        byte[] numArray35 = this.nPkt;
        int index36 = (int) num103;
        int num105 = 1;
        byte num106 = (byte) (index36 + num105);
        int num107 = (int) (0xff & (byte) (Until.getYear(dateStartDate) % 256));
        // appendLog("Num107" +num107);
        numArray35[index36] = (byte) num107;
        byte[] numArray36 = this.nPkt;
        int index37 = (int) num106;
        int num108 = 1;
        byte num109 = (byte) (index37 + num108);
        int num110 = (int) (byte) Until.getMonth (dateStartDate);
        // appendLog("num110" +num110);
        numArray36[index37] = (byte) num110;
        byte[] numArray37 = this.nPkt;
        int index38 = (int) num109;
        int num111 = 1;
        byte num112 = (byte) (index38 + num111);
        int num113 = (int) (byte)  Until.getDate(dateStartDate);
        //  appendLog("num113" +num113);
        numArray37[index38] = (byte) num113;
        byte[] numArray38 = this.nPkt;
        int index39 = (int) num112;
        int num114 = 1;
        byte num115 = (byte) (index39 + num114);
        int num116 = (int) 255;
        numArray38[index39] = (byte) num116;
        byte[] numArray39 = this.nPkt;
        int index40 = (int) num115;
        int num117 = 1;
        byte num118 = (byte) (index40 + num117);
        int num119 = 0;
        numArray39[index40] = (byte) num119;
        byte num120;
        if (!this.nNewAmmendment)
        {
            byte[] numArray40 = this.nPkt;
            int index9 = (int) num118;
            int num121 = 1;
            num120 = (byte) (index9 + num121);
            int num122 = 0;
            numArray40[index9] = (byte) num122;
        }
        else
        {
            byte[] numArray40 = this.nPkt;
            int index9 = (int) num118;
            int num121 = 1;
            num120 = (byte) (index9 + num121);
            int num122 = (int) (byte)(intProfilePd);
            numArray40[index9] = (byte) num122;
        }
        byte[] numArray41 = this.nPkt;
        int index41 = (int) num120;
        int num123 = 1;
        byte num124 = (byte) (index41 + num123);
        int num125 = 0;
        numArray41[index41] = (byte) num125;
        byte[] numArray42 = this.nPkt;
        int index42 = (int) num124;
        int num126 = 1;
        byte num127 = (byte) (index42 + num126);
        int num128 = 0;
        numArray42[index42] = (byte) num128;
        byte[] numArray43 = this.nPkt;
        int index43 = (int) num127;
        int num129 = 1;
        byte num130 = (byte) (index43 + num129);
        int num131 = 128;
        numArray43[index43] = (byte) num131;
        byte[] numArray44 = this.nPkt;
        int index44 = (int) num130;
        int num132 = 1;
        byte num133 = (byte) (index44 + num132);
        int num134 = 0;
        numArray44[index44] = (byte) num134;
        byte[] numArray45 = this.nPkt;
        int index45 = (int) num133;
        int num135 = 1;
        byte num136 = (byte) (index45 + num135);
        int num137 = 0;
        numArray45[index45] = (byte) num137;
        byte[] numArray46 = this.nPkt;
        int index46 = (int) num136;
        int num138 = 1;
        byte num139 = (byte) (index46 + num138);
        int num140 = 9;
        numArray46[index46] = (byte) num140;
        byte[] numArray47 = this.nPkt;
        int index47 = (int) num139;
        int num141 = 1;
        byte num142 = (byte) (index47 + num141);
        int num143 = 12;
        numArray47[index47] = (byte) num143;
        byte num144;
        Date Sdate = null;
        try {
            Sdate = Until.Sysdate();
        }
        catch (Exception ex )
        {

        }
        //   appendLog( "Sdate" +Sdate );
        //  appendLog( "dateEndDate" +dateEndDate );
        if (Until.stringToDateOlnly(dateEndDate) == Until.stringToDateOlnly(Sdate))
        {
            byte[] numArray40 = this.nPkt;
            int index9 = (int) num142;
            int num121 = 1;
            byte num122 = (byte) (index9 + num121);
            int num145 = (int) (byte) (Until.getYear(dateEndDate) / 256);
            numArray40[index9] = (byte) num145;
            byte[] numArray48 = this.nPkt;
            int index48 = (int) num122;
            int num146 = 1;
            byte num147 = (byte) (index48 + num146);
            int num148 = (int) (0xff & (byte)( Until.getYear(dateEndDate) % 256));
            numArray48[index48] = (byte) num148;
            byte[] numArray49 = this.nPkt;
            int index49 = (int) num147;
            int num149 = 1;
            byte num150 = (byte) (index49 + num149);
            int num151 = (int)(byte)(Until.getMonth(dateEndDate));
            numArray49[index49] = (byte) num151;
            byte[] numArray50 = this.nPkt;
            int index50 = (int) num150;
            int num152 = 1;
            byte num153 = (byte) (index50 + num152);
            int num154 = (int) (byte)( Until.getDate(dateEndDate));
            numArray50[index50] = (byte) num154;
            byte[] numArray51 = this.nPkt;
            int index51 = (int) num153;
            int num155 = 1;
            byte num156 = (byte) (index51 + num155);
            int num157 = (int) 255;
            numArray51[index51] = (byte) num157;
            byte[] numArray52 = this.nPkt;
            int index52 = (int) num156;
            int num158 = 1;
            byte num159 = (byte) (index52 + num158);
            int num160 = (int) (byte)( Until.getHours(Sdate));
            numArray52[index52] = (byte) num160;
            byte[] numArray53 = this.nPkt;
            int index53 = (int) num159;
            int num161 = 1;
            num144 = (byte) (index53 + num161);
            int num162 = (int) (byte)((int)(Until.getMinutes(Sdate)) / intProfilePd * intProfilePd);
            numArray53[index53] = (byte) num162;
        }
        else
        {
            byte[] numArray40 = this.nPkt;
            int index9 = (int) num142;
            int num121 = 1;
            byte num122 = (byte) (index9 + num121);
            int num145 = (int) (byte)( Until.getYear(Until.AddDate(dateEndDate,1)) / 256);
            numArray40[index9] = (byte) num145;
            byte[] numArray48 = this.nPkt;
            int index48 = (int) num122;
            int num146 = 1;
            byte num147 = (byte) (index48 + num146);
            int num148 = (int) (0xff & (byte)( Until.getYear(Until.AddDate(dateEndDate,1)) % 256));
            numArray48[index48] = (byte) num148;
            byte[] numArray49 = this.nPkt;
            int index49 = (int) num147;
            int num149 = 1;
            byte num150 = (byte) (index49 + num149);
            int num151 = (int) (byte)( Until.getMonth(Until.AddDate(dateEndDate,1) ));
            numArray49[index49] = (byte) num151;
            byte[] numArray50 = this.nPkt;
            int index50 = (int) num150;
            int num152 = 1;
            byte num153 = (byte) (index50 + num152);
            int num154 = (int) (byte)(Until.getDate(Until.AddDate(dateEndDate,1)));
            numArray50[index50] = (byte) num154;
            byte[] numArray51 = this.nPkt;
            int index51 = (int) num153;
            int num155 = 1;
            byte num156 = (byte) (index51 + num155);
            int num157 = (int) 255;
            numArray51[index51] = (byte) num157;
            byte[] numArray52 = this.nPkt;
            int index52 = (int) num156;
            int num158 = 1;
            byte num159 = (byte) (index52 + num158);
            int num160 = 0;
            numArray52[index52] = (byte) num160;
            byte[] numArray53 = this.nPkt;
            int index53 = (int) num159;
            int num161 = 1;
            num144 = (byte) (index53 + num161);
            int num162 = 0;
            numArray53[index53] = (byte) num162;
        }
        byte[] numArray54 = this.nPkt;
        int index54 = (int) num144;
        int num163 = 1;
        byte num164 = (byte) (index54 + num163);
        int num165 = 0;
        numArray54[index54] = (byte) num165;
        byte[] numArray55 = this.nPkt;
        int index55 = (int) num164;
        int num166 = 1;
        byte num167 = (byte) (index55 + num166);
        int num168 = 0;
        numArray55[index55] = (byte) num168;
        byte[] numArray56 = this.nPkt;
        int index56 = (int) num167;
        int num169 = 1;
        byte num170 = (byte) (index56 + num169);
        int num171 = 128;
        numArray56[index56] = (byte) num171;
        byte[] numArray57 = this.nPkt;
        int index57 = (int) num170;
        int num172 = 1;
        byte num173 = (byte) (index57 + num172);
        int num174 = 0;
        numArray57[index57] = (byte) num174;
        byte[] numArray58 = this.nPkt;
        int index58 = (int) num173;
        int num175 = 1;
        byte num176 = (byte) (index58 + num175);
        int num177 = 0;
        numArray58[index58] = (byte) num177;
        byte[] numArray59 = this.nPkt;
        int index59 = (int) num176;
        int num178 = 1;
        byte num179 = (byte) (index59 + num178);
        int num180 = 1;
        numArray59[index59] = (byte) num180;
        byte[] numArray60 = this.nPkt;
        int index60 = (int) num179;
        int num181 = 1;
        byte num182 = (byte) (index60 + num181);
        int num183 = 0;
        numArray60[index60] = (byte) num183;
        this.fcs(this.nPkt, (int) (byte)((int) this.bytAddMode + 5), (byte) 1);
        this.fcs(this.nPkt, (int) (byte)((int) num182 - 1), (byte) 1);
        this.nPkt[(int) num182 + 2] = (byte) 126;

        if (isDLM) {
            if (Integer.toHexString(nClassID).length()==1  )
                strbldDLMdata.append("\r\n000" + Integer.toHexString(nClassID) + " " + sOBISCode + " 0" + nAttribID + " ");
            else
                strbldDLMdata.append("\r\n00" + Integer.toHexString(nClassID) + " " + sOBISCode + " 0" + nAttribID + " ");
        }

        byte num184 = (byte) 0;
        boolean flag2;
       /* this.nPkt[49] = 9;
        this.nPkt[63] = 9;
        this.nPkt[75]= 76;
        this.nPkt[76]= (byte)239;
*/
       /* for (int ma = 0; ma < (int) num182 + 3 ; ma++) {
            appendLog( "Paket sent -"+this.nPkt[ma] +" ~" + (this.nPkt[ma] & 0xff ) );
        }

*/


        do
        {

            this.ClearBuffer();
            flag2 = false;
            //   appendLog("Before Packet Sent...---1");

            byte sendCommand1[] = new byte[(int)num182 + 3];
            for (int ma = 0; ma < (int) num182 + 3; ma++) {
                sendCommand1[ma] = (byte) (this.nPkt[ma] & 0xff);
                //   appendLog( "Instant Send -"+this.nPkt[ma] +" ~" + (this.nPkt[ma] & 0xff ) );
            }

            this.SendPkt(port ,sendCommand1, ((int) num182 + 3));
            //   appendLog("Packet Sent...---1");
            Time now2 = new Time();
            now2.setToNow();
            Time t3 = new Time();
            long original = System.currentTimeMillis();
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                // this part is executed when an exception (in this example InterruptedException) occurs
            }
            do
            {
                //    appendLog("Data Rece ...---1");
                try {
                    Thread.sleep(80);
                } catch (InterruptedException e) {
                    // this part is executed when an exception (in this example InterruptedException) occurs
                }
                this.DataReceive(port);
                //  appendLog("Data Rece ...---2");
                String Hex1 = Integer.toHexString((0xff & this.nRcvPkt[1]) & 7);
                if (Hex1.length() == 1)
                    Hex1 = "0" + Hex1;

                //  appendLog("Hex 1" + Hex1);

                String Hex2 = Integer.toHexString((0xff & this.nRcvPkt[2]));
                if (Hex2.length() == 1)
                    Hex2 = "0" + Hex2;
                //  appendLog("Hex 2" + Hex2);
                String hex = (Hex1 + Hex2);

                int Len = Integer.parseInt(hex, 16);
                this.pktLength = Len;
                if (this.nCounter > 2 && this.pktLength + 2 <= this.nCounter && ((int) this.nRcvPkt[this.pktLength + 1] == 126 && this.fcs(this.nRcvPkt, this.pktLength, (byte) 0)))
                {
                    flag2 = true;
                    num184 = (byte) 0;
                    this.FrameType();
                    //     appendLog(" Loop Condition No 1");
                    break;


                }
                else if (((System.currentTimeMillis() - original))/1000 > (int) nTimeOut && (int) num184 < (int) nTryCount)
                {
                    //  appendLog(" Loop Condition No 2");
                    {
                        ++num184;
                        break;
                    }
                }
            }
            while ( (int) num184 != (int) nTryCount);

            // appendLog("Loop Inner Cunter");


        }
        while (!flag2 && (int) num184 != (int) nTryCount);

        // appendLog("Loop Outer Cunter" +flag2);

        if (!flag2)
            SbData.append("");
        if (flag2 && (int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 11)] == 196 && (int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 12)] == 2)
        {
            //num1 = long.Parse(this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 15)].ToString("X2") + this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 16)].ToString("X2") + this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 17)].ToString("X2") + this.nRcvPkt[(int) Convert.ToByte((int) this.bytAddMode + 18)].ToString("X2"), NumberStyles.HexNumber);
            num1 = this.nRcvPkt[(int) (byte) ((int) this.bytAddMode + 15)] + this.nRcvPkt[(int) (byte) ((int) this.bytAddMode + 16)] + this.nRcvPkt[(int) (byte) ((int) this.bytAddMode + 17)] + this.nRcvPkt[(int) (byte) ((int) this.bytAddMode + 18)];

            flag1 = !IntToBool(this.nRcvPkt[(int) (byte) ((int) this.bytAddMode + 14)]);

            // appendLog("After Rec packet" );

        }
        if ((int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 11)] == 196 && (int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 12)] == 2)
        {
            if ((int) this.nRcvPkt[(int) this.bytAddMode + 20] == 130)
            {
                for (int index9 = (int) (byte)((int) this.bytAddMode + 23); index9 < this.pktLength - 1; ++index9)
                    strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));
            }
            else if ((int) this.nRcvPkt[(int) this.bytAddMode + 20] == 129)
            {
                for (int index9 = (int) (byte)((int) this.bytAddMode + 22); index9 < this.pktLength - 1; ++index9)
                    strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));
            }
            else
            {
                for (int index9 = (int) (byte)((int) this.bytAddMode + 21); index9 < this.pktLength - 1; ++index9)
                    strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));
            }

            //   appendLog("After Rec packet If" +strbldDLMdata.toString() );
        }
        else
        {
            for (int index9 = (int) (byte)((int) this.bytAddMode + 15); index9 < this.pktLength - 1; ++index9)
                strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));

            //    appendLog("After Rec packet Else" +strbldDLMdata.toString() + this.pktLength );
        }

        //  appendLog("While Call-" +(0xff & this.nRcvPkt[1]));
        while (((int) (0xff & this.nRcvPkt[1]) & 168) == 168)
        {
            //    appendLog("While Call Inner11");
            this.nPkt[2] = (byte)((int) this.bytAddMode + 7);
            this.nRetLSH = (byte)((int) this.nRecvCntr << 5);
            this.nPkt[(int) (byte)((int) this.bytAddMode + 5)] = (byte)((int) this.nRetLSH | 16);
            this.nPkt[(int) (byte)((int) this.bytAddMode + 5)] = (byte)((int) this.nPkt[(int) (byte)((int) this.bytAddMode + 5)] | 1);
            this.fcs( this.nPkt, (int) (byte)((int) this.bytAddMode + 5), (byte) 1);
            this.nPkt[(int) (byte)((int) this.bytAddMode + 8)] = (byte) 126;
            byte num121 = (byte) 0;
            boolean flag3;
            do
            {

                this.ClearBuffer();
                flag3 = false;

                this.SendPkt(port,this.nPkt,  (byte)((int) this.bytAddMode + 9));

                long original2 = System.currentTimeMillis();
                do
                {

                    this.DataReceive(port);
                    String Hex1 = Integer.toHexString((0xff & this.nRcvPkt[1]) & 7);
                    if (Hex1.length() == 1)
                        Hex1 = "0" + Hex1;

                    //   appendLog("Hex 1" + Hex1);

                    String  Hex2 = Integer.toHexString((0xff & this.nRcvPkt[2]));
                    if (Hex2.length() == 1)
                        Hex2 = "0" + Hex2;
                    //  appendLog("Hex 2" + Hex2);
                    String  hex = (Hex1 + Hex2);

                    int Len = Integer.parseInt(hex, 16);

                    this.pktLength = Len;
                    // appendLog("Packet len Here "+ Len);
                    if (this.nCounter > 2 && this.pktLength + 2 <= this.nCounter && ((int) this.nRcvPkt[this.pktLength + 1] == 126 && this.fcs( this.nRcvPkt, this.pktLength, (byte) 0)))
                    {
                        // appendLog("I Am here...!");
                        flag3 = true;
                        num121 = (byte) 0;
                        for (int index9 = (int) (byte)((int) this.bytAddMode + 8); index9 < this.pktLength - 1; ++index9)
                            strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));
                        this.FrameType();
                        break;
                    }
                    else if (((System.currentTimeMillis() - original2))/1000  > (int) nTimeOut && (int) num121 < (int) nTryCount)
                    {
                        // appendLog("I Am Here 2");
                        if ( this.nCounter > 0)
                        {
                            appendLog("I Am Here 2.1");
                            if ((int) this.nRcvPkt[0] != 126)
                                this.ClearBuffer();
                            flag3 = false;

                            this.SendPkt(port,this.nPkt, (byte)((int) this.bytAddMode + 9));

                            long original3 = System.currentTimeMillis();
                            do
                            {
                                this.DataReceive(port);
                                Hex1 = Integer.toHexString((0xff & this.nRcvPkt[1]) & 7);
                                if (Hex1.length() == 1)
                                    Hex1 = "0" + Hex1;

                                //  appendLog("Hex 1" + Hex1);

                                Hex2 = Integer.toHexString((0xff & this.nRcvPkt[2]));
                                if (Hex2.length() == 1)
                                    Hex2 = "0" + Hex2;
                                //  appendLog("Hex 2" + Hex2);
                                hex = (Hex1 + Hex2);

                                Len = Integer.parseInt(hex, 16);

                                this.pktLength = Len;

                                if (this.pktLength + 2 <= this.nCounter && (int) this.nRcvPkt[this.pktLength + 1] == 126 && this.fcs( this.nRcvPkt, this.pktLength, (byte) 0))
                                {
                                    flag3 = true;
                                    // this.ComPort.DiscardInBuffer();
                                    // this.ComPort.DiscardOutBuffer();
                                    num121 = (byte) 0;
                                    for (int index9 = (int) (byte)((int) this.bytAddMode + 8); index9 < this.pktLength - 1; ++index9)
                                        strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));
                                    this.FrameType();
                                    //goto label_94;
                                    if (flag3)
                                    {
                                        if ((int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 5)] == 151 || ((int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 5)] & 1) == 1)
                                            SbData.append("*****");
                                        else
                                            break;
                                    }

                                }
                            }
                            while (((System.currentTimeMillis() - original3))/1000 <= (int) nTimeOut);
                            ++num121;
                            break;
                        }
                        else
                        {
                            ++num121;
                            break;
                        }
                    }
                }
                while ((int) num121 != (int) nTryCount);


            }
            while ((int) num121 != (int) nTryCount);

            if (!flag3 || (int) this.nRcvPkt[1] != 160)
            {
                if (!flag3)
                    SbData.append("");
            }
            else
                break;
        }

        //  appendLog("While Call Innser " +flag1);
        while (flag1)
        {
            flag1 = false;
            this.nPkt[2] = (byte)((int) this.bytAddMode + 19);
            this.nRetLSH = (byte)((int) this.nRecvCntr << 5);
            this.nPkt[(int) (byte)((int) this.bytAddMode + 5)] = (byte)((int) this.nRetLSH | 16);
            this.nRetLSH = (byte)((int) this.nSentCntr << 1);
            this.nPkt[(int) (byte)((int) this.bytAddMode + 5)] = (byte)((int) this.nRetLSH | (int) this.nPkt[(int) (byte)((int) this.bytAddMode + 5)]);
            byte num121 = (byte)((int) this.bytAddMode + 8);
            byte[] numArray40 = this.nPkt;
            int index9 = (int) num121;
            int num122 = 1;
            byte num145 = (byte) (index9 + num122);
            int num146 = 230;
            numArray40[index9] = (byte) num146;
            byte[] numArray48 = this.nPkt;
            int index48 = (int) num145;
            int num147 = 1;
            byte num148 = (byte) (index48 + num147);
            int num149 = 230;
            numArray48[index48] = (byte) num149;
            byte[] numArray49 = this.nPkt;
            int index49 = (int) num148;
            int num150 = 1;
            byte num151 = (byte) (index49 + num150);
            int num152 = 0;
            numArray49[index49] = (byte) num152;
            byte[] numArray50 = this.nPkt;
            int index50 = (int) num151;
            int num153 = 1;
            byte num154 = (byte) (index50 + num153);
            int num155 = 192;
            numArray50[index50] = (byte) num155;
            byte[] numArray51 = this.nPkt;
            int index51 = (int) num154;
            int num156 = 1;
            byte num157 = (byte) (index51 + num156);
            int num158 = 2;
            numArray51[index51] = (byte) num158;
            byte[] numArray52 = this.nPkt;
            int index52 = (int) num157;
            int num159 = 1;
            byte num160 = (byte) (index52 + num159);
            int num161 = 129;
            numArray52[index52] = (byte) num161;
            byte[] numArray53 = this.nPkt;
            int index53 = (int) num160;
            int num162 = 1;
            byte num185 = (byte) (index53 + num162);
            int num186 = 0;
            numArray53[index53] = (byte) num186;
            byte[] numArray61 = this.nPkt;
            int index61 = (int) num185;
            int num187 = 1;
            byte num188 = (byte) (index61 + num187);
            int num189 = 0;
            numArray61[index61] = (byte) num189;
            byte[] numArray62 = this.nPkt;
            int index62 = (int) num188;
            int num190 = 1;
            byte num191 = (byte) (index62 + num190);
            int num192 = (int) (byte)(num1 / 256L);
            numArray62[index62] = (byte) num192;
            byte[] numArray63 = this.nPkt;
            int index63 = (int) num191;
            int num193 = 1;
            byte num194 = (byte) (index63 + num193);
            int num195 = (int) (byte)(num1 % 256L);
            numArray63[index63] = (byte) num195;
            this.fcs( this.nPkt, (int) (byte)((int) this.bytAddMode + 5), (byte) 1);
            this.fcs( this.nPkt, (int) (byte)((int) num194 - 1), (byte) 1);
            this.nPkt[(int) num194 + 2] = (byte) 126;
            byte num196 = (byte) 0;
            boolean flag3;
            do
            {
                this.ClearBuffer();
                flag3 = false;

                this.SendPkt(port,this.nPkt, (byte)((int) num194 + 3));

                long original4 = System.currentTimeMillis();
                do
                {

                    this.DataReceive(port);
                    String Hex1 = Integer.toHexString((0xff & this.nRcvPkt[1]) & 7);
                    if (Hex1.length() == 1)
                        Hex1 = "0" + Hex1;

                    //    appendLog("Hex 1" + Hex1);

                    String Hex2 = Integer.toHexString((0xff & this.nRcvPkt[2]));
                    if (Hex2.length() == 1)
                        Hex2 = "0" + Hex2;
                    //    appendLog("Hex 2" + Hex2);
                    String hex = (Hex1 + Hex2);

                    int Len = Integer.parseInt(hex, 16);

                    this.pktLength = Len;

                    if (this.nCounter > 2 && this.pktLength + 2 <= this.nCounter && ((int) this.nRcvPkt[this.pktLength + 1] == 126 && this.fcs(this.nRcvPkt, this.pktLength, (byte) 0)))
                    {
                        flag3 = true;
                        num196 = (byte) 0;
                        this.FrameType();
                        break;
                    }
                    else if (((System.currentTimeMillis() - original4))/1000 > (int) nTimeOut && (int) num196 < (int) nTryCount)
                    {
                        {
                            ++num196;
                            break;
                        }
                    }
                }
                while ((int) num196 != (int) nTryCount);
                // label_129:

            }
            while ((int) num196 != (int) nTryCount);
            if (flag3 && (int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 11)] == 196 && (int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 12)] == 2)
            {
                // num1 = long.Parse(this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 15)].ToString("X2") + this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 16)].ToString("X2") + this.nRcvPkt[(int) Convert.ToByte((int) this.bytAddMode + 17)].ToString("X2") + this.nRcvPkt[(int) Convert.ToByte((int) this.bytAddMode + 18)].ToString("X2"), NumberStyles.HexNumber);
                num1 = this.nRcvPkt[(int) (byte) ((int) this.bytAddMode + 15)] + this.nRcvPkt[(int) (byte) ((int) this.bytAddMode + 16)] + this.nRcvPkt[(int) (byte) ((int) this.bytAddMode + 17)] + this.nRcvPkt[(int) (byte) ((int) this.bytAddMode + 18)];
                flag1 = !IntToBool(this.nRcvPkt[(int) (byte) ((int) this.bytAddMode + 14)]);
            }
            if (!flag3)
                SbData.append("");;
            if ((int) this.nRcvPkt[(int) this.bytAddMode + 20] == 130)
            {
                for (int index64 = (int) (byte)((int) this.bytAddMode + 23); index64 < this.pktLength - 1; ++index64)
                    strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index64]));
            }
            else if ((int) this.nRcvPkt[(int) this.bytAddMode + 20] == 129)
            {
                for (int index64 = (int) (byte)((int) this.bytAddMode + 22); index64 < this.pktLength - 1; ++index64)
                    strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index64]));
            }
            else
            {
                for (int index64 = (int) (byte)((int) this.bytAddMode + 21); index64 < this.pktLength - 1; ++index64)
                    strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index64]));
            }
            while (((int) this.nRcvPkt[1] & 168) == 168)
            {
                this.nPkt[2] = (byte)((int) this.bytAddMode + 7);
                this.nRetLSH =(byte)((int) this.nRecvCntr << 5);
                this.nPkt[(int) (byte)((int) this.bytAddMode + 5)] = (byte)((int) this.nRetLSH | 16);
                this.nPkt[(int) (byte)((int) this.bytAddMode + 5)] = (byte)((int) this.nPkt[(int) (byte)((int) this.bytAddMode + 5)] | 1);
                this.fcs( this.nPkt, (int) (byte)((int) this.bytAddMode + 5), (byte) 1);
                this.nPkt[(int) (byte)((int) this.bytAddMode + 8)] = (byte) 126;
                byte num197 = (byte) 0;
                boolean flag4;
                do
                {
                    flag4 = false;

                    this.ClearBuffer();

                    this.SendPkt(port,this.nPkt, (byte)((int) this.bytAddMode + 9));

                    long original6 = System.currentTimeMillis();
                    do
                    {


                        this.DataReceive(port);

                        String Hex1 = Integer.toHexString((0xff & this.nRcvPkt[1]) & 7);
                        if (Hex1.length() == 1)
                            Hex1 = "0" + Hex1;

                        //     appendLog("Hex 1" + Hex1);

                        String Hex2 = Integer.toHexString((0xff & this.nRcvPkt[2]));
                        if (Hex2.length() == 1)
                            Hex2 = "0" + Hex2;
                        //  appendLog("Hex 2" + Hex2);
                        String hex = (Hex1 + Hex2);

                        int Len = Integer.parseInt(hex, 16);

                        this.pktLength = Len;

                        if (this.nCounter > 2 && this.pktLength + 2 <= this.nCounter && ((int) this.nRcvPkt[this.pktLength + 1] == 126 && this.fcs( this.nRcvPkt, this.pktLength, (byte) 0)))
                        {
                            flag4 = true;
                            num197 = (byte) 0;
                            for (int index64 = (int) (byte)((int) this.bytAddMode + 8); index64 < this.pktLength - 1; ++index64)
                                strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index64]));
                            this.FrameType();
                            break;
                        }
                        else if (((System.currentTimeMillis() - original6))/1000  > (int) nTimeOut && (int) num197 < (int) nTryCount)
                        {
                            {
                                ++num197;
                                break;
                            }
                        }
                    }
                    while ((int) num197 != (int) nTryCount);


                }
                while ((int) num197 != (int) nTryCount);
                if (!flag4)
                    SbData.append(strbldDLMdata.toString());
                return SbData;

            }
        }

        SbData.append(strbldDLMdata);
        return SbData;
    }

    private StringBuilder ReadBillingData(UsbSerialPort port)
    {
        boolean flag = false;
        StringBuilder strbldDLMdata = new StringBuilder();
        StringBuilder DLMdata = new StringBuilder();
        appendLog("In Billing");
        DLMdata=this.ReadScalarUnit("BILLTYPC", port);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);

        appendLog("Scalar Unit" +DLMdata);
        flag = false;
        DLMdata =this.GetParameter(port,(byte) 7, "0100620100FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        appendLog("Bill Block 1" +DLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        flag = false;
        DLMdata = this.GetParameter(port,(byte) 7, "0100620100FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")

            strbldDLMdata.append(DLMdata);

        appendLog("Bill Block 2" +DLMdata);
        flag = false;
        DLMdata =this.GetParameter(port,(byte) 7, "0100620100FF", (byte) 8, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        appendLog("Bill Block 3" +DLMdata);
        flag = false;
        DLMdata =this.GetParameter(port,(byte) 20, "00000D0000FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        appendLog("Bill Block 4" +DLMdata);
        flag = false;
        DLMdata =this.GetParameter(port,(byte) 20, "00000D0000FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        flag = false;
        DLMdata =this.GetParameter(port,(byte) 20, "00000D0000FF", (byte) 4, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        flag = false;
        DLMdata =this.GetParameter(port, (byte) 20, "00000D0000FF", (byte) 5, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        return strbldDLMdata;
    }

    private StringBuilder ReadEventData(UsbSerialPort port)
    {
        boolean flag = false;
        StringBuilder strbldDLMdata = new StringBuilder();
        StringBuilder DLMdata = new StringBuilder();

        DLMdata=this.ReadScalarUnit("EVENT", port);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        flag = false;
        DLMdata= this.GetParameter(port,(byte) 7, "0000636200FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 7, "0000636200FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 7, "0000636201FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 7, "0000636201FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 7, "0000636202FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 7, "0000636202FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 7, "0000636203FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 7, "0000636203FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 7, "0000636204FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 7, "0000636204FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 7, "0000636205FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 7, "0000636205FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        DLMdata=this.GetParameter(port,(byte) 7, "0000636281FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 7, "0000636281FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);

        return strbldDLMdata;
    }

    private StringBuilder ReadScalarUnit(String WhichData, UsbSerialPort port)
    {
        String str ="";
        StringBuilder strbldDLMdata = new StringBuilder();
        StringBuilder DLMdata = new StringBuilder();
        boolean flag = false;
        if (WhichData == "INSTANT")
        {

            flag = false;



            DLMdata =this.GetParameter(port,(byte) 7, "01005E5B03FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
            if (DLMdata.toString()!="")
                strbldDLMdata.append(DLMdata);

            flag = false;
            DLMdata=this.GetParameter(port,(byte) 7, "01005E5B03FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (DLMdata.toString()!="")
                strbldDLMdata.append(DLMdata);


        }
        else if (WhichData == "BILLTYPC")
        {
            for(int ma=0; ma< 10  ;ma++)
            {
                //sendCommand1[ma]=(byte) (this.nPkt[ma] & 0xff ) ;
                appendLog( "Before Billing -"+this.nPkt[ma] +" ~" + (this.nPkt[ma] & 0xff ) );
            }

            flag = false;
            DLMdata=this.GetParameter(port,(byte) 7, "01005E5B06FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
            if (DLMdata.toString()!="")
                strbldDLMdata.append(DLMdata);
            flag = false;
            DLMdata= this.GetParameter(port,(byte) 7, "01005E5B06FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
            if (DLMdata.toString()!="")
                strbldDLMdata.append(DLMdata);
        }
        else if (WhichData == "BLOCKLOAD")
        {
            flag = false;
            DLMdata=this.GetParameter(port,(byte) 7, "01005E5B04FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
            if (DLMdata.toString()!="")
                strbldDLMdata.append(DLMdata);
            flag = false;
            DLMdata=this.GetParameter(port,(byte) 7, "01005E5B04FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (DLMdata.toString()!="")
                strbldDLMdata.append(DLMdata);
        }
        else if (WhichData == "DAILYLOAD")
        {
            flag = false;
            DLMdata=this.GetParameter(port,(byte) 7, "01005E5B05FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (DLMdata.toString()!="")
                strbldDLMdata.append(DLMdata);
            flag = false;
            DLMdata=this.GetParameter(port,(byte) 7, "01005E5B05FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (DLMdata.toString()!="")
                strbldDLMdata.append(DLMdata);
        }
        else if (WhichData == "EVENT")
        {
            flag = false;
            DLMdata=this.GetParameter(port,(byte) 7, "01005E5B07FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
            if (DLMdata.toString()!="")
                strbldDLMdata.append(DLMdata);
            flag = false;
            DLMdata=this.GetParameter(port,(byte) 7, "01005E5B07FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (DLMdata.toString()!="")
                strbldDLMdata.append(DLMdata);
        }
        return strbldDLMdata;
    }

    private StringBuilder ReadInstantData(UsbSerialPort port)
    {
        boolean flag = false;
        StringBuilder strbldDLMdata = new StringBuilder();
        StringBuilder DLMdata = new StringBuilder();
        DLMdata=this.ReadScalarUnit("INSTANT", port);

        //   appendLog("Read Scalar"+DLMdata.toString() );

        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        //  appendLog("Inst 1");

        DLMdata= this.GetParameter(port,(byte) 7, "01005E5B00FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        //   appendLog("Inst 1"+DLMdata.toString() );
        //appendLog("&&&&&&&&&&&" );
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        flag = false;
        DLMdata= this.GetParameter(port,(byte) 7, "01005E5B00FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        //  appendLog("Inst 2"+DLMdata.toString() );
        // appendLog("&&&&&&&&&&&" );
        flag = false;
        DLMdata= this.GetParameter(port,(byte) 4, "0100011100FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        //   appendLog("Inst 3"+DLMdata.toString() );
        //  appendLog("&&&&&&&&&&&" );
        flag = false;
        DLMdata= this.GetParameter(port,(byte) 4, "0100011100FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        // appendLog("Inst 4"+DLMdata.toString() );
        // appendLog("&&&&&&&&&&&" );
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 4, "0100011100FF", (byte) 5, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        //  appendLog("Inst 5"+DLMdata.toString() );
        //   appendLog("&&&&&&&&&&&" );
        flag = false;
        DLMdata= this.GetParameter(port,(byte) 1, "01008C0700FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        //  appendLog("Inst 6"+DLMdata.toString() );
        //   appendLog("&&&&&&&&&&&" );
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 3, "0100800800FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        //  appendLog("Inst 6"+DLMdata.toString() );
        //  appendLog("&&&&&&&&&&&" );
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 3, "0100800800FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        //   appendLog("Inst 7"+DLMdata.toString() );
        //   appendLog("&&&&&&&&&&&" );
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 3, "0100960800FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        //  appendLog("Inst 8"+DLMdata.toString() );
        //   appendLog("&&&&&&&&&&&" );
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 3, "0100960800FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        //   appendLog("Inst 9"+DLMdata.toString() );
        //   appendLog("&&&&&&&&&&&" );
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 3, "0100010200FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        // appendLog("Inst 10"+DLMdata.toString() );
        //  appendLog("&&&&&&&&&&&" );
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 3, "0100010200FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        //  appendLog("Inst 11"+DLMdata.toString() );
        //  appendLog("&&&&&&&&&&&" );
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 3, "0100090200FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        //  appendLog("Inst 12"+DLMdata.toString() );
        //  appendLog("&&&&&&&&&&&" );
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 3, "0100090200FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        //  appendLog("Inst 13"+DLMdata.toString() );
        //  appendLog("&&&&&&&&&&&" );
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 3, "0000600800FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        //  appendLog("Inst 14"+DLMdata.toString() );
        //  appendLog("&&&&&&&&&&&" );
        flag = false;
        DLMdata=this.GetParameter(port,(byte) 3, "0000600800FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);
        //  appendLog("Inst 15"+DLMdata.toString() );
        //  appendLog("&&&&&&&&&&&" );
        return strbldDLMdata;
    }

    private StringBuilder  ReadNamePlate(UsbSerialPort port)
    {
        boolean flag = false;
        StringBuilder strbldDLMdata = new StringBuilder();
        StringBuilder DLMdata = new StringBuilder();
        String str1 = "";
        //CultureInfo invariantCulture = CultureInfo.InvariantCulture;
        this.nNewAmmendment = false;



        DLMdata = this.GetParameter(port,(byte) 7, "00005E5B0AFF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (DLMdata != null && DLMdata.length() > 25)
        {
            this.nNewAmmendment = true;
            strbldDLMdata.append(DLMdata);
            flag = false;
            appendLog("I Am Here");
            DLMdata=this.GetParameter(port,(byte) 7, "00005E5B0AFF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
            if (DLMdata.toString()!="")
                strbldDLMdata.append(DLMdata);
        }
        else
        {




        }

        DLMdata=this.GetParameter(port,(byte) 1, "00002A0000FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (DLMdata.toString()!="") {
            strbldDLMdata.append(DLMdata);
            //    this.chrMeterType = Convert.ToChar(byte.Parse(((object) strbldDLMdata).ToString().Substring(strbldDLMdata.Length - 2, 2), NumberStyles.HexNumber));

        }

        flag = false;
        DLMdata=this.GetParameter(port,(byte) 1, "0100000804FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (DLMdata.toString()!="") {
            strbldDLMdata.append(DLMdata);
            //   this.intProfilePd = int.Parse(((object) strbldDLMdata).ToString().Substring(25), NumberStyles.HexNumber) / 60;
        }

        flag = false;
        DLMdata=this.GetParameter(port,(byte) 8, "0000010000FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (DLMdata.toString()!="") {
            strbldDLMdata.append(DLMdata);
            //String str2 =  DLMdata.toString().substring(DLMdata.length() - 24,24);
            //      this.dateGlobalCurrentDate = DateTime.ParseExact(int.Parse(str2.Substring(6, 2), NumberStyles.HexNumber).ToString("00") + "/" + int.Parse(str2.Substring(4, 2), NumberStyles.HexNumber).ToString("00") + "/" + int.Parse(str2.Substring(0, 4), NumberStyles.HexNumber).ToString("0000") + " " + int.Parse(str2.Substring(10, 2), NumberStyles.HexNumber).ToString("00") + ":" + int.Parse(str2.Substring(12, 2), NumberStyles.HexNumber).ToString("00") + ":" + int.Parse(str2.Substring(14, 2), NumberStyles.HexNumber).ToString("00"), "dd/MM/yyyy HH:mm:ss", (IFormatProvider) invariantCulture, DateTimeStyles.AssumeLocal);
        }

        flag = false;
        DLMdata=this.GetParameter(port,(byte) 1, "0000600100FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);


        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);

        flag = false;
        DLMdata=this.GetParameter(port,(byte) 1, "0100000200FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);


        flag = false;
        DLMdata=this.GetParameter(port,(byte) 1, "00005E5B09FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);


        flag = false;
        DLMdata=this.GetParameter(port,(byte) 7, "0100630100FF", (byte) 4, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);


        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);

        flag = false;
        DLMdata=this.GetParameter(port,(byte) 1, "0000000100FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);

        flag = false;
        DLMdata=this.GetParameter(port,(byte) 1, "0100000800FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);

        flag = false;
        DLMdata=this.GetParameter(port,(byte) 1, "0100608012FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (DLMdata.toString()!="") {
            strbldDLMdata.append("\n");
            strbldDLMdata.append("0001 0100608012FF 02 0B");
            strbldDLMdata.append("\n");
        }
        else
            strbldDLMdata.append(DLMdata);


        flag = false;
        DLMdata=this.GetParameter(port,(byte) 63, "0000600A01FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (DLMdata.toString()!="") {
            strbldDLMdata.append("003F 0000600A01FF 02 0B");
            strbldDLMdata.append("\n");
        }
        else
            strbldDLMdata.append(DLMdata);


        flag = false;
        DLMdata=this.GetParameter(port,(byte) 1, "0100608017FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (DLMdata.toString()!="") {
            strbldDLMdata.append("0001 0100608012FF 02 0B");
            strbldDLMdata.append("\n");
        }
        else
            strbldDLMdata.append(DLMdata);

        return strbldDLMdata;
    }

    public static byte[] intToByteArray(int a)
    {
        byte[] ret = new byte[4];
        ret[3] = (byte) (a & 0xFF);
        ret[2] = (byte) ((a >> 8) & 0xFF);
        ret[1] = (byte) ((a >> 16) & 0xFF);
        ret[0] = (byte) ((a >> 24) & 0xFF);
        return ret;
    }
    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len/2];

        for(int i = 0; i < len; i+=2){
            data[i/2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i+1), 16));
        }

        return data;
    }

    private StringBuilder GetParameter(UsbSerialPort port,byte nClassID, String sOBISCode, byte nAttribID, int nWait, byte nTryCount, byte nTimeOut, boolean isDLM,  StringBuilder strbldDLMdata)
    {

       appendLog("In Get Paraameter");
        StringBuilder SbData = new StringBuilder();
        boolean flag1 = false;
        long num1 = 0L;
        byte num2 = (byte)((int) this.bytAddMode + 8);
        strbldDLMdata = new StringBuilder();
        this.nPkt[2] = (byte)((int) this.bytAddMode + 25);
        this.nRetLSH = (byte)((int) this.nRecvCntr << 5);
        this.nPkt[(int) (byte)((int) this.bytAddMode + 5)] = (byte)((int) this.nRetLSH | 16);
        this.nRetLSH = (byte)((int) this.nSentCntr << 1);
        this.nPkt[(int) (byte)((int) this.bytAddMode + 5)] = (byte)((int) this.nRetLSH | (int) this.nPkt[(int) (byte)((int) this.bytAddMode + 5)]);

        appendLog("In Get Paraameter 1.1");
        byte[] numArray1 = this.nPkt;
        int index1 = (int) num2;
        int num3 = 1;
        byte num4 = (byte) (index1 + num3);
        int num5 = 230;
        numArray1[index1] = (byte) num5;
        byte[] numArray2 = this.nPkt;
        int index2 = (int) num4;
        int num6 = 1;
        byte num7 = (byte) (index2 + num6);
        int num8 = 230;
        numArray2[index2] = (byte) num8;
        byte[] numArray3 = this.nPkt;
        int index3 = (int) num7;
        int num9 = 1;
        appendLog("In Get Paraameter 1.2");
        byte num10 = (byte) (index3 + num9);
        int num11 = 0;
        numArray3[index3] = (byte) num11;
        byte[] numArray4 = this.nPkt;
        int index4 = (int) num10;
        int num12 = 1;
        byte num13 = (byte) (index4 + num12);
        int num14 = 192;
        numArray4[index4] = (byte) num14;
        byte[] numArray5 = this.nPkt;
        int index5 = (int) num13;
        int num15 = 1;
        byte num16 = (byte) (index5 + num15);
        int num17 = 1;
        appendLog("In Get Paraameter 1.3");
        numArray5[index5] = (byte) num17;
        byte[] numArray6 = this.nPkt;
        int index6 = (int) num16;
        int num18 = 1;
        byte num19 = (byte) (index6 + num18);
        int num20 = 129;
        numArray6[index6] = (byte) num20;
        byte[] numArray7 = this.nPkt;
        int index7 = (int) num19;
        int num21 = 1;
        byte num22 = (byte) (index7 + num21);
        int num23 = 0;
        numArray7[index7] = (byte) num23;
        byte[] numArray8 = this.nPkt;
        int index8 = (int) num22;
        int num24 = 1;

        byte num25 = (byte) (index8 + num24);
        int num26 = (int) nClassID;
        numArray8[index8] = (byte) num26;

        byte[] tempbyte1 =hexStringToByteArray((sOBISCode.substring(0, 12)));
        for (int index9 = 0; index9 < 6; ++index9) {
            this.nPkt[(int) num25++] = tempbyte1[index9];
        }


        byte[] numArray9 = this.nPkt;
        int index10 = (int) num25;
        int num27 = 1;
        byte num28 = (byte) (index10 + num27);
        int num29 = (int) nAttribID;
        numArray9[index10] = (byte) num29;
        byte[] numArray10 = this.nPkt;
        int index11 = (int) num28;
        int num30 = 1;
        byte num31 = (byte) (index11 + num30);
        int num32 = 0;
        numArray10[index11] = (byte) num32;
        this.fcs(this.nPkt, (int) (byte)((int) this.bytAddMode + 5), (byte) 1);
        this.fcs(this.nPkt, (int) (byte)((int) num31 - 1), (byte) 1);
        this.nPkt[(int) num31 + 2] = (byte) 126;
        if (isDLM)
            strbldDLMdata.append ("\r\n00" + nClassID + " " + sOBISCode + " " + nAttribID + " ");
        byte num33 = (byte) 0;
        boolean flag2;
        do
        {
            appendLog("1 loop");
            this.ClearBuffer();
            flag2 = false;

            byte sendCommand1[] = new byte[num31+3];
            for(int ma=0; ma< num31 + 3 ;ma++)
            {
                sendCommand1[ma]=this.nPkt[ma];
                //appendLog( "S--"+  (int)this.nPkt[ma] );
            }
            this.SendPkt(port ,sendCommand1,  num31 + 3);
            long original = System.currentTimeMillis();
            do
            {
                appendLog("1.1 loop");
                  this.DataReceive(port);

                this.pktLength = ((int) this.nRcvPkt[1] & 7) + this.nRcvPkt[2];
                if (this.nCounter > 2 && this.pktLength + 2 <= this.nCounter && ((int) this.nRcvPkt[this.pktLength + 1] == 126 && this.fcs(this.nRcvPkt, this.pktLength, (byte) 0)))
                {
                    flag2 = true;
                    num33 = (byte) 0;
                    this.FrameType();
                    break;
                }
          else if ((System.currentTimeMillis() - original)/1000 > (int) nTimeOut && (int) num33 < (int) nTryCount)
            {


                {
                    ++num33;
                    break;
                }
            }
            }
            while ((int) num33 != (int) nTryCount);

        }
        while (!flag2 && (int) num33 != (int) nTryCount);

        appendLog("after 1 loop");
        if (!flag2 || (int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 5)] == 151 || ((int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 5)] & 1) == 1)
            SbData.append("");
            //return false;
        if (flag2 && (int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 11)] == 196 && (int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 12)] == 2)
        {
            num1 = this.nRcvPkt[(int) this.bytAddMode + 15] + this.nRcvPkt[(int) this.bytAddMode + 16] + this.nRcvPkt[(int) this.bytAddMode + 17]+ this.nRcvPkt[(int)  this.bytAddMode + 18];
            flag1 = !IntToBool(this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 14)]);


        }
        if ((int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 11)] == 196 && (int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 12)] == 2)
        {
            if ((int) this.nRcvPkt[(int) this.bytAddMode + 20] == 130)
            {
                for (int index9 = (int) (byte)((int) this.bytAddMode + 23); index9 < this.pktLength - 1; ++index9)
                    strbldDLMdata.append(Integer.toHexString(this.nRcvPkt[index9]));


            }
            else if ((int) this.nRcvPkt[(int) this.bytAddMode + 20] == 129)
            {
                for (int index9 = (int) (byte)((int) this.bytAddMode + 22); index9 < this.pktLength - 1; ++index9)
                    strbldDLMdata.append(Integer.toHexString(this.nRcvPkt[index9]));
            }
            else
            {
                for (int index9 = (int) (byte)((int) this.bytAddMode + 21); index9 < this.pktLength - 1; ++index9)
                    strbldDLMdata.append(Integer.toHexString(this.nRcvPkt[index9]));
            }
        }
        else
        {
            for (int index9 = (int) (byte)((int) this.bytAddMode + 15); index9 < this.pktLength - 1; ++index9)
                strbldDLMdata.append(Integer.toHexString(this.nRcvPkt[index9]));
        }
        while (((int) this.nRcvPkt[1] & 168) == 168)
        {
            this.nPkt[2] = (byte)((int) this.bytAddMode + 7);
            this.nRetLSH = (byte)((int) this.nRecvCntr << 5);
            this.nPkt[(int) (byte)((int) this.bytAddMode + 5)] = (byte)((int) this.nRetLSH | 16);
            this.nPkt[(int) (byte)((int) this.bytAddMode + 5)] = (byte)((int) this.nPkt[(int) (byte)((int) this.bytAddMode + 5)] | 1);
            this.fcs( this.nPkt, (int) (byte)((int) this.bytAddMode + 5), (byte) 1);
            this.nPkt[(int) (byte)((int) this.bytAddMode + 8)] = (byte) 126;
            byte num34 = (byte) 0;
            boolean flag3;
            do
            {
                appendLog("2 loop");
                this.ClearBuffer();
                flag3 = false;

                byte sendCommand1[] = new byte[this.bytAddMode + 9];
                for(int ma=0; ma< this.bytAddMode + 9 ;ma++)
                {
                    sendCommand1[ma]=this.nPkt[ma];
                    //appendLog( "S--"+  (int)this.nPkt[ma] );
                }
                this.SendPkt(port ,sendCommand1,  this.bytAddMode + 9);

                    //this.SendPkt(port,this.nPkt, (byte)((int) this.bytAddMode + 9));

                long original = System.currentTimeMillis();
                do
                {
                    appendLog("2.1 loop");
                        this.DataReceive(port);

                    this.pktLength = ((int) this.nRcvPkt[1] & 7) + this.nRcvPkt[2];
                    if (this.nCounter > 2 && this.pktLength + 2 <= this.nCounter && ((int) this.nRcvPkt[this.pktLength + 1] == 126 && this.fcs( this.nRcvPkt, this.pktLength, (byte) 0)))
                    {
                        flag3 = true;
                        num34 = (byte) 0;
                        for (int index9 = (int) (byte)((int) this.bytAddMode + 8); index9 < this.pktLength - 1; ++index9)
                            strbldDLMdata.append(Integer.toHexString(this.nRcvPkt[index9]));
                        this.FrameType();
                        break;
                    }
            else if ((System.currentTimeMillis() - original)/1000 > (int) nTimeOut && (int) num34 < (int) nTryCount)
                {
                    {
                        ++num34;
                        break;
                    }
                }
                }
                while ((int) num34 != (int) nTryCount);
                if (flag3)
                {
                    if ((int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 5)] == 151 || ((int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 5)] & 1) == 1)
                        //return false;
                        SbData.append("");
                    else
                        break;
                }
            }
            while ((int) num34 != (int) nTryCount);
            if (!flag3 || (int) this.nRcvPkt[1] != 160)
            {
                if (!flag3)
                   // return false;
                    SbData.append("");
            }
            else
                break;
        }
        while (flag1)
        {
            flag1 = false;
            this.nPkt[2] = (byte)((int) this.bytAddMode + 19);
            this.nRetLSH = (byte)((int) this.nRecvCntr << 5);
            this.nPkt[(int) (byte)((int) this.bytAddMode + 5)] = (byte)((int) this.nRetLSH | 16);
            this.nRetLSH = (byte)((int) this.nSentCntr << 1);
            this.nPkt[(int) (byte)((int) this.bytAddMode + 5)] = (byte)((int) this.nRetLSH | (int) this.nPkt[(int) (byte)((int) this.bytAddMode + 5)]);
            byte num34 = (byte)((int) this.bytAddMode + 8);
            byte[] numArray11 = this.nPkt;
            int index9 = (int) num34;
            int num35 = 1;
            byte num36 = (byte) (index9 + num35);
            int num37 = 230;
            numArray11[index9] = (byte) num37;
            byte[] numArray12 = this.nPkt;
            int index12 = (int) num36;
            int num38 = 1;
            byte num39 = (byte) (index12 + num38);
            int num40 = 230;
            numArray12[index12] = (byte) num40;
            byte[] numArray13 = this.nPkt;
            int index13 = (int) num39;
            int num41 = 1;
            byte num42 = (byte) (index13 + num41);
            int num43 = 0;
            numArray13[index13] = (byte) num43;
            byte[] numArray14 = this.nPkt;
            int index14 = (int) num42;
            int num44 = 1;
            byte num45 = (byte) (index14 + num44);
            int num46 = 192;
            numArray14[index14] = (byte) num46;
            byte[] numArray15 = this.nPkt;
            int index15 = (int) num45;
            int num47 = 1;
            byte num48 = (byte) (index15 + num47);
            int num49 = 2;
            numArray15[index15] = (byte) num49;
            byte[] numArray16 = this.nPkt;
            int index16 = (int) num48;
            int num50 = 1;
            byte num51 = (byte) (index16 + num50);
            int num52 = 129;
            numArray16[index16] = (byte) num52;
            byte[] numArray17 = this.nPkt;
            int index17 = (int) num51;
            int num53 = 1;
            byte num54 = (byte) (index17 + num53);
            int num55 = 0;
            numArray17[index17] = (byte) num55;
            byte[] numArray18 = this.nPkt;
            int index18 = (int) num54;
            int num56 = 1;
            byte num57 = (byte) (index18 + num56);
            int num58 = 0;
            numArray18[index18] = (byte) num58;
            byte[] numArray19 = this.nPkt;
            int index19 = (int) num57;
            int num59 = 1;
            byte num60 = (byte) (index19 + num59);
            int num61 = (int) (byte)(num1 / 256L);
            numArray19[index19] = (byte) num61;
            byte[] numArray20 = this.nPkt;
            int index20 = (int) num60;
            int num62 = 1;
            byte num63 = (byte) (index20 + num62);
            int num64 = (int) (byte)(num1 % 256L);
            numArray20[index20] = (byte) num64;
            this.fcs( this.nPkt, (int) (byte)((int) this.bytAddMode + 5), (byte) 1);
            this.fcs(this.nPkt, (int) (byte)((int) num63 - 1), (byte) 1);
            this.nPkt[(int) num63 + 2] = (byte) 126;
            byte num65 = (byte) 0;
            boolean flag3;
            do
            {
                this.ClearBuffer();
                flag3 = false;

                byte sendCommand1[] = new byte[num63 + 3];
                for(int ma=0; ma< num63 + 3 ;ma++)
                {
                    sendCommand1[ma]=this.nPkt[ma];
                    //appendLog( "S--"+  (int)this.nPkt[ma] );
                }
                this.SendPkt(port ,sendCommand1,  num63 + 3);

                //    this.SendPkt(port,this.nPkt, (byte)((int) num63 + 3));

                long original = System.currentTimeMillis();
                do
                {

                        this.DataReceive(port);

                    this.pktLength = ((int) this.nRcvPkt[1] & 7)+ this.nRcvPkt[2];
                    if (this.nCounter > 2 && this.pktLength + 2 <= this.nCounter && ((int) this.nRcvPkt[this.pktLength + 1] == 126 && this.fcs(this.nRcvPkt, this.pktLength, (byte) 0)))
                    {
                        flag3 = true;
                        num65 = (byte) 0;
                        this.FrameType();
                        break;
                    }
            else if ((System.currentTimeMillis() - original)/1000 > (int) nTimeOut && (int) num65 < (int) nTryCount)
                {

                    {
                        ++num65;
                        break;
                    }
                }
                }
                while ((int) num65 != (int) nTryCount);

                if (flag3)
                {
                    if ((int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 5)] == 151 || ((int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 5)] & 1) == 1)
                        //return false;
                        SbData.append("");
                    else
                        break;
                }
            }
            while ((int) num65 != (int) nTryCount);
            if (flag3 && (int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 11)] == 196 && (int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 12)] == 2)
            {
                num1 = this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 15)] + this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 16)] + this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 17)] + this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 18)];
                flag1 = !IntToBool(this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 14)]);
            }
            if (!flag3)
                //return false;
                SbData.append("");
            if ((int) this.nRcvPkt[(int) this.bytAddMode + 20] == 130)
            {
                for (int index21 = (int) (byte)((int) this.bytAddMode + 23); index21 < this.pktLength - 1; ++index21)
                  strbldDLMdata.append(Integer.toHexString(this.nRcvPkt[index21]));
            }
            else if ((int) this.nRcvPkt[(int) this.bytAddMode + 20] == 129)
            {
                for (int index21 = (int) (byte)((int) this.bytAddMode + 22); index21 < this.pktLength - 1; ++index21)
                    strbldDLMdata.append(Integer.toHexString(this.nRcvPkt[index21]));
            }
            else
            {
                for (int index21 = (int) (byte)((int) this.bytAddMode + 21); index21 < this.pktLength - 1; ++index21)
                    strbldDLMdata.append(Integer.toHexString(this.nRcvPkt[index21]));
            }
            while (((int) this.nRcvPkt[1] & 168) == 168)
            {
                this.nPkt[2] = (byte)((int) this.bytAddMode + 7);
                this.nRetLSH = (byte)((int) this.nRecvCntr << 5);
                this.nPkt[(int) (byte)((int) this.bytAddMode + 5)] = (byte) (this.nRetLSH | 16);
                this.nPkt[(int) (byte)((int) this.bytAddMode + 5)] = (byte) (this.nPkt[(int) (byte)((int) this.bytAddMode + 5)] | 1);
                this.fcs( this.nPkt, (int) (byte)((int) this.bytAddMode + 5), (byte) 1);
                this.nPkt[(int) (byte)((int) this.bytAddMode + 8)] = (byte) 126;
                byte num66 = (byte) 0;
                boolean flag4;
                do
                {
                    flag4 = false;

                    this.ClearBuffer();
                    byte sendCommand1[] = new byte[this.bytAddMode + 9];
                    for(int ma=0; ma< this.bytAddMode + 9 ;ma++)
                    {
                        sendCommand1[ma]=this.nPkt[ma];
                        //
                        // appendLog( "S--"+  (int)this.nPkt[ma] );
                    }
                    this.SendPkt(port ,sendCommand1, this.bytAddMode + 9);

                       // this.SendPkt(port,this.nPkt,  (byte)((int) this.bytAddMode + 9));
                    long original = System.currentTimeMillis();
                    do
                    {
                            this.DataReceive(port);
                        this.pktLength = (((int) this.nRcvPkt[1] & 7) + this.nRcvPkt[2]);
                        if (this.nCounter > 2 && this.pktLength + 2 <= this.nCounter && ((int) this.nRcvPkt[this.pktLength + 1] == 126 && this.fcs( this.nRcvPkt, this.pktLength, (byte) 0)))
                        {
                            flag4 = true;
                            num66 = (byte) 0;
                            for (int index21 = (int) (byte)((int) this.bytAddMode + 8); index21 < this.pktLength - 1; ++index21)
                              strbldDLMdata.append(Integer.toHexString(this.nRcvPkt[index21]));
                            this.FrameType();
                            break;
                        }
              else if ((System.currentTimeMillis() - original)/1000 > (int) nTimeOut && (int) num66 < (int) nTryCount)
                    {

                        {
                            ++num66;
                            break;
                        }
                    }
                    }
                    while ((int) num66 != (int) nTryCount);
                    if (flag4)
                    {
                        if ((int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 5)] == 151 || ((int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 5)] & 1) == 1)
                            //return false;
                            SbData.append("");
                        else
                            break;
                    }
                }
                while ((int) num66 != (int) nTryCount);
                if (!flag4)
                    //return false;
                    SbData.append("");
            }
        }

        appendLog("**********************************************************");
        appendLog(strbldDLMdata.toString());
        SbData.append(strbldDLMdata.toString());
        return SbData;
    }

    public static String byteArrayToHex(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length * 2);
        for(byte b: a)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }


    private int AARQ(UsbSerialPort port, byte bytAsslevel, String strPsd, int nWait, byte nTryCount, byte nTimeOut)
    {
        byte num1 =  8;
        this.nRetLSH= (byte) ((int)(this.nRecvCntr << 5));
        //this.nRetLSH =  this.nRecvCntr << 5;
        this.nPkt[(int) (byte)((int) this.bytAddMode + 5)] = (byte) ((int) this.nRetLSH | 16);
        this.nRetLSH = (byte)((int) this.nSentCntr << 1);
        this.nPkt[(int) (byte)((int) this.bytAddMode + 5)] = (byte)((int) this.nRetLSH | (int) this.nPkt[(int) (byte)((int) this.bytAddMode + 5)]);
        byte[] numArray1 = this.nPkt;
        int index1 = (int) num1;
        int num2 = 1;
        byte num3 = (byte) (index1 + num2);
        int num4 = 230;
        numArray1[index1] = (byte) num4;
        byte[] numArray2 = this.nPkt;
        int index2 = (int) num3;
        int num5 = 1;
        byte num6 = (byte) (index2 + num5);
        int num7 = 230;
        numArray2[index2] = (byte) num7;
        byte[] numArray3 = this.nPkt;
        int index3 = (int) num6;
        int num8 = 1;
        byte num9 = (byte) (index3 + num8);
        int num10 = 0;
        numArray3[index3] = (byte) num10;
        byte[] numArray4 = this.nPkt;
        int index4 = (int) num9;
        int num11 = 1;
        byte num12 = (byte) (index4 + num11);
        int num13 = 96;
        numArray4[index4] = (byte) num13;
        byte[] numArray5 = this.nPkt;
        int index5 = (int) num12;
        int num14 = 1;
        byte num15 = (byte) (index5 + num14);
        int num16 = 0;
        numArray5[index5] = (byte) num16;
        byte[] numArray6 = this.nPkt;
        int index6 = (int) num15;
        int num17 = 1;
        byte num18 = (byte) (index6 + num17);
        int num19 = 161;
        numArray6[index6] = (byte) num19;
        byte[] numArray7 = this.nPkt;
        int index7 = (int) num18;
        int num20 = 1;
        byte num21 = (byte) (index7 + num20);
        int num22 = 9;
        numArray7[index7] = (byte) num22;
        byte[] numArray8 = this.nPkt;
        int index8 = (int) num21;
        int num23 = 1;
        byte num24 = (byte) (index8 + num23);
        int num25 = 6;
        numArray8[index8] = (byte) num25;
        byte[] numArray9 = this.nPkt;
        int index9 = (int) num24;
        int num26 = 1;
        byte num27 = (byte) (index9 + num26);
        int num28 = 7;
        numArray9[index9] = (byte) num28;
        byte[] numArray10 = this.nPkt;
        int index10 = (int) num27;
        int num29 = 1;
        byte num30 = (byte) (index10 + num29);
        int num31 = 96;
        numArray10[index10] = (byte) num31;
        byte[] numArray11 = this.nPkt;
        int index11 = (int) num30;
        int num32 = 1;
        byte num33 = (byte) (index11 + num32);
        int num34 = 133;
        numArray11[index11] = (byte) num34;
        byte[] numArray12 = this.nPkt;
        int index12 = (int) num33;
        int num35 = 1;
        byte num36 = (byte) (index12 + num35);
        int num37 = 116;
        numArray12[index12] = (byte) num37;
        byte[] numArray13 = this.nPkt;
        int index13 = (int) num36;
        int num38 = 1;
        byte num39 = (byte) (index13 + num38);
        int num40 = 5;
        numArray13[index13] = (byte) num40;
        byte[] numArray14 = this.nPkt;
        int index14 = (int) num39;
        int num41 = 1;
        byte num42 = (byte) (index14 + num41);
        int num43 = 8;
        numArray14[index14] = (byte) num43;
        byte[] numArray15 = this.nPkt;
        int index15 = (int) num42;
        int num44 = 1;
        byte num45 = (byte) (index15 + num44);
        int num46 = 1;
        numArray15[index15] = (byte) num46;
        byte[] numArray16 = this.nPkt;
        int index16 = (int) num45;
        int num47 = 1;
        byte num48 = (byte) (index16 + num47);
        int num49 = 1;
        numArray16[index16] = (byte) num49;
        if ((int) bytAsslevel == 0)
        {
            this.nPkt[(int) (byte)((int) this.bytAddMode + 12)] = (byte) 29;
        }
        else
        {
            byte[] numArray17 = this.nPkt;
            int index17 = (int) num48;
            int num50 = 1;
            byte num51 = (byte) (index17 + num50);
            int num52 = 138;
            numArray17[index17] = (byte) num52;
            byte[] numArray18 = this.nPkt;
            int index18 = (int) num51;
            int num53 = 1;
            byte num54 = (byte) (index18 + num53);
            int num55 = 2;
            numArray18[index18] = (byte) num55;
            byte[] numArray19 = this.nPkt;
            int index19 = (int) num54;
            int num56 = 1;
            byte num57 = (byte) (index19 + num56);
            int num58 = 7;
            numArray19[index19] = (byte) num58;
            byte[] numArray20 = this.nPkt;
            int index20 = (int) num57;
            int num59 = 1;
            byte num60 = (byte) (index20 + num59);
            int num61 = 128;
            numArray20[index20] = (byte) num61;
            byte[] numArray21 = this.nPkt;
            int index21 = (int) num60;
            int num62 = 1;
            byte num63 = (byte) (index21 + num62);
            int num64 = 139;
            numArray21[index21] = (byte) num64;
            byte[] numArray22 = this.nPkt;
            int index22 = (int) num63;
            int num65 = 1;
            byte num66 = (byte) (index22 + num65);
            int num67 = 7;
            numArray22[index22] = (byte) num67;
            byte[] numArray23 = this.nPkt;
            int index23 = (int) num66;
            int num68 = 1;
            byte num69 = (byte) (index23 + num68);
            int num70 = 96;
            numArray23[index23] = (byte) num70;
            byte[] numArray24 = this.nPkt;
            int index24 = (int) num69;
            int num71 = 1;
            byte num72 = (byte) (index24 + num71);
            int num73 = 133;
            numArray24[index24] = (byte) num73;
            byte[] numArray25 = this.nPkt;
            int index25 = (int) num72;
            int num74 = 1;
            byte num75 = (byte) (index25 + num74);
            int num76 = 116;
            numArray25[index25] = (byte) num76;
            byte[] numArray26 = this.nPkt;
            int index26 = (int) num75;
            int num77 = 1;
            byte num78 = (byte) (index26 + num77);
            int num79 = 5;
            numArray26[index26] = (byte) num79;
            byte[] numArray27 = this.nPkt;
            int index27 = (int) num78;
            int num80 = 1;
            byte num81 = (byte) (index27 + num80);
            int num82 = 8;
            numArray27[index27] = (byte) num82;
            byte[] numArray28 = this.nPkt;
            int index28 = (int) num81;
            int num83 = 1;
            byte num84 = (byte) (index28 + num83);
            int num85 = 2;
            numArray28[index28] = (byte) num85;
            byte[] numArray29 = this.nPkt;
            int index29 = (int) num84;
            int num86 = 1;
            byte num87 = (byte) (index29 + num86);
            int num88 = (int) bytAsslevel;
            numArray29[index29] = (byte) num88;
            byte[] numArray30 = this.nPkt;
            int index30 = (int) num87;
            int num89 = 1;
            byte num90 = (byte) (index30 + num89);
            int num91 = 172;
            numArray30[index30] = (byte) num91;
            byte[] numArray31 = this.nPkt;
            int index31 = (int) num90;
            int num92 = 1;
            byte num93 = (byte) (index31 + num92);
            int num94 = (int) (byte)(2 + strPsd.length());
            numArray31[index31] = (byte) num94;
            byte[] numArray32 = this.nPkt;
            int index32 = (int) num93;
            int num95 = 1;
            byte num96 = (byte) (index32 + num95);
            int num97 = 128;
            numArray32[index32] = (byte) num97;
            byte[] numArray33 = this.nPkt;
            int index33 = (int) num96;
            int num98 = 1;
            num48 = (byte) (index33 + num98);
            int num99 = (int) (byte)(strPsd.length());
            numArray33[index33] = (byte) num99;
            //ASCIIEncoding asciiEncoding = new ASCIIEncoding();
            //this.Ps = (int) bytAsslevel != 1 ? asciiEncoding.GetBytes("GNSRAPDRP-" + DateTime.Now.ToString("HHmmss")) : asciiEncoding.GetBytes(strPsd);

            if (strPsd=="ABCD0001")
                this.Ps =new byte[]{ 65, 66, 67, 68, 48, 48,48,49};
            else if(strPsd=="1A2B3C4D")
                this.Ps =new byte[]{ 49, 65, 50, 66, 51, 67, 52, 68};
            else if(strPsd=="lnt1")
                this.Ps =new byte[]{ 108, 110, 116, 49};
            else if(strPsd=="11111111")
                this.Ps =new byte[]{ 49, 49, 49, 49, 49, 49, 49 ,49};
            else if(strPsd=="1111111111111111")
                this.Ps =new byte[]{ 49, 49, 49, 49, 49, 49, 49 ,49,49, 49, 49, 49, 49, 49, 49 ,49};


            for (int index34 = 0; index34 < strPsd.length(); ++index34)
                this.nPkt[(int) num48++] = this.Ps[index34];
            this.nPkt[(int) (byte)((int) this.bytAddMode + 12)] = (byte)(46 + strPsd.length());
        }
        byte[] numArray34 = this.nPkt;
        int index35 = (int) num48;
        int num100 = 1;
        byte num101 = (byte) (index35 + num100);
        int num102 = 190;
        numArray34[index35] = (byte) num102;
        byte[] numArray35 = this.nPkt;
        int index36 = (int) num101;
        int num103 = 1;
        byte num104 = (byte) (index36 + num103);
        int num105 = 16;
        numArray35[index36] = (byte) num105;
        byte[] numArray36 = this.nPkt;
        int index37 = (int) num104;
        int num106 = 1;
        byte num107 = (byte) (index37 + num106);
        int num108 = 4;
        numArray36[index37] = (byte) num108;
        byte[] numArray37 = this.nPkt;
        int index38 = (int) num107;
        int num109 = 1;
        byte num110 = (byte) (index38 + num109);
        int num111 = 14;
        numArray37[index38] = (byte) num111;
        byte[] numArray38 = this.nPkt;
        int index39 = (int) num110;
        int num112 = 1;
        byte num113 = (byte) (index39 + num112);
        int num114 = 1;
        numArray38[index39] = (byte) num114;
        byte[] numArray39 = this.nPkt;
        int index40 = (int) num113;
        int num115 = 1;
        byte num116 = (byte) (index40 + num115);
        int num117 = 0;
        numArray39[index40] = (byte) num117;
        byte[] numArray40 = this.nPkt;
        int index41 = (int) num116;
        int num118 = 1;
        byte num119 = (byte) (index41 + num118);
        int num120 = 0;
        numArray40[index41] = (byte) num120;
        byte[] numArray41 = this.nPkt;
        int index42 = (int) num119;
        int num121 = 1;
        byte num122 = (byte) (index42 + num121);
        int num123 = 0;
        numArray41[index42] = (byte) num123;
        byte[] numArray42 = this.nPkt;
        int index43 = (int) num122;
        int num124 = 1;
        byte num125 = (byte) (index43 + num124);
        int num126 = 6;
        numArray42[index43] = (byte) num126;
        byte[] numArray43 = this.nPkt;
        int index44 = (int) num125;
        int num127 = 1;
        byte num128 = (byte) (index44 + num127);
        int num129 = 95;
        numArray43[index44] = (byte) num129;
        byte[] numArray44 = this.nPkt;
        int index45 = (int) num128;
        int num130 = 1;
        byte num131 = (byte) (index45 + num130);
        int num132 = 31;
        numArray44[index45] = (byte) num132;
        byte[] numArray45 = this.nPkt;
        int index46 = (int) num131;
        int num133 = 1;
        byte num134 = (byte) (index46 + num133);
        int num135 = 4;
        numArray45[index46] = (byte) num135;
        byte[] numArray46 = this.nPkt;
        int index47 = (int) num134;
        int num136 = 1;
        byte num137 = (byte) (index47 + num136);
        int num138 = 0;
        numArray46[index47] = (byte) num138;
        byte[] numArray47 = this.nPkt;
        int index48 = (int) num137;
        int num139 = 1;
        byte num140 = (byte) (index48 + num139);
        int num141 = 0;
        numArray47[index48] = (byte) num141;
        byte[] numArray48 = this.nPkt;
        int index49 = (int) num140;
        int num142 = 1;
        byte num143 = (byte) (index49 + num142);
        int num144 = 24;
        numArray48[index49] = (byte) num144;
        byte[] numArray49 = this.nPkt;
        int index50 = (int) num143;
        int num145 = 1;
        byte num146 = (byte) (index50 + num145);
        int num147 = 29;
        numArray49[index50] = (byte) num147;
        byte[] numArray50 = this.nPkt;
        int index51 = (int) num146;
        int num148 = 1;
        byte num149 = (byte) (index51 + num148);
        int num150 = (int) 255;
        numArray50[index51] = (byte) num150;
        byte[] numArray51 = this.nPkt;
        int index52 = (int) num149;
        int num151 = 1;
        byte num152 = (byte) (index52 + num151);
        int num153 = (int) 255;
        numArray51[index52] = (byte) num153;
        this.nPkt[2] = (byte)((int) num152 + 1);
        fcs( this.nPkt, ( this.bytAddMode + 5), (byte) 1);
        fcs( this.nPkt,  num152 - 1, (byte) 1);
        this.nPkt[(int) num152 + 2] = (byte) 126;
        byte num154 = (byte) 0;
        boolean flag;
        do
        {
            this.ClearBuffer();
            flag = false;
            byte sendCommand1[] = new byte[num152+3];
            for(int ma=0; ma< num152 + 3 ;ma++)
            {
                sendCommand1[ma]=this.nPkt[ma];
                // appendLog( "S--"+  (int)this.nPkt[ma] );
            }
            this.SendPkt(port ,sendCommand1,  num152 + 3);

            long original = System.currentTimeMillis();
            do
            {
                // appendLog("In Data Receive");
                //this.Wait((double) nWait);
                this.DataReceive(port);
                long  Second=(int) System.currentTimeMillis() - original;

                if (this.nCounter > 2 && (int) this.nRcvPkt[2] + 2 <= this.nCounter && this.fcs(this.nRcvPkt, (int) this.nRcvPkt[2], (byte) 0))
                {

                    flag = true;
                    this.FrameType();
                    break;
                }



                else if ((System.currentTimeMillis() - original)/1000 > (int) nTimeOut && (int) num154 < (int) nTryCount)
                {
                    ++num154;
                    break;
                }
            }
            while (!flag);
        }
        while (!flag && (int) num154 != (int) nTryCount);
        if (!flag || (int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 28)] != 0 || this.nCounter <= 27)
            return 1;
        if ((int) bytAsslevel != 2)
            return 0;
       /* this.keyBytes = FrmDLMS.StrToByteArray(strPsd.Trim());
        Aes aes = new Aes((Aes.KeySize) 0, this.keyBytes);
        for (int index17 = 0; index17 < 16; ++index17)
            this.plainText[index17] = this.nRcvPkt[index17 + (int) Convert.ToByte((int) this.bytAddMode + 53)];
        aes.Cipher(this.plainText, this.cipherText);
        if (this.ActionCmd(this.cipherText))
            return 0;
        this.LblStatus.Text = "Authentication Fail";*/
        return 2;
    }

    private void FrameType()
    {
        this.pktLength =   (int) (this.nRcvPkt[1] & 7)  + (int) this.nRcvPkt[2];
        //this.pktLength = int.Parse(((int) this.nRcvPkt[1] & 7).ToString("X2") + this.nRcvPkt[2].ToString("X2"), NumberStyles.HexNumber);

        //appendLog("Frame Packet Length"+ pktLength);
        if ((int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 5)] != 115 && ((int) this.nRcvPkt[1] & 168) == 160)
        {
            this.nRecv = (byte)((int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 5)] >> 5);
            this.nSent = (byte)((int) this.nRcvPkt[(int) (byte)((int) this.bytAddMode + 5)] >> 1 & 7);
        }
        if (((int) this.nRcvPkt[1] & 168) == 160 && this.pktLength > 10 || ((int) this.nRcvPkt[1] & 168) == 168)
            this.nRecvCntr = (int) this.nRecvCntr != 7 ? ++this.nRecvCntr : (byte) 0;
        if (((int) this.nRcvPkt[1] & 168) != 168)
        {
            if ((int) this.nRecvLast != 7)
            {
                if ((int) this.nRecv - (int) this.nRecvLast == 1)
                    this.nSentCntr = (int) this.nSentCntr != 7 ? ++this.nSentCntr : (byte) 0;
            }
            else if ((int) this.nRecvLast - (int) this.nRecv == 7)
                this.nSentCntr = (int) this.nSentCntr != 7 ? ++this.nSentCntr : (byte) 0;
        }
        this.nRecvLast = this.nRecv;
        this.nSentLast = this.nSent;
    }
    public void onbtnDLMS (View v) {
        TextView tv = (TextView) findViewById(R.id.textView1);
        StringBuilder sb = new StringBuilder();
        try {


            PendingIntent mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
            mSerial = new PL2303Driver((UsbManager) getSystemService(Context.USB_SERVICE), this, ACTION_USB_PERMISSION);
            UsbManager manager = (UsbManager) getApplicationContext().getSystemService(Context.USB_SERVICE);

            List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
            UsbSerialDriver driver = availableDrivers.get(0);
            sb.append("Driver Name" +driver.toString());
            sb.append("\n");

            UsbDevice  devices = driver.getDevice();
            manager.requestPermission(devices, mPermissionIntent);
            sb.append("Device Name--"+ devices.getDeviceName() );
            sb.append("\n");


            UsbDeviceConnection connection = manager.openDevice(driver.getDevice());

            if (connection == null) {
                sb.append("manager.openDevice(driver.getDevice() " );
                sb.append("\n");
                manager.requestPermission(devices,
                        PendingIntent.getBroadcast(getApplicationContext(), 0, new Intent(Home.ACTION_USB_PERMISSION), 0));
            }
            else {
                sb.append("Geting connection" + connection.getSerial());
                sb.append("\n");
            }
            sb.append("opening port" );
            sb.append("\n");
            UsbSerialPort port = driver.getPorts().get(0);
            sb.append("port Serrial " +port.getSerial());
            sb.append("\n");


            boolean parameter = true;
            sb.append("Port Connecting");
            sb.append("\n");
            port.open(connection);

            port.setParameters(9600, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            sb.append("Set Port Setting");
            Fcs_Tab();
            sb.append("\n");
            this.AddressInit();
            sb.append("Address Init & Sending NRM");
            sb.append("\n");
             this.SetNRM(port,this.bytWait, (byte) 3, this.bytTimOut);
            int i= port.write(this.nPkt, 9);

            int num = port.read(this.buffer,  64);

            if (parameter == true) {
                sb.append("SetNRM is ok");
                sb.append("\n");
            }
              else {
                sb.append("SetNRM Not ok");
                sb.append("\n");
            }
              tv.setText(sb.toString());
        }
        catch (Exception ex)
        {
            String x= "Error:--" + ex.getMessage().toString();
            sb.append(x);
            tv.setText(sb.toString());

        }
    }
    public void ClearBuffer()
    {
        for (int index = 0; index <= this.nCounter; ++index)
            this.nRcvPkt[index] = (byte) 0;
        this.nCounter = 0;
    }
    private boolean SendPkt(UsbSerialPort port,byte[] buffer, int length)
    {
        try {
            //DateTime now = DateTime.Now;
            //this.port.DiscardInBuffer();
            // this.ComPort.DiscardOutBuffer();
            if (port != null)
            {
                appendLog("Port is available ");

            }
            else
                appendLog("Port is available ");

           appendLog("Write Data In Port");

           String s = Base64.getEncoder().encodeToString(buffer);
           appendLog("Buffer " +s);
            appendLog("length " +length);
           int i= port.write(buffer, length);
           appendLog("Write Sucessfully Data In Port with Length" +i);
           return true;
        }
        catch (Exception ex)
        {

            appendLog("Error: " +ex.getMessage().toString());
            return false;
        }
    }
    private void DataReceive(UsbSerialPort port)
    {
        try
        {
            int num = port.read(this.buffer,  64);
           // appendLog("Data Recevie Num:-"+ num );
            {
                for (int index = 0; index < num; ++index) {
                    this.nRcvPkt[this.nCounter++] = this.buffer[index];
                    //appendLog("Buffer Index" + this.buffer[index]);


                }
            }
        }
        catch (Exception ex)
        {
        }
    }

    private int fcs_cal(int fcs,  byte[] cp, int length)
    {
        int num = 1;

        boolean b = (length != 0);
        while ( b) {
            fcs = (fcs >> (8) ^ (int) this.fcstab[((int) fcs ^ (int) (cp[num++])) & (int) 255]);
            length--;
            b = (length != 0);
        }
        return fcs;
    }

    public boolean IntToBool(int Number)
    {

        if (Number >0)
            return true;
        else
            return false;
    }
    public  void Fcs_Tab()
    {
      //  appendLog("call FCS Tab" );
        int num1 =  0;
        do {
            int num2 = num1;
            short num3 = (short) 8;
            boolean b = (num3 != 0);
            while (b) {
                num2 = (IntToBool((int) num2 & (int) (1)) ? (int) num2 >> (int) (1) ^ 33800 : (int) num2 >> (int) (1));
                this.fcstab[(int) num1] =  num2 &   65535;
                num3--;
              //  appendLog("TAB fcsTab[" + num1 +"]" + (num2 &   65535) );
                b = (num3 != 0);
            }
        }
        while ((int) ++num1 != 256);
    }


    public boolean fcs( byte[] cp, int len, byte flag)
    {
        if ((flag!=0))
        {


            int num =   this.fcs_cal(65535,  cp, len) ^  ((int) 65535);
            appendLog("fcs Length Pass:-" + len);
            appendLog("fcs Num:-" + num);
            cp[len + 1] = (byte) (num &  255) ;
            cp[len + 2] = (byte)  (num >>  (8 &  255));
            return true;

        }
        else
          return (int) this.fcs_cal(65535, cp, len) == 61624;
    }

    private boolean SetNRM(UsbSerialPort port, int nWait, byte nTryCount, byte nTimeOut) {
        boolean flag1 = false;
        byte num1 = (byte) 5;
        this.nPkt[2] = (byte) 7;
        byte[] numArray1 = this.nPkt;
        int index1 = (int) num1;
        int num2 = 1;
        byte num3 = (byte) (index1 + num2);
        int num4 = 83;
        numArray1[index1] = (byte) num4;
        this.nPkt[(int) num3 + 2] = (byte) 126;
        fcs(this.nPkt, (int) (5 + (int) this.bytAddMode), (byte) 1);

        this.ClearBuffer();
        appendLog("Pkt Sending: " );

        byte sendCommand[] = new byte[]{nPkt[0] ,nPkt[1],nPkt[2],nPkt[3],nPkt[4],nPkt[5],nPkt[6],nPkt[7],nPkt[8]};
        boolean Result =this.SendPkt( port,sendCommand, 9);
        //int i= port.write(securityLnG, 9);
       // appendLog("Packet Write Response : " +i);
       // sb.append("\n");
       // int num = port.read(this.buffer,  64);
        if (Result== true)
           appendLog("Data write in port " );
        else
            appendLog("Data Unbale to write in port " );
        //int i= port.write(this.nPkt, 9);


        Time now1 = new Time();
        now1.setToNow();

        int cnt=1;
        appendLog("Loop Count: " +cnt);
        appendLog("Recive Packet Before " +nRcvPkt);

        long original = System.currentTimeMillis();
        while (true) {
            appendLog("Loop Count: " +cnt);
            cnt++;
             this.DataReceive(port);
            if (this.nCounter > 2 && this.nRcvPkt[2] + 2 <= this.nCounter && this.fcs(this.nRcvPkt, this.nRcvPkt[2], (byte) 0)) {
                flag1 = true;
                appendLog("Condidtion True" );
                break;
            }
            else
            {
                appendLog("Condidtion False" );
            }
            if (System.currentTimeMillis() - original >= 5000) {
                break;
            }

        }

        byte num5 = 5;//(byte) 5 +  this.bytAddMode;
        byte[] numArray2 = this.nPkt;
        int index2 = (int) num5;
        int num6 = 1;
        byte num7 = (byte) (index2 + num6);
        int num8 = 147;
        numArray2[index2] = (byte) num8;

        int my = num7 + 1;
        this.nPkt[2] = (byte) my;
        this.fcs(this.nPkt, this.bytAddMode + 5, (byte) 1);
        this.nPkt[(int) num7 + 2] = (byte) 126;

        byte num70 = (byte) 0;
        boolean flag2;
        do {
            this.ClearBuffer();
            flag2 = false;
            byte sendCommand1[] = new byte[num7+3];
            appendLog("Num 7 Value :--" + num7);
            for(int ma=0; ma< num7 + 3 ;ma++)
            {
                sendCommand1[ma]=this.nPkt[ma];
              //  appendLog( "S"+  (int)this.nPkt[ma] );
            }
            this.SendPkt(port,sendCommand1,  num7 + 3);

            //DateTime now2 = DateTime.Now;
            Time now2 = new Time();
            now2.setToNow();
            Time t3 = new Time();

            do {
                this.DataReceive(port);

                if (this.nCounter > 2 && (int) this.nRcvPkt[2] + 2 <= this.nCounter && ((int) this.nRcvPkt[(int) this.nRcvPkt[2] + 1] == 126 && this.fcs(this.nRcvPkt, (this.nRcvPkt[2]), (byte) 0)))
                {
                    flag2 = true;
                    break;
                }
                else
                if ((TimeUnit.MILLISECONDS.toSeconds(t3.toMillis(true) - now2.toMillis(true))) > (int) nTimeOut && (int) num70 < (int) nTryCount) {
                    ++num70;
                    break;
                }
            }
            while (!flag2);
        }  while (!flag2 && (int) num70 != (int) nTryCount);

        appendLog("Recive Packet After " +nRcvPkt);

        if ((int) this.nRcvPkt[ this.bytAddMode + 5] == 115)
            return flag2;
        else
            return false;

    }


    private void AddressInit()
    {
        int num1 = 65;
        this.nPkt[0] = (byte) 126;
        this.nPkt[1] = (byte) 160;
        if ((int) this.bytAddMode == 0)
        {
            this.nPkt[3] = (byte)3;
            this.nPkt[4] = (byte)(num1);
        }

        this.nRecv = (byte) 0;
        this.nRecvLast = (byte) 0;
        this.nRecvCntr = (byte) 0;
        this.nSent = (byte) 0;
        this.nSentLast = (byte) 0;
        this.nSentCntr = (byte) 0;
    }
    public void onbtnUpload(View v) {

        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        UsbSerialDriver driver = availableDrivers.get(0);
        UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
        StringBuilder sb2 = new StringBuilder();
         UsbSerialPort port = driver.getPorts().get(0);

        TextView tv = (TextView)findViewById(R.id.textView1);
        try {

            port.open(connection);
            port.setParameters(300, UsbSerialPort.DATABITS_7, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_EVEN);
            int usbResultOut, usbResultIn1, usbResultIn2, usbResultIn3;
            String tOut = "/?!\r\n";  //This is the data i am sending to serial device.
            byte securityLnG[] = new byte[]{47, 63, 33, 13, 10};
            byte securityLnGBill[] = new byte[]{ 6, 48, 48, 48, 13, 10};
            byte[] bytesOut = tOut.getBytes(); //convert String to byte[]
            byte[] bytesIn1 = new byte[255];
            byte[] bytesIn2 = new byte[25];
            byte[] bytesIn3 = new byte[25];


            usbResultOut = port.write(securityLnG, securityLnG.length); //write the data to serial device.
            Toast.makeText(getApplicationContext(), "usbResultOut"+ usbResultOut , 0).show();
            Thread.sleep(1000);
            usbResultIn1 = port.read(bytesIn1, 255);  //read the data but in my case 0 bytes received.
            StringBuilder sbHex = new StringBuilder();
            int len =bytesIn1.length;
            for (int j = 0; j < len; j++) {
                sbHex.append((char) (bytesIn1[j] & 255));

            }
            tv.setText(sbHex.toString());
            Thread.sleep(100);
            usbResultOut = port.write(securityLnGBill, securityLnGBill.length);
            Toast.makeText(getApplicationContext(), "Length :"+usbResultOut  , 0).show();
            Thread.sleep(3000);
            for(int i=0;i<100 ;i++) {

                bytesIn1 = new byte[255];
                usbResultIn1 = port.read(bytesIn1, 255);  //read the data but in my case 0 bytes received.
                len = bytesIn1.length;

                sbHex = new StringBuilder();
                for (int j = 0; j < len; j++) {

                    sbHex.append((char) (bytesIn1[j] & 255));

                    //appendLog(i.toString());
                }
                if (sbHex.toString().contains("!") )
                {
                    appendLog("i am Call");
                    port.close();
                    break;
                }
                appendLog(sbHex.toString());
                Thread.sleep(300);
            }
           // tv.setText(sbHex.toString());
            tv.setText(sb2.toString());




            Toast.makeText(getApplicationContext(), "Length :"+len +  "Data"+ sbHex.toString() , 0).show();
            // usbResultIn2 = port.read(bytesIn2, 1000);  //read the data but in my case 0 bytes received.
            // usbResultIn3 = port.read(bytesIn3, 1000);  //read the data, this time the data is received.
        }
        catch (Exception x)
        {

            tv.setText(x.getMessage().toString());
            Toast.makeText(getApplicationContext(), "Eror"+ x.getMessage().toString() , 0).show();

        }

    }
    private void openUsbSerial() {

    }//openUsbSerial

    public void appendLog(String text)
    {
        File logFile = new File(Environment.getExternalStorageDirectory()+ "/" + "OPDLlog.file");
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
            e.printStackTrace();
        }
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
