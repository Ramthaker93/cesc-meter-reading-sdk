package com.npcl.com.vcpopdl;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.database.Cursor;
import android.os.Handler;
import android.os.Bundle;
import android.widget.EditText;
import android.view.View;
import java.util.Set;
import android.bluetooth.BluetoothSocket;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.io.InputStream;
import java.io.OutputStream;

public class Reprint extends AppCompatActivity {

    boolean stopWorker = false;
    Thread workerThread;
    BluetoothAdapter btAdapter;
    BluetoothSocket mmSocket;
    BluetoothDevice mmDevice;
    OutputStream mmOutputStream;
    InputStream mmInputStream;
    int readBufferPosition = 0;
    byte[] readBuffer = new byte[1024];
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reprint);
        Bundle bundle = getIntent().getExtras();
        String text = bundle.getString("Data");
        String[] Temp = text.split("#");
        if (Temp.length > 0) {

            String Data = Temp[0].toString();
            String LogUser = Temp[1].toString();
            EditText txtuser = (EditText) findViewById(R.id.txtuser);
            txtuser.setText(LogUser);
            String ConsumerNo= "";
            String ReciptNo="";
            String[] Temp1= Data.split("::");
            if (Temp1.length >0) {
                ConsumerNo=Temp1[0].toString();
                ReciptNo=Temp1[1].toString();
                BindData(ConsumerNo, ReciptNo);
            }
        }
    }


  public void PrintText(String Data)
    {

        // Toast.makeText(getApplicationContext(),Data,Toast.LENGTH_SHORT).show();
        try {
            String Device="";
            btAdapter = BluetoothAdapter.getDefaultAdapter();
            if (btAdapter.isEnabled()) {

                Set<BluetoothDevice> devices = btAdapter.getBondedDevices();
                for (BluetoothDevice device : devices) {
                    Device=device.getName();
                    if (  device.getName().contains("AT2TV") || device.getName().equals("ANTHERMAL")|| device.getName().equals("MPT-II")||device.getName().equals("MPT-ll")||device.getName().equals("TM-P20_000028")||device.getName().contains("BT"))
                    {
                        mmDevice = device;
                        UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
                        mmSocket = mmDevice.createRfcommSocketToServiceRecord(uuid);
                        Thread.sleep(100);
                        mmSocket.connect();
                        Thread.sleep(500);
                        mmOutputStream = mmSocket.getOutputStream();
                        mmInputStream = mmSocket.getInputStream();
                        beginListenForData();
                        Thread.sleep(50);
                        mmOutputStream.write(Data.getBytes());
                        stopWorker = true;
                        Thread.sleep(100);
                        mmOutputStream.close();
                        mmInputStream.close();
                        mmSocket.close();
                        break;
                    }

                }


            }
        }
        catch (Exception ex)
        {
            String   ErrMsg = ex.getMessage().toString();
            Toast.makeText(getApplicationContext(),ErrMsg,Toast.LENGTH_SHORT).show();
        }
    }
    public void PrintBill(String ConsNo, String ReciptNo)
    {
        try {


            int rcpt=0;
            String ReceiptNo="";
            String ConsumerNo="";
            String Name="";
            String Address="";
            String datetmprnt="";
            String msg="\n\n\n";
            String CardPStatus="";
            String AmountPaid="";
            String PaymentMode="";
            String ChequeNo="";
            String ChequeDate="";
            String ContractNo="";
            String UserId="";
            String Paydate="";
            SimpleDateFormat dateFormatter = new SimpleDateFormat("dd/MM/yyyy hh:mm:ss");
            dateFormatter.setLenient(false);
            Date today = new Date();
            datetmprnt = dateFormatter.format(today);

            String Sql ="select * from PaymentData where  ConsumerNo='" + ConsNo + "'";
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());

            Cursor c = db.GetData(Sql);

            if (c.moveToFirst()) {
                do {
                    Name = c.getString(c.getColumnIndex("Name"));
                    ConsumerNo = c.getString(c.getColumnIndex("ConsumerNo"));
                    String Co = c.getString(c.getColumnIndex("Co"));
                    String HouseNo = c.getString(c.getColumnIndex("HouseNo"));
                    String Street = c.getString(c.getColumnIndex("Street"));

                    Address =Co +" " + HouseNo+" " +Street;
                } while (c.moveToNext());//Move the cursor to the next row.
            }

            Sql ="select * from PaymentDetails where  ReceiptNo='" + ReciptNo + "'";
            db = new DatabaseHandler(getApplicationContext());

            c = db.GetData(Sql);

            if (c.moveToFirst()) {
                do {
                    ReceiptNo = c.getString(c.getColumnIndex("ReceiptNo"));
                    CardPStatus=c.getString(c.getColumnIndex("CardPStatus"));
                    AmountPaid=c.getString(c.getColumnIndex("AmountPaid"));
                    PaymentMode=c.getString(c.getColumnIndex("PaymentMode"));
                    ChequeNo=c.getString(c.getColumnIndex("ChequeNo"));
                    ChequeDate=c.getString(c.getColumnIndex("ChequeDate"));
                    ContractNo=c.getString(c.getColumnIndex("ContractNo"));
                    UserId=c.getString(c.getColumnIndex("UserId"));
                    Paydate= c.getString(c.getColumnIndex("PaymentDate"));

                } while (c.moveToNext());//Move the cursor to the next row.

            }


            rcpt=1;
            msg ="*******************************\n";
            if (rcpt > 0)
            {
                msg+="Duplicate \n" ;
            }

            msg+="* Noida Power Company Limited * \n";
            msg+="*   Greater Noida-201310      *\n";
            msg+="*******************************\n";
            msg+="Receipt No.:" + ReceiptNo + "\n";
            msg+="Consumer No.:" + ConsumerNo + "\n";
            msg+="Name        :" + Name + "\n";
            msg+="Address :" + Address + "\n";
            msg+="Dated:" + datetmprnt +" \n";

            if (CardPStatus.equals( "Failure"))
            {
                msg+="Amount Rs.  : " + AmountPaid +"\n";
            }
            else
            {
                msg+="Received Rs.: " + AmountPaid + "\n";
            }
            msg+="Mode        : " + PaymentMode + "\n";
            if (PaymentMode.equals("Cheque"))
            {
                msg+=" Cheque No   : " + ChequeNo + "\n";
                msg+=" Cheque Date : " + ChequeDate +"\n";
            }


            else if (PaymentMode.equals("Card"))
            {
                msg+=" Txn Status  : " + CardPStatus + "\n";

                String txnref = ContractNo;

                msg+=" Txn Refe No.: " + txnref + "\n";
            }

            msg+=" VCP ID      : " + UserId + "\n";


            final long number = Long.parseLong(AmountPaid);
            String AmtInWord = Words.convert(number);

            String InWord1 = "";
            String InWord2 = "";

            if (AmtInWord.length() > 22)
            {
                int index = AmtInWord.indexOf(" ", 16);
                InWord1 = AmtInWord.substring(0, index);
                InWord2 = AmtInWord.replace(InWord1,"") ;//  .substring(index, AmtInWord.length() - index);

                msg+=" (Rupees " + InWord1 + "\n";

                if (PaymentMode.equals( "Cheque"))
                {
                    msg+= InWord2 + " only)" + "\n";
                }
                else
                {
                    msg+=InWord2 + " only)\n\r\n\r\n\r\n";

                    msg+="-------------------------------";
                    msg+="\r\n";
                }
            }
            else
            {
                InWord1 = AmtInWord;
                if (PaymentMode.equals("Cheque") || PaymentMode.equals("Card"))
                {
                    msg+=" (Rupees " + AmtInWord + " only)\n" ;
                }
                else
                {

                    msg+=" (Rupees " + AmtInWord + " only)\r\n\r\n\r\n";

                    msg+="-------------------------------";
                    msg+="\r\n";
                }
            }


            if (PaymentMode.equals("Cheque"))
            {
                msg+=" ** Payment made through Cheque is subject to realization\r\n\r\n\r\n\r\n";
                msg+="-------------------------------";
                msg+="\r\n";
            }
            msg += "\n\n\n";
            PrintText(msg);

        }
        catch (Exception ex)
        {

            String Error =ex.getMessage().toString();
        }
    }

    void beginListenForData() {
        try {
            final Handler handler = new Handler();

            // this is the ASCII code for a newline character
            final byte delimiter = 10;

            stopWorker = false;
            readBufferPosition = 0;
            readBuffer = new byte[1024];

            workerThread = new Thread(new Runnable() {
                public void run() {

                    while (!Thread.currentThread().isInterrupted() && !stopWorker) {

                        try {

                            int bytesAvailable = mmInputStream.available();

                            if (bytesAvailable > 0) {

                                byte[] packetBytes = new byte[bytesAvailable];
                                mmInputStream.read(packetBytes);

                                for (int i = 0; i < bytesAvailable; i++) {

                                    byte b = packetBytes[i];
                                    if (b == delimiter) {

                                        byte[] encodedBytes = new byte[readBufferPosition];
                                        System.arraycopy(
                                                readBuffer, 0,
                                                encodedBytes, 0,
                                                encodedBytes.length
                                        );

                                        // specify US-ASCII encoding
                                        final String data = new String(encodedBytes, "US-ASCII");
                                        readBufferPosition = 0;

                                        // tell the user data were sent to bluetooth printer device
                                        handler.post(new Runnable() {
                                            public void run() {
                                                //  myLabel.setText(data);
                                            }
                                        });

                                    } else {
                                        readBuffer[readBufferPosition++] = b;
                                    }
                                }
                            }

                        } catch (IOException ex) {
                            stopWorker = true;
                        }

                    }
                }
            });

            workerThread.start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public void BindData(String ConsNo,String ReciptNo)
    {
        int rcpt=0;
        String ReceiptNo="";
        String ConsumerNo="";
        String Name="";
        String Address="";
        String datetmprnt="";
        String msg="\n\n\n";
        String CardPStatus="";
        String AmountPaid="";
        String PaymentMode="";
        String ChequeNo="";
        String ChequeDate="";
        String ContractNo="";
        String UserId="";

        SimpleDateFormat dateFormatter = new SimpleDateFormat("dd/MM/yyyy hh:mm:ss");
        dateFormatter.setLenient(false);
        Date today = new Date();
        datetmprnt = dateFormatter.format(today);

        String Sql ="select * from PaymentData where  ConsumerNo='" + ConsNo + "'";
        DatabaseHandler db = new DatabaseHandler(getApplicationContext());

        Cursor c = db.GetData(Sql);

        if (c.moveToFirst()) {
            do {
                Name = c.getString(c.getColumnIndex("Name"));
                ConsumerNo = c.getString(c.getColumnIndex("ConsumerNo"));
                String Co = c.getString(c.getColumnIndex("Co"));
                String HouseNo = c.getString(c.getColumnIndex("HouseNo"));
                String Street = c.getString(c.getColumnIndex("Street"));
                Address =Co +" " + HouseNo+" " +Street;
            } while (c.moveToNext());//Move the cursor to the next row.
        }

        Sql ="select * from PaymentDetails where  ReceiptNo='" + ReciptNo + "'";
        db = new DatabaseHandler(getApplicationContext());

        c = db.GetData(Sql);

        if (c.moveToFirst()) {
            do {
                ReceiptNo = c.getString(c.getColumnIndex("ReceiptNo"));
                CardPStatus=c.getString(c.getColumnIndex("CardPStatus"));
                AmountPaid=c.getString(c.getColumnIndex("AmountPaid"));
                PaymentMode=c.getString(c.getColumnIndex("PaymentMode"));
                ChequeNo=c.getString(c.getColumnIndex("ChequeNo"));
                ChequeDate=c.getString(c.getColumnIndex("ChequeDate"));
                ContractNo=c.getString(c.getColumnIndex("ContractNo"));
                UserId=c.getString(c.getColumnIndex("UserId"));

            } while (c.moveToNext());//Move the cursor to the next row.

            TextView lbConsumerNo = (TextView)findViewById(R.id.lbConsumerNo);
            TextView lbReceiptNo = (TextView)findViewById(R.id.lbReceiptNo);
            TextView lbName = (TextView)findViewById(R.id.lbName);
            TextView lbAddress = (TextView)findViewById(R.id.lbAddress);
            TextView lbPayMode = (TextView)findViewById(R.id.lbPayMode) ;

            lbConsumerNo.setText(ConsumerNo);
            lbReceiptNo.setText(ReceiptNo);
            lbName.setText(Name);
            lbAddress.setText(Address);
            lbPayMode.setText(PaymentMode);





        }


    }

    public void onbtnPrintClicked(View v)
    {
        String ConsNo="";
        String ReciptNo="";
        TextView lbConsumerNo = (TextView)findViewById(R.id.lbConsumerNo);
        TextView lbReceiptNo = (TextView)findViewById(R.id.lbReceiptNo);
        ConsNo = lbConsumerNo.getText().toString();
        ReciptNo = lbReceiptNo.getText().toString();

        PrintBill( ConsNo, ReciptNo);
    }

}

