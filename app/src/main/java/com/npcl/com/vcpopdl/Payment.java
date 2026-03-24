package com.npcl.com.vcpopdl;

import android.app.Dialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.ColorDrawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Base64;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.eze.api.EzeAPI;
import com.google.gson.JsonObject;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.npcl.com.vcpopdl.api.APIClient;
import com.npcl.com.vcpopdl.api.APIClientBilling;
import com.npcl.com.vcpopdl.api.APIInterface;
import com.npcl.com.vcpopdl.api.APIInterfaceBilling;
import com.npcl.com.vcpopdl.model.InvoicesResponse;
import com.npcl.com.vcpopdl.model.QuickBillResponse;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

//import androidmads.library.qrgenearator.QRGContents;
//import androidmads.library.qrgenearator.QRGEncoder;

//import android.support.v4.content.ContextCompat;

public class Payment extends AppCompatActivity {
    private ImageView qrImage;
    private Bitmap bitmap;
   // private QRGEncoder qrgEncoder;
    private static final String TAG = "QRActivity";
    boolean stopWorker = false;
    private String QRAmount="";
    Thread workerThread;
    BluetoothAdapter btAdapter;
    BluetoothSocket mmSocket;
    BluetoothDevice mmDevice;
    OutputStream mmOutputStream;
    InputStream mmInputStream;
    int readBufferPosition = 0;
    String Cons = "";
    byte[] readBuffer = new byte[1024];
    private final int REQUEST_CODE_UPI = 10018;
    private final int REQUEST_CODE_CASH_TXN = 10009;
    private final int REQUEST_CODE_CASH_BACK_TXN = 10007;
    private final int REQUEST_CODE_CASH_AT_POS_TXN = 10008;
    private final int REQUEST_CODE_WALLET_TXN = 10003;
    private final int REQUEST_CODE_SALE_TXN = 10006;
    private final int REQUEST_CODE_INITIALIZE = 10001;
    private final int REQUEST_CODE_PREPARE = 10002;
    private final int REQUEST_CODE_CHEQUE_TXN = 10004;
    private final int REQUEST_CODE_SEARCH = 10010;
    private final int REQUEST_CODE_VOID = 10011;
    private final int REQUEST_CODE_ATTACH_SIGN = 10012;
    private final int REQUEST_CODE_UPDATE = 10013;
    private final int REQUEST_CODE_CLOSE = 10014;
    private final int REQUEST_CODE_GET_TXN_DETAIL = 10015;
    private final int REQUEST_CODE_GET_INCOMPLETE_TXN = 10016;
    private ImageView profile_pic,pro;
    private APIInterface apiInterface;
    private String  token;
    private ProgressDialog progressDialog;
    TextView lblCurrBillAmt;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_payment);
         apiInterface = APIClient.getClient(this);
         JsonObject jsonObject = new JsonObject();
         jsonObject.addProperty("userName","NPCLWEBTOKENPRD");
         jsonObject.addProperty("password","PRDNPCLWEBTOKEN#$1234");
         getHeaderToken(jsonObject);
        Bundle bundle = getIntent().getExtras();
        String text = bundle.getString("Data");
        String[] Temp = text.split("#");
        String User = Temp[1].toString();
        BindPayMode();
        EditText ConsNo;
        ConsNo = (EditText) findViewById(R.id.txtConsumerNo);
        String[] Row = text.split(":");
        EditText Userid = (EditText) findViewById(R.id.txtuser);
        Userid.setText(User);
        qrImage = findViewById(R.id.qr_image);
        mrodownload com = new mrodownload();
        //To be Remove
        //DatabaseHandler db1 = new DatabaseHandler(getApplicationContext());
        //String result = db1.onCreate();
        Button btnRefresh= findViewById(R.id.btnRefresh);

        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
              JsonObject jsonObject1 = new JsonObject();
              jsonObject1.addProperty("PartnerID","Npcl1234");
              jsonObject1.addProperty("ConsumerNo",Cons);
              Toast.makeText(Payment.this,Cons,Toast.LENGTH_LONG).show();
              refreshTotalAmount(jsonObject1);
            }
        });

        if (Row.length > 0) {
            RadioButton rbConsNo = (RadioButton) findViewById(R.id.rbConsNo);
            rbConsNo.setChecked(true);
            String ConsumerNo = Row[0].toString().replace(" #Welcome","");
            ConsNo.setText(ConsumerNo);
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            String Sql;
            rbConsNo = (RadioButton) findViewById(R.id.rbConsNo);
            RadioButton rbMeterNo = (RadioButton) findViewById(R.id.rbMeterNo);
            ConsNo = (EditText) findViewById(R.id.txtConsumerNo);
            String Fileter = ConsNo.getText().toString();
            TextView hidden_ConsAc =(TextView) findViewById(R.id.hidden_ConsAc);
            if (rbConsNo.isChecked())
                Sql = "select distinct Consumerno, Name, co,MeterNo,Poleno,(HouseNo ||','|| Street||',' ||City )address,Arrear,MobileNo,LastBillAmt, LastPmt,LastPmtDate,TotalAmt,Billmonthyear,TabId  from paymentData where Consumerno='" + Fileter + "' and UserId='" + User.replace("Welcome:::", "").toUpperCase() + "'";
            else
                Sql = "select distinct Consumerno, Name, co,MeterNo,Poleno,(HouseNo ||','|| Street||',' ||City )address,Arrear, LastPmt,LastPmtDate,LastBillAmt,MobileNo,TotalAmt,Billmonthyear,TabId  from paymentData where MeterNo='" + Fileter + "' and UserId='" + User.replace("Welcome:::", "").toUpperCase() + "'";



            String Mtrno = "";
            String Name = "";
            String address = "";
            String LastPmt = "";
            String Arrear = "";
            String TotalAmt = "";
            String LastBillAmt = "";
            String TabId="";
            String BillMonth="";
            String LastPmtDate="";
            try {
                Cursor c = db.GetData(Sql);

                if (c.moveToFirst()) {
                    do {
                        LastPmtDate =c.getString(c.getColumnIndex("LastPmtDate"));
                        Cons = c.getString(c.getColumnIndex("ConsumerNo"));
                        Mtrno = c.getString(c.getColumnIndex("MeterNo"));
                        Name = c.getString(c.getColumnIndex("Name"));
                        address = c.getString(c.getColumnIndex("address"));
                        Arrear = c.getString(c.getColumnIndex("Arrear")).replace(",","").trim();
                         LastBillAmt = c.getString(c.getColumnIndex("LastPmt")).replace(",","");
                        TotalAmt = c.getString(c.getColumnIndex("TotalAmt")).replace(",","");
                        LastPmt = c.getString(c.getColumnIndex("LastBillAmt")).replace(",","");
                        TabId = c.getString(c.getColumnIndex("TabId"));
                        BillMonth= c.getString(c.getColumnIndex("Billmonthyear"));
                        if (Arrear.equals(""))
                            Arrear="0";
                        //   TotalAmt = Double.toString( Double.parseDouble((LastPmt.trim()) )+ Double.parseDouble((Arrear.trim()) ));//+ Long.parseLong(Arrear));

                    } while (c.moveToNext());//Move the cursor to the next row.


                    TextView lbConsNo = (TextView) findViewById(R.id.lbConsumerNo);
                    TextView lbMeterNo = (TextView) findViewById(R.id.lbMeterNo);
                    TextView lbName = (TextView) findViewById(R.id.lbName);
                    TextView lbAddress = (TextView) findViewById(R.id.lbAddress);
//                    TextView lblastPaidAmt = (TextView) findViewById(R.id.lblastPaidAmt);
                    TextView lblArrear = (TextView) findViewById(R.id.lblArrear);
//                     lblCurrBillAmt = (TextView) findViewById(R.id.lblastPaidAmt);
//                    TextView lblPayable = (TextView) findViewById(R.id.lblPayable);
                    TextView lbBillMonth = (TextView) findViewById(R.id.lbBillMonth);

                    JsonObject d = getQuickBillDataFromAPI(this, Cons);

                    lbConsNo.setText(Cons);
                    lbMeterNo.setText(Mtrno);
                    lbName.setText(Name);
                    lbAddress.setText(address);
//                    lblastPaidAmt.setText(LastPmt);
                    lblArrear.setText(Arrear);
//                    lblPayable.setText(TotalAmt);
//                    lblastPaidAmt.setText(LastBillAmt  + " (" + LastPmtDate +")");
                    hidden_ConsAc.setText(TabId);
                    lbBillMonth.setText(BillMonth);
                }
            } catch (Exception ex) {
                String Error = ex.getMessage().toString();
            }
        }

    }

    private JsonObject getQuickBillDataFromAPI(Context context, String consumerNo) {

        APIInterfaceBilling apiService = APIClientBilling.getClient(context);
        try {
            lblCurrBillAmt = findViewById(R.id.lblCurrBillAmt);
            lblCurrBillAmt.setText("Fetching ...");
            Call<QuickBillResponse> call = apiService.getQuickBillData(consumerNo);

            call.enqueue(new Callback<QuickBillResponse>() {
                @Override
                public void onResponse(Call<QuickBillResponse> call, Response<QuickBillResponse> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        QuickBillResponse data = response.body();
                        QuickBillResponse.Data d = data.getData();

                        runOnUiThread(() -> {
                            lblCurrBillAmt.setText(d.getCurrentOutstandingAmount());
                        });
                    } else {
                        Log.e("testing123", "QuickBill API failed: " + response);
                    }
                }

                @Override
                public void onFailure(Call<QuickBillResponse> call, Throwable throwable) {

                }
            });

        }catch (Exception ex){
            Log.e("testing123", "QuickBill API exception: " + ex.toString());
            lblCurrBillAmt.setText("Failed to fetch");

        }

        TextView lblastPaidAmt = (TextView) findViewById(R.id.lblastPaidAmt);
        TextView lblPayable = (TextView) findViewById(R.id.lblPayable);
        try {
            lblPayable.setText("Fetching ...");
            lblastPaidAmt.setText("Fetching...");
            Call<InvoicesResponse> call = apiService.getInvoices(consumerNo);
            call.enqueue(new Callback<InvoicesResponse>() {
                @Override
                public void onResponse(Call<InvoicesResponse> call, Response<InvoicesResponse> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        InvoicesResponse.Data data = response.body().getData();

                        if (data != null && data.getResults() != null && !data.getResults().isEmpty()) {
                            List<InvoicesResponse.Data.Result> results = data.getResults();
                            String amountDue = results.get(0).getAmountDue();
                            runOnUiThread(() -> lblPayable.setText(amountDue));

                            if (results.size() > 1) {
                                String amountPaid = results.get(1).getAmountPaid();
                                String invoiceDateRaw = results.get(1).getInvoiceDate();

                                // Format the SAP-style date string
                                String formattedDate = formatSapDate(invoiceDateRaw);

                                String combined = amountPaid + " (" + formattedDate + ")";
                                runOnUiThread(() -> lblastPaidAmt.setText(combined));
                            } else {
                                Log.w("API", "No second invoice record found.");
                                lblastPaidAmt.setText("No last payments");
                            }

                        } else {
                            Log.e("API", "No invoice data found");
                            lblPayable.setText("Failed to Fetch");
                        }


                    }else {
                        Log.e("API Failure", "Invoices API failed: " + response);
                        lblPayable.setText("Failed to Fetch");
                    }
                }

                @Override
                public void onFailure(Call<InvoicesResponse> call, Throwable throwable) {

                }
            });
        } catch (Exception e) {
            Log.e("Api Fail", "GetInvoices API Exception" + e.toString());
            lblPayable.setText("Failed to Fetch");
            lblastPaidAmt.setText("Failed to Fetch");
        }

        return null;
    }

    private String formatSapDate(String sapDate) {
        if (sapDate == null || !sapDate.contains("(") || !sapDate.contains(")")) {
            return "";
        }

        try {
            // Extract timestamp from /Date(1758844800000)/
            String millisString = sapDate.substring(sapDate.indexOf('(') + 1, sapDate.indexOf(')'));
            long millis = Long.parseLong(millisString);

            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd-MMM-yyyy", java.util.Locale.getDefault());
            java.util.Date date = new java.util.Date(millis);

            return sdf.format(date);
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    private void getInvoicesFromAPI(Context context, String consumerNo){
        try {
            APIInterfaceBilling apiService = APIClientBilling.getClient(context);
        } catch (Exception e) {
            Log.e("Api Fail", "GetInvoices API Exception" + e.toString());
        }
    }

    public void onShowButtonClicked(View v) {
        try {

            if (AmountCheck() == false)
            {
                Toast.makeText(getApplicationContext(), "Your Two lakhs Rs. Limit Exceed, Please submit Cash First ",  Toast.LENGTH_SHORT).show();
                return;
            }

            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            String Sql;
            EditText ConsNo;
            RadioButton rbConsNo = (RadioButton) findViewById(R.id.rbConsNo);
            RadioButton rbMeterNo = (RadioButton) findViewById(R.id.rbMeterNo);
            ConsNo = (EditText) findViewById(R.id.txtConsumerNo);

            EditText Userid = (EditText) findViewById(R.id.txtuser);
            String User = Userid.getText().toString();

            String Fileter = ConsNo.getText().toString();
            if (rbConsNo.isChecked())
                Sql = "select distinct TabId, Consumerno, Name, co,MeterNo,Poleno,(HouseNo ||','|| Street||',' ||City )address,Arrear,MobileNo,LastBillAmt, LastPmt,LastPmtDate,TotalAmt,Billmonthyear,TabId  from paymentData where Consumerno='" + Fileter + "' and UserId='" + User.replace("Welcome:::", "").toUpperCase() + "'";
            else
                Sql = "select distinct TabId, Consumerno, Name, co,MeterNo,Poleno,(HouseNo ||','|| Street||',' ||City )address,Arrear, LastPmt,LastPmtDate,LastBillAmt,MobileNo,TotalAmt,Billmonthyear,TabId  from paymentData where MeterNo='" + Fileter + "' and UserId='" + User.replace("Welcome:::", "").toUpperCase() + "'";

            String Cons = "";
            String Mtrno = "";
            String Name = "";
            String address = "";
            String LastPmt = "";
            String Arrear = "";
            String TotalAmt = "";
            String LastBillAmt = "";
            String ConAc ="";
            String LastPmtDate="";
            Cursor c = db.GetData(Sql);

            if (c.moveToFirst()) {
                do {
                    LastPmtDate =c.getString(c.getColumnIndex("LastPmtDate"));
                    ConAc = c.getString(c.getColumnIndex("TabId"));
                    Cons = c.getString(c.getColumnIndex("ConsumerNo"));
                    Mtrno = c.getString(c.getColumnIndex("MeterNo"));
                    Name = c.getString(c.getColumnIndex("Name"));
                    address = c.getString(c.getColumnIndex("address"));
                    Arrear = c.getString(c.getColumnIndex("Arrear"));
                    LastPmt = c.getString(c.getColumnIndex("LastPmt"));
                    TotalAmt = c.getString(c.getColumnIndex("TotalAmt"));
                    LastBillAmt = c.getString(c.getColumnIndex("LastBillAmt"));
                } while (c.moveToNext());//Move the cursor to the next row.


                TextView hidden_ConsAc= (TextView) findViewById(R.id.hidden_ConsAc);
                TextView lbConsNo = (TextView) findViewById(R.id.lbConsumerNo);
                TextView lbMeterNo = (TextView) findViewById(R.id.lbMeterNo);
                TextView lbName = (TextView) findViewById(R.id.lbName);
                TextView lbAddress = (TextView) findViewById(R.id.lbAddress);
                TextView lblastPaidAmt = (TextView) findViewById(R.id.lblastPaidAmt);
                TextView lblArrear = (TextView) findViewById(R.id.lblArrear);
                TextView lblCurrBillAmt = (TextView) findViewById(R.id.lblCurrBillAmt);
                TextView lblPayable = (TextView) findViewById(R.id.lblPayable);

                hidden_ConsAc.setText(ConAc);
                lbConsNo.setText(Cons);
                lbMeterNo.setText(Mtrno);
                lbName.setText(Name);
                lbAddress.setText(address);
                lblastPaidAmt.setText(LastPmt);
                lblArrear.setText(Arrear);
                lblPayable.setText(TotalAmt);

                lblCurrBillAmt.setText(LastBillAmt  + " (" + LastPmtDate +")");

            }
            else
            {
                Toast.makeText(getApplicationContext(), "No Data Found",  Toast.LENGTH_SHORT).show();
                ClearData();
            }

        } catch (Exception ex) {


        }
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        String Result="";
        super.onActivityResult(requestCode, resultCode, intent);
        try {
            /*if (intent != null && intent.hasExtra("response")) {
                Toast.makeText(this, intent.getStringExtra("response"), Toast.LENGTH_LONG).show();
                Log.d("SampleAppLogs", intent.getStringExtra("response"));
            }*/

            String errorCode="";
            String errorMessage="";
            String TxnReferenceNo="";
            String TID="";
            String authCode="";
            String CardPmtStatus="";
            String PmtResultDesc ="";
            switch (requestCode) {
                case REQUEST_CODE_UPI:
                case REQUEST_CODE_CASH_TXN:
                case REQUEST_CODE_CASH_BACK_TXN:
                case REQUEST_CODE_CASH_AT_POS_TXN:
                case REQUEST_CODE_WALLET_TXN:
                case REQUEST_CODE_SALE_TXN:
                case REQUEST_CODE_INITIALIZE :

                    JSONObject response = new JSONObject();
                    if (resultCode == RESULT_OK) {
                        response = new JSONObject(intent.getStringExtra("response"));
                        response = response.getJSONObject("result");
                        response = response.getJSONObject("txn");
                        TxnReferenceNo = response.getString("txnId");
                        authCode = response.getString("authCode");
                        TID= response.getString("tid");
                        CardPmtStatus="Success";
                        PmtResultDesc =response.toString();

                        String txnid=TxnReferenceNo;
                        String AuthN=authCode;
                        String tid=TID;
                        String CrdSts=CardPmtStatus;
                        String Resuldesc=PmtResultDesc;

                        /// Save
                        EditText eduser = (EditText)findViewById(R.id.txtuser);
                        EditText txtMobileno = (EditText) findViewById(R.id.txtMobileno);
                        EditText txtPayAmt = (EditText) findViewById(R.id.txtPayAmt);
                        EditText txtChequeNo = (EditText) findViewById(R.id.txtChequeNo);
                        EditText txtChequeDate = (EditText) findViewById(R.id.txtChequeDate);
                        Spinner ddlPayMode = (Spinner) findViewById(R.id.ddlPayMode);
                        TextView hidden_ConsAc=(TextView)findViewById(R.id.hidden_ConsAc);
                        String PayMode = String.valueOf(ddlPayMode.getSelectedItem());
                        String LogUser = eduser.getText().toString().replace("Welcome:::","").toUpperCase();
                        String ConsAc= hidden_ConsAc.getText().toString();
                        TextView lbConsumerNo = (TextView) findViewById(R.id.lbConsumerNo);
                        String ConsumerNo= lbConsumerNo.getText().toString();
                        EditText  txtMobileno1 = (EditText) findViewById(R.id. txtMobileno);
                        String Mobileno=txtMobileno1.getText().toString();
                        String ChequeNo= txtChequeNo.getText().toString();
                        String ChequeDate =txtChequeDate.getText().toString();
                        TextView  lblPayable=(TextView) findViewById(R.id.lblPayable);
                        String PayBillAmt=lblPayable.getText().toString();
                        String AmtPiad=txtPayAmt.getText().toString();
                        String RcpNo="";
                        String PayDate="";
                        SimpleDateFormat dateFormatter = new SimpleDateFormat("ddMMyyyyhhmmss");
                        dateFormatter.setLenient(false);
                        Date today = new Date();
                        PayDate = dateFormatter.format(today);
                        RcpNo = LogUser +PayDate;
                        String DescPmt= Resuldesc;
                        String Strtid=tid;
                        if (ConsumerNo.contains("20")) {
                            if (SavePayData(ConsumerNo, ConsAc, txnid, PayMode, ChequeNo, ChequeDate, PayBillAmt, AmtPiad, "MobApp", LogUser, Mobileno, RcpNo, PayDate, Strtid, CrdSts, DescPmt, AuthN) == true) {

                                PrintBill(ConsumerNo, RcpNo);
                                Toast.makeText(getApplicationContext(), "Record Save Successfully..!", Toast.LENGTH_LONG).show();
                                ClearData();

                            } else {
                                Toast.makeText(getApplicationContext(), "Problen while Record Saving..!", Toast.LENGTH_LONG).show();

                            }
                        }
                        else
                        {
                            Toast.makeText(getApplicationContext(), "Please Click on Show Button than Save Record..!", Toast.LENGTH_LONG).show();

                        }


                        //// End save
                    } else if (resultCode == RESULT_CANCELED) {
                        response = new JSONObject(intent.getStringExtra("response"));
                        response = response.getJSONObject("error");
                        errorCode = response.getString("code");
                        errorMessage = response.getString("message");
                    }

                    break;

                default:
                    break;
            }

        } catch (Exception e) {

            e.printStackTrace();
        }

    }

    private void doInitializeEzeTap() {
        /**********************************************
         {
         "demoAppKey": "your demo app key",
         "prodAppKey": "your prod app key",
         "merchantName": "your merchant name",
         "userName": "your user name",
         "currencyCode": "INR",
         "appMode": "DEMO/PROD",
         "captureSignature": "true/false",
         "prepareDevice": "true/false"
         }
         **********************************************/
        JSONObject jsonRequest = new JSONObject();
        try {
            jsonRequest.put("demoAppKey", "cf741b34-ad65-4068-98b8-9f6556adde8e");
            jsonRequest.put("prodAppKey", "cfbb421f-0bc2-425d-89ac-431dcfb72a06");
            jsonRequest.put("merchantName", "NPCL");
            jsonRequest.put("userName", "7835018575");
            jsonRequest.put("currencyCode", "INR");
            jsonRequest.put("appMode", "PROD");
            jsonRequest.put("captureSignature", "true");
            jsonRequest.put("prepareDevice", "false");
            EzeAPI.initialize(this, REQUEST_CODE_INITIALIZE, jsonRequest);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
    private void doPrepareDeviceEzeTap() {
        EzeAPI.prepareDevice(this, REQUEST_CODE_PREPARE);
    }
    private void doSaleTxn(JSONObject jsonRequest) {
        EzeAPI.cardTransaction(this, REQUEST_CODE_SALE_TXN, jsonRequest);
    }
    private void doCloseEzetap() {
        EzeAPI.close(this, REQUEST_CODE_CLOSE);
    }
    public void CardPayment(String orderNumber ,String payableAmount, String customerName,String mobileNumber,String emailId)
    {
        try {


            doInitializeEzeTap();
            doPrepareDeviceEzeTap();
            JSONObject jsonRequest = new JSONObject();
            JSONObject jsonOptionalParams = new JSONObject();
            JSONObject jsonReferences = new JSONObject();
            JSONObject jsonCustomer = new JSONObject();
            jsonRequest.put("amount", payableAmount);

            // Building Customer Object(Optional)
            jsonCustomer.put("name", customerName);
            jsonCustomer.put("mobileNo", mobileNumber);
            jsonCustomer.put("email", emailId);
            jsonOptionalParams.put("customer", jsonCustomer);
            jsonReferences.put("reference1", orderNumber);

            JSONArray array = new JSONArray();
            array.put("addRef_xx1");
            array.put("addRef_xx2");
            jsonReferences.put("additionalReferences", array);

            jsonOptionalParams.put("references", jsonReferences);
            JSONObject addlData = new JSONObject();
            addlData.put("addl1", "addl1");
            addlData.put("addl2", "addl2");
            addlData.put("addl3", "addl3");
            JSONObject appData = new JSONObject();
            appData.put("app1", "app1");
            appData.put("app2", "app2");
            appData.put("app3", "app3");
            //jsonOptionalParams.put("appData", appData);

            // Building final optional params
            jsonRequest.put("options", jsonOptionalParams);
            final EditText emailIdED = (EditText) (EditText)findViewById(R.id.txtMobileno);
            InputMethodManager imm = (InputMethodManager) Payment.this.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(emailIdED.getWindowToken(), 0);
            jsonRequest.put("mode", "SALE");
            doSaleTxn(jsonRequest);




        }
        catch (Exception ex)
        {
            String Err= ex.getMessage().toString();
        }
    }

    public void PrintText(String Data)
    {

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
                        mmOutputStream.write(Data.getBytes());
                        stopWorker = true;
                        Thread.sleep(500);
                        mmOutputStream.close();
                        mmInputStream.close();
                        mmSocket.close();
                        break;
                    }

                }


            }
        }
        catch (Exception ex)
        {}
    }

    public void ClearData()
    {
        try
        {
            EditText txtConsumerNo = (EditText) findViewById(R.id.txtConsumerNo);
            TextView lbConsumerNo = (TextView) findViewById(R.id.lbConsumerNo);
            TextView lbMeterNo = (TextView) findViewById(R.id.lbMeterNo);
            TextView lbName = (TextView) findViewById(R.id.lbName);
            TextView lbAddress = (TextView) findViewById(R.id.lbAddress);
            TextView lblastPaidAmt = (TextView) findViewById(R.id.lblastPaidAmt);
            TextView lblArrear = (TextView) findViewById(R.id.lblArrear);
            TextView lblCurrBillAmt = (TextView) findViewById(R.id.lblCurrBillAmt);
            TextView hidden_ConsAc = (TextView) findViewById(R.id.hidden_ConsAc);
            TextView lblPayable = (TextView) findViewById(R.id.lblPayable);
            EditText txtMobileno = (EditText) findViewById(R.id.txtMobileno);
            EditText txtPayAmt = (EditText) findViewById(R.id.txtPayAmt);
            Spinner ddlPayMode =(Spinner) findViewById(R.id.ddlPayMode);
            txtConsumerNo.setText("");
            lbConsumerNo.setText("");
            lbMeterNo.setText("");
            lbName.setText("");
            lbAddress.setText("");
            lblArrear.setText("");
            lblastPaidAmt.setText("");
            lblCurrBillAmt.setText("");
            hidden_ConsAc.setText("");
            lblPayable.setText("");
            txtMobileno.setText("");
            txtPayAmt.setText("");
            ddlPayMode.setSelection(0);



        }
        catch ( Exception ex)
        {

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

            }



            msg ="*******************************\n";
            if (rcpt > 0)
            {
                msg+="Duplicate \n" ;
            }

            msg+="* Noida Power Company Limited * \n";
            msg+="*   Greater Noida-201310      *\n";
            msg+="*******************************\n";
            msg+="Receipt No.:" + ReceiptNo + "";
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
                InWord2 = AmtInWord.replace(InWord1,"") ;

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
                                        readBuffer[readBufferPosition++] = b; }
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
    public void onbtnSaveClicked1(View v) {

        try {



        }
        catch (Exception ex)
        {

            String Err=ex.getMessage().toString();
        }

    }

    public void onbtnPrintClicked(View v)
    {
        try {


            String txnid = "";
            String AuthN = "";
            String tid = "";
            String CrdSts = "";
            String Resuldesc = "";

            EditText eduser = (EditText) findViewById(R.id.txtuser);
            EditText txtMobileno = (EditText) findViewById(R.id.txtMobileno);
            EditText txtPayAmt = (EditText) findViewById(R.id.txtPayAmt);
            EditText txtChequeNo = (EditText) findViewById(R.id.txtChequeNo);
            EditText txtChequeDate = (EditText) findViewById(R.id.txtChequeDate);
            Spinner ddlPayMode = (Spinner) findViewById(R.id.ddlPayMode);
            TextView hidden_ConsAc = (TextView) findViewById(R.id.hidden_ConsAc);
            String PayMode = String.valueOf(ddlPayMode.getSelectedItem());
            String LogUser = eduser.getText().toString().replace("Welcome:::", "").toUpperCase();
            String ConsAc = hidden_ConsAc.getText().toString();


            if (PayMode.equals("--Select Pay Mode--")) {
                Toast.makeText(getApplicationContext(), "Please Select  Pay Mode.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (txtMobileno.getText().length() != 10) {
                Toast.makeText(getApplicationContext(), "Please Enter Valid Mobile No.", Toast.LENGTH_SHORT).show();
                return;
            }

            if (txtPayAmt.getText().length() == 0) {
                Toast.makeText(getApplicationContext(), "Please Enter Amount payable", Toast.LENGTH_SHORT).show();
                return;
            }

            if (PayMode.equals("Cheque")) {
                if (txtChequeNo.getText().length() == 0) {
                    Toast.makeText(getApplicationContext(), "Please Enter Valid Cheque No.", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (txtChequeDate.getText().length() == 0) {
                    Toast.makeText(getApplicationContext(), "Please Enter Cheque Date", Toast.LENGTH_SHORT).show();
                    return;
                }

            }
            if (PayMode.equals("Card")) {

                EditText eduser1 = (EditText) findViewById(R.id.txtuser);
                EditText txtMobileno1 = (EditText) findViewById(R.id.txtMobileno);
                EditText txtPayAmt1 = (EditText) findViewById(R.id.txtPayAmt);
                TextView Name = (TextView) findViewById(R.id.lbName);
                String LogUser1 = eduser1.getText().toString().replace("Welcome:::", "").toUpperCase();
                String Mobileno1 = txtMobileno1.getText().toString();
                String PayAmt1 = txtPayAmt1.getText().toString();
                String Name1 = Name.getText().toString();
                SimpleDateFormat dateFormatter = new SimpleDateFormat("ddMMyyyyhhmmss");
                dateFormatter.setLenient(false);
                Date today1 = new Date();
                String PayDate1 = dateFormatter.format(today1);
                String RcpNo1 = LogUser1 + PayDate1;

                CardPayment(RcpNo1, PayAmt1, Name1, Mobileno1, "npclapp@gmail.com");
                return;
            }


            if (CheckPayDataNAmount(txtPayAmt.getText().toString(), PayMode) == true) {
                TextView lbConsumerNo = (TextView) findViewById(R.id.lbConsumerNo);
                String ConsumerNo = lbConsumerNo.getText().toString();
                EditText txtMobileno1 = (EditText) findViewById(R.id.txtMobileno);
                String Mobileno = txtMobileno1.getText().toString();
                String ChequeNo = txtChequeNo.getText().toString();
                String ChequeDate = txtChequeDate.getText().toString();
                TextView lblPayable = (TextView) findViewById(R.id.lblPayable);
                String PayBillAmt = lblPayable.getText().toString();
                String AmtPiad = txtPayAmt.getText().toString();

                String RcpNo = "";
                String PayDate = "";

                SimpleDateFormat dateFormatter = new SimpleDateFormat("ddMMyyyyhhmmss");
                dateFormatter.setLenient(false);
                Date today = new Date();
                PayDate = dateFormatter.format(today);
                TextView lbreceiptno = (TextView) findViewById(R.id.txtreceiptno);
                RcpNo = lbreceiptno.getText().toString();

                String DescPmt = Resuldesc;
                String Strtid = tid;


//txtreceiptno
                String Sql = "insert into PaymentDetails (ConsumerNo, ContractAc, ContractNo,PaymentMode, ChequeNo, ChequeDate, PayableAmount,AmountPaid,PaymentDate, TabId, UserId,UploadFlag,MobileNO,TransferDateTime,ReceiptNo,NoOfReceipt,TID,CardPStatus,PaymentResult,AuthNumber)  Values ('" + ConsumerNo + "','" + ConsAc + "' , '" + txnid + "' , '" + PayMode + "', '" + ChequeNo + "','" + ChequeDate + "','" + PayBillAmt + "','" + AmtPiad + "',  '" + PayDate + "','MobApp' ,'" + LogUser + "','1','" + Mobileno + "',0,'" + RcpNo + "',0, '" + Strtid + "','" + CrdSts + "','" + DescPmt + "','" + AuthN + "')";


                DatabaseHandler Obj = new DatabaseHandler(getApplicationContext());
                Obj.ExecuteQry(Sql);


                PrintBill(ConsumerNo, RcpNo);
                // Toast.makeText(getApplicationContext(), "Record Save Successfully..!", Toast.LENGTH_LONG).show();
                ClearData();

            }
        }
        catch (Exception ex)
        {

            String Err=ex.getMessage().toString();
        }

    }


    public void onbtnSaveClicked(View v) {

        try {

            // CardPayment();




            //  PrintBill("abc","ASHOKN20180425121212");
            String txnid="";
            String AuthN="";
            String tid="";
            String CrdSts="";
            String Resuldesc="";

            EditText eduser = (EditText)findViewById(R.id.txtuser);
            EditText txtMobileno = (EditText) findViewById(R.id.txtMobileno);
            EditText txtPayAmt = (EditText) findViewById(R.id.txtPayAmt);
            EditText txtChequeNo = (EditText) findViewById(R.id.txtChequeNo);
            EditText txtChequeDate = (EditText) findViewById(R.id.txtChequeDate);
            Spinner ddlPayMode = (Spinner) findViewById(R.id.ddlPayMode);
            TextView hidden_ConsAc=(TextView)findViewById(R.id.hidden_ConsAc);
            String PayMode = String.valueOf(ddlPayMode.getSelectedItem());
            String LogUser = eduser.getText().toString().replace("Welcome:::","").toUpperCase();
            String ConsAc= hidden_ConsAc.getText().toString();


            if (PayMode.equals("--Select Pay Mode--"))
            {
                Toast.makeText(getApplicationContext(), "Please Select  Pay Mode.", Toast.LENGTH_SHORT).show();
                return;
            }

            if(PayMode.equals("QRPay")){
                Button btn = (Button) findViewById(R.id.btnSave);
                btn.setEnabled(false);
            }
            else{
                if (txtMobileno.getText().length()!=10)
                {
                    Toast.makeText(getApplicationContext(), "Please Enter Valid Mobile No.", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (txtPayAmt.getText().length()==0)
                {
                    Toast.makeText(getApplicationContext(), "Please Enter Amount payable", Toast.LENGTH_SHORT).show();
                    return;
                }
                Double amt_paying= Double.parseDouble(txtPayAmt.getText().toString());
                if (amt_paying<1){

                    Toast.makeText(getApplicationContext(), "Payable Amount should not be less then 1", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (PayMode.equals("Cheque"))
                {
                    if (txtChequeNo.getText().length()==0)
                    {
                        Toast.makeText(getApplicationContext(), "Please Enter Valid Cheque No.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (txtChequeDate.getText().length()==0)
                    {
                        Toast.makeText(getApplicationContext(), "Please Enter Cheque Date", Toast.LENGTH_SHORT).show();
                        return;
                    }

                }
                if (PayMode.equals("Card")) {

                    EditText eduser1 = (EditText)findViewById(R.id.txtuser);
                    EditText txtMobileno1 = (EditText) findViewById(R.id.txtMobileno);
                    EditText txtPayAmt1 = (EditText) findViewById(R.id.txtPayAmt);
                    TextView  Name = (TextView) findViewById(R.id.lbName);
                    String LogUser1 = eduser1.getText().toString().replace("Welcome:::","").toUpperCase();
                    String Mobileno1= txtMobileno1.getText().toString();
                    String PayAmt1 = txtPayAmt1.getText().toString();
                    String Name1 =Name.getText().toString();
                    SimpleDateFormat dateFormatter = new SimpleDateFormat("ddMMyyyyhhmmss");
                    dateFormatter.setLenient(false);
                    Date today1 = new Date();
                    String PayDate1 = dateFormatter.format(today1);
                    String RcpNo1 = LogUser1 +PayDate1;

                    CardPayment(RcpNo1,PayAmt1, Name1 ,Mobileno1,"npclapp@gmail.com");
                    return;
                }
            }

            if ( CheckPayDataNAmount(txtPayAmt.getText().toString(), PayMode)== true)
            {
                TextView lbConsumerNo = (TextView) findViewById(R.id.lbConsumerNo);
                String ConsumerNo= lbConsumerNo.getText().toString();
                EditText  txtMobileno1 = (EditText) findViewById(R.id. txtMobileno);
                String Mobileno=txtMobileno1.getText().toString();
                String ChequeNo= txtChequeNo.getText().toString();
                String ChequeDate =txtChequeDate.getText().toString();
                TextView  lblPayable=(TextView) findViewById(R.id.lblPayable);
                String PayBillAmt=lblPayable.getText().toString();
                String AmtPiad=txtPayAmt.getText().toString();

                String RcpNo="";
                String PayDate="";

                SimpleDateFormat dateFormatter = new SimpleDateFormat("ddMMyyyyhhmmss");
                dateFormatter.setLenient(false);
                Date today = new Date();
                PayDate = dateFormatter.format(today);
                RcpNo = LogUser +PayDate;
                String DescPmt= Resuldesc;
                String Strtid=tid;

                TextView lbreceiptno = (TextView) findViewById(R.id.txtreceiptno);
                lbreceiptno.setText(RcpNo);
//txtreceiptno
                if (ConsumerNo.contains("20")) {
                    if(PayMode.equals("QRPay")){
                        if(LastPaymentCheck(ConsumerNo)){

                        }
                    }
                    else{
                        SavePayData(ConsumerNo, ConsAc, txnid, PayMode, ChequeNo, ChequeDate, PayBillAmt, AmtPiad, "MobApp", LogUser, Mobileno, RcpNo, PayDate, Strtid, CrdSts, DescPmt, AuthN) ;

                    }




                }
                else
                {
                    Toast.makeText(getApplicationContext(), "Please Click on Show Button than Save Record..!", Toast.LENGTH_LONG).show();

                }


            }
        }
        catch (Exception ex)
        {

            String Err=ex.getMessage().toString();
        }

    }

    public void onbtnGenerateQR(View v){

        // CreateLog(QRCode);
        CreateLog("QR:Start");
        // below line is for getting
        // the windowmanager service.

        TextView lbConsumerNo = (TextView) findViewById(R.id.lbConsumerNo);
        String ConsumerNo= lbConsumerNo.getText().toString();
        TextView  lblPayable=(TextView) findViewById(R.id.lblPayable);
        String PayBillAmt=lblPayable.getText().toString();
        CreateLog(ConsumerNo+" - "+PayBillAmt);

        AsyncTaskRunner1 task = new AsyncTaskRunner1();

        task.execute(new String[] { "Method", ConsumerNo, PayBillAmt});

        EditText txtMobileno = (EditText) findViewById(R.id.txtMobileno);
        EditText txtPayAmt = (EditText) findViewById(R.id.txtPayAmt);
        EditText txtChequeNo = (EditText) findViewById(R.id.txtChequeNo);
        EditText txtChequeDate = (EditText) findViewById(R.id.txtChequeDate);
        txtMobileno.setEnabled(false);
        txtPayAmt.setEnabled(false);
        txtChequeNo.setEnabled(false);
        txtChequeDate.setEnabled(false);

        Spinner ddlPayMode = (Spinner) findViewById(R.id.ddlPayMode);
        ddlPayMode.setSelection(2);
        ddlPayMode.setEnabled(false);

        CreateLog("QRCodeGenrate:END");

     /*  WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // initializing a variable for default display.
        Display display = manager.getDefaultDisplay();

        // creating a variable for point which
        // is to be displayed in QR Code.
        Point point = new Point();
        display.getSize(point);

        // getting width and
        // height of a point
        int width = point.x;
        int height = point.y;

        // generating dimension from width and height.
        int dimen = width < height ? width : height;
        dimen = dimen * 3 / 4;
    //Toast.makeText(getApplicationContext(), dimen, Toast.LENGTH_LONG).show();
    QRGEncoder qrgEncoder = new QRGEncoder("Hello", null, QRGContents.Type.TEXT, dimen);
    try {
        // Getting QR-Code as Bitmap
        bitmap = qrgEncoder.getBitmap();
        // Setting Bitmap to ImageView
        imageQR.setImageBitmap(bitmap);
    } catch (Exception e) {
        CreateLog("QR:Error"+e.getMessage().toString());
       // Log.v(TAG, e.toString());
    } */
        CreateLog("QR:End");
        // setting this dimensions inside our qr code
        // encoder to generate our qr code.
   /* QRGEncoder qrgEncoder = new QRGEncoder("Hello", null, QRGContents.Type.TEXT, dimen);
    // getting our qrcode in the form of bitmap.
    //  Bitmap bitmap = qrgEncoder.encodeAsBitmap();
    Bitmap bitmap= qrgEncoder.getBitmap();

    // the bitmap is set inside our image
    // view using .setimagebitmap method.
    // qrCodeIV.setImageBitmap(bitmap);
    CreateLog("QR:"+bitmap.toString());
*/
   /*
  Dialog builder = new Dialog(Payment.this);
        builder.requestWindowFeature(Window.FEATURE_NO_TITLE);
        builder.getWindow().setBackgroundDrawable(
                new ColorDrawable(android.graphics.Color.TRANSPARENT));
        builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialogInterface) {
                //nothing;
            }
        });
     //   int a=v.getId();
     //   if(R.id.go_pro==a)
      //  {
   // Uri uri = Uri.parse("android.resource://" + getPackageName() + "/src/main/res/drawable/sync.jpg");    //path of image
    //File imageFile = new File(Environment.getExternalStorageDirectory() + "/.vcpsysdata/" + "2000014190_20220727_1319.jpg");
    //String LoaclFullPath = imageFile.getPath();

  //  Uri uri = Uri.parse(LoaclFullPath);    //path of image
   // Toast.makeText(getApplicationContext(), "hi ", Toast.LENGTH_LONG).show();
   // CreateLog("QR:"+uri.toString());
    //  } //drawable/sync.jpg
       // else if(R.id.img_View==a) {
          //  uri = Uri.parse("android.resource://" + getPackageName() + "/drawable/profile"); //path of image
      //  }
        ImageView imageView = new ImageView(Payment.this);
       // imageView.setImageURI(uri);                //set the image in dialog popup
       imageView.setImageBitmap(bitmap);
        //below code fullfil the requirement of xml layout file for dialoge popup

        builder.addContentView(imageView, new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        builder.show();
       // return false;
*/

    }

    public boolean LastPaymentCheck(String ConsumerNo){
        Lastpaycheck task = new Lastpaycheck();

        task.execute(new String[] {  ConsumerNo});

        return true;
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

            File f = new File(rootPath + "PayData_" +SysDate +".Log");
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


    public boolean SavePayData(String ConsumerNo, String ContractAc, String ContractNo, String PaymentMode, String ChequeNo, String ChequeDate, String PayableAmount, String AmountPaid, String TabId, String UserId, String MobNo, String rcptNo, String pmtdt, String strtid, String crdststs, String descPmt, String authCd)
    {
        Boolean IsSave = false;
        try {

            AsyncTaskRunner task = new AsyncTaskRunner();

            task.execute(new String[] { AmountPaid, authCd, ChequeDate, ChequeNo, ConsumerNo, ContractAc, ContractNo, descPmt, crdststs, UserId.toUpperCase(), MobNo, PayableAmount, pmtdt, PaymentMode, rcptNo, TabId, strtid});
            return IsSave;
        }
        catch(Exception ex){
            return IsSave;
        }
    }

    private class AsyncTaskRunner extends AsyncTask<String, String, String> {
        // AmountPaid, authCd, ChequeDate, ChequeNo, ConsumerNo, ContractAc, ContractNo, descPmt, crdststs,
// UserId.toUpperCase(), MobNo, "0", PayableAmount, pmtdt, PaymentMode, rcptNo, TabId, strtid
        private String resp;
        ProgressDialog progressDialog;

        protected String doInBackground(String... params) {
            try {
                String AmountPaid = params[0];
                String authCd = params[1];
                String ChequeDate = params[2];
                String ChequeNo = params[3];
                String ConsumerNo = params[4];
                String ContractAc = params[5];
                String ContractNo = params[6];
                String descPmt = params[7];
                String crdststs = params[8];
                String UserId = params[9];
                String MobNo = params[10];
                String PayableAmount = params[11];
                String pmtdt = params[12];
                String PaymentMode = params[13];
                String rcptNo = params[14];
                String TabId = params[15];
                String strtid = params[16];

                UploadPaymentData com = new UploadPaymentData();
                String Result =com.SetPayment("UpdaloadPaymentDataOnline", AmountPaid, authCd, ChequeDate, ChequeNo, ConsumerNo, ContractAc, ContractNo, descPmt, crdststs, UserId.toUpperCase(), MobNo, "0", PayableAmount, pmtdt, PaymentMode, rcptNo, TabId, strtid, getApplicationContext());
                return Result;
            } catch (Exception ex) {
                return "";
            }
        }

        protected void onPostExecute(String result) {
            // execution of result of Long time consuming operation
            progressDialog.dismiss();
            if (result.equals("1")) {
                // Button myButton=(Button) findViewById(R.id.btnPrint);
                // myButton.setEnabled(true);

                String txnid = "";
                String AuthN = "";
                String tid = "";
                String CrdSts = "";
                String Resuldesc = "";

                EditText eduser = (EditText) findViewById(R.id.txtuser);
                EditText txtMobileno = (EditText) findViewById(R.id.txtMobileno);
                EditText txtPayAmt = (EditText) findViewById(R.id.txtPayAmt);
                EditText txtChequeNo = (EditText) findViewById(R.id.txtChequeNo);
                EditText txtChequeDate = (EditText) findViewById(R.id.txtChequeDate);
                Spinner ddlPayMode = (Spinner) findViewById(R.id.ddlPayMode);
                TextView hidden_ConsAc = (TextView) findViewById(R.id.hidden_ConsAc);
                String PayMode = String.valueOf(ddlPayMode.getSelectedItem());
                String LogUser = eduser.getText().toString().replace("Welcome:::", "").toUpperCase();
                String ConsAc = hidden_ConsAc.getText().toString();
                TextView lbConsumerNo = (TextView) findViewById(R.id.lbConsumerNo);
                String ConsumerNo = lbConsumerNo.getText().toString();
                EditText txtMobileno1 = (EditText) findViewById(R.id.txtMobileno);
                String Mobileno = txtMobileno1.getText().toString();
                String ChequeNo = txtChequeNo.getText().toString();
                String ChequeDate = txtChequeDate.getText().toString();
                TextView lblPayable = (TextView) findViewById(R.id.lblPayable);
                String PayBillAmt = lblPayable.getText().toString();
                String AmtPiad = txtPayAmt.getText().toString();

                String RcpNo = "";
                String PayDate = "";

                SimpleDateFormat dateFormatter = new SimpleDateFormat("ddMMyyyyhhmmss");
                dateFormatter.setLenient(false);
                Date today = new Date();
                PayDate = dateFormatter.format(today);
                TextView lbreceiptno = (TextView) findViewById(R.id.txtreceiptno);
                RcpNo = lbreceiptno.getText().toString();

                String DescPmt = Resuldesc;
                String Strtid = tid;
                //QRAmount
                if (PayMode.equals("QRPay")){
                    AmtPiad= QRAmount;

                }


                String Sql = "insert into PaymentDetails (ConsumerNo, ContractAc, ContractNo,PaymentMode, ChequeNo, ChequeDate, PayableAmount,AmountPaid,PaymentDate, TabId, UserId,UploadFlag,MobileNO,TransferDateTime,ReceiptNo,NoOfReceipt,TID,CardPStatus,PaymentResult,AuthNumber)  Values ('" + ConsumerNo + "','" + ConsAc + "' , '" + txnid + "' , '" + PayMode + "', '" + ChequeNo + "','" + ChequeDate + "','" + PayBillAmt + "','" + AmtPiad + "',  '" + PayDate + "','MobApp' ,'" + LogUser + "','1','" + Mobileno + "',0,'" + RcpNo + "',0, '" + Strtid + "','" + CrdSts + "','" + DescPmt + "','" + AuthN + "')";

                DatabaseHandler Obj = new DatabaseHandler(getApplicationContext());
                Obj.ExecuteQry(Sql);
                CreateLog(Sql);
                PrintBill(ConsumerNo, RcpNo);
                Toast.makeText(getApplicationContext(), "Payment Data Uploaded, Please Take Print ", Toast.LENGTH_LONG).show();
                ClearData();

            }

        }

        protected void onPreExecute() {
            progressDialog = ProgressDialog.show(Payment.this,
                    "ProgressDialog",
                    "Wait payment Data Uploading");
        }



        protected void onProgressUpdate(String txt){


        }

    }


    private class AsyncTaskRunner1 extends AsyncTask<String, String, String> {
        // AmountPaid, authCd, ChequeDate, ChequeNo, ConsumerNo, ContractAc, ContractNo, descPmt, crdststs,
// UserId.toUpperCase(), MobNo, "0", PayableAmount, pmtdt, PaymentMode, rcptNo, TabId, strtid
        private String resp;
        ProgressDialog progressDialog;

        protected String doInBackground(String... params) {
            try {
                String PaymentwithQR = params[0];
                String ConsumerNo = params[1];
                String PayBillAmt = params[2];
                CreateLog(PaymentwithQR+" - "+" - "+ConsumerNo+" - "+PayBillAmt);

                UploadPaymentData com = new UploadPaymentData();
                String QRCode=  com.QRPayment("PaymentwithQR",ConsumerNo,PayBillAmt);
                CreateLog("QRCode Asyn"+QRCode);

                return QRCode;
            } catch (Exception ex) {
                return "";
            }
        }

        protected void onPostExecute(String QRCode) {
            // execution of result of Long time consuming operation
            progressDialog.dismiss();
            if (QRCode.length()>100) {
                WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
                Display display = manager.getDefaultDisplay();
                Point point = new Point();
                display.getSize(point);
                int width = point.x;
                int height = point.y;
                int smallerDimension = width < height ? width : height;
                smallerDimension = smallerDimension * 3 / 4;
                // String codd="000201010211021644038478003110020415522024078003110061661000307800311030822CITI0100000CITIHDF1111111531150100002005826490010A0000005240131billdeskbqr.noidapower@hdfcbank27320010A0000005240114STQ2000012345X520449005303356540520.005802IN5904NPCL6006MUMBAI610640005362260510200001234507087800311063041D37";
               /* qrgEncoder = new QRGEncoder(
                        QRCode, null,
                        QRGContents.Type.TEXT,
                        smallerDimension);
                qrgEncoder.setColorBlack(Color.BLACK);
                qrgEncoder.setColorWhite(Color.WHITE);

                try {
                    bitmap = qrgEncoder.getBitmap();
                    //qrImage.setImageBitmap(bitmap);
                } catch (Exception e) {
                    CreateLog("QR:Error"+e.getMessage());

                    //e.printStackTrace();
                }*/

                bitmap =  generateQRCode(QRCode);


                Dialog builder = new Dialog(Payment.this);
                builder.requestWindowFeature(Window.FEATURE_NO_TITLE);
                builder.getWindow().setBackgroundDrawable(
                        new ColorDrawable(android.graphics.Color.TRANSPARENT));
                builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialogInterface) {
                        //nothing;
                    }
                });

                ImageView imageView = new ImageView(Payment.this);
                imageView.setImageBitmap(bitmap);
                builder.addContentView(imageView, new RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                builder.show();


                CreateLog("Stop");

            }
            else{
                Toast.makeText(getApplicationContext(), QRCode, Toast.LENGTH_LONG).show();
            }

        }

        protected void onPreExecute() {
            progressDialog = ProgressDialog.show(Payment.this,
                    "ProgressDialog",
                    "Wait for QR Code");
        }



        protected void onProgressUpdate(String txt){


        }

    }

    private class Lastpaycheck extends AsyncTask<String, String, String> {
        private String resp;
        ProgressDialog progressDialog;

        protected String doInBackground(String... params) {
            try {

                String ConsumerNo = params[0];

                CreateLog("Last pay:"+ConsumerNo);

                UploadPaymentData com = new UploadPaymentData();
                String data=  com.LastPaymentStatus("lastPaymentserviceQLT",ConsumerNo);
                CreateLog("Last Pay Asyn"+data);

                return data;
            } catch (Exception ex) {
                return "";
            }
        }

        protected void onPostExecute(String data) {
            // execution of result of Long time consuming operation
            progressDialog.dismiss();
            if (data.length()>10) {
                TextView lbConsumerNo = (TextView) findViewById(R.id.lbConsumerNo);
                String ConsumerNo = lbConsumerNo.getText().toString();
                Date date = new Date();
                String modifiedDate= new SimpleDateFormat("dd.MM.yyyy").format(date);
                CreateLog("modifiedDate"+modifiedDate);

                String[] lPaydata=data.split("~");
                String BPno=lPaydata[0];
                String datetime=  lPaydata[1];
                String [] datee=datetime.split(" ");

                String amount=  lPaydata[2];
                QRAmount=amount;
                if(modifiedDate.equals(datee[0])&& ConsumerNo.equals(BPno)){

                    String txnid="";
                    String AuthN="";
                    String tid="";
                    String CrdSts="";
                    String Resuldesc="";

                    EditText eduser = (EditText)findViewById(R.id.txtuser);
                    EditText txtMobileno = (EditText) findViewById(R.id.txtMobileno);
                    EditText txtPayAmt = (EditText) findViewById(R.id.txtPayAmt);
                    EditText txtChequeNo = (EditText) findViewById(R.id.txtChequeNo);
                    EditText txtChequeDate = (EditText) findViewById(R.id.txtChequeDate);
                    Spinner ddlPayMode = (Spinner) findViewById(R.id.ddlPayMode);
                    TextView hidden_ConsAc=(TextView)findViewById(R.id.hidden_ConsAc);
                    String PayMode = String.valueOf(ddlPayMode.getSelectedItem());
                    String LogUser = eduser.getText().toString().replace("Welcome:::","").toUpperCase();
                    String ConsAc= hidden_ConsAc.getText().toString();


                    EditText txtMobileno1 = (EditText) findViewById(R.id.txtMobileno);
                    String Mobileno = txtMobileno1.getText().toString();
                    String ChequeNo = txtChequeNo.getText().toString();
                    String ChequeDate = txtChequeDate.getText().toString();
                    TextView lblPayable = (TextView) findViewById(R.id.lblPayable);
                    String PayBillAmt = lblPayable.getText().toString();
                    String AmtPiad = txtPayAmt.getText().toString();

                    String RcpNo = "";
                    String PayDate = "";

                    SimpleDateFormat dateFormatter = new SimpleDateFormat("ddMMyyyyhhmmss");
                    dateFormatter.setLenient(false);
                    Date today = new Date();
                    PayDate = dateFormatter.format(today);
                    RcpNo = LogUser + PayDate;
                    String DescPmt = Resuldesc;
                    String Strtid = tid;

                    TextView lbreceiptno = (TextView) findViewById(R.id.txtreceiptno);
                    lbreceiptno.setText(RcpNo);
                    //AmtPiad=amount;

                    CreateLog("modifiedDate"+modifiedDate);
                    SavePayData(ConsumerNo, ConsAc, txnid, PayMode, ChequeNo, ChequeDate, PayBillAmt, amount, "MobApp", LogUser, Mobileno, RcpNo, PayDate, Strtid, CrdSts, DescPmt, AuthN) ;

                    //  Toast.makeText(getApplicationContext(), "save suceessfully", Toast.LENGTH_LONG).show();
                }
                else {
                    Toast.makeText(getApplicationContext(), "Payment NOT Received", Toast.LENGTH_LONG).show();
                }
            }
            else{
                Toast.makeText(getApplicationContext(), "Error", Toast.LENGTH_LONG).show();
            }

        }

        protected void onPreExecute() {
            progressDialog = ProgressDialog.show(Payment.this,
                    "ProgressDialog",
                    "Wait for Payment Verify");
        }



        protected void onProgressUpdate(String txt){


        }

    }


    public void BindPayMode() {
        try {

            List<String> list = new ArrayList<String>();
            list.add("--Select Pay Mode--");
            list.add("Cash");
            list.add("QRPay");
            list.add("Cheque");
            list.add("Card");

            Spinner ddlPayMode = (Spinner) findViewById(R.id.ddlPayMode);
            ArrayAdapter<String> dataAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, list);
            dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            ddlPayMode.setAdapter(dataAdapter);

        } catch (Exception ex) {
            String Msg = ex.getMessage().toString();

        }
    }

    public long DayBetween(String DLDate) {
        long Days = 0;
        try {
            Calendar calendar = Calendar.getInstance();
            SimpleDateFormat mdformat = new SimpleDateFormat("yyyy/MM/dd ");
            String CurrentDate = mdformat.format(calendar.getTime());
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd");
            Date date1 = new SimpleDateFormat("yyyy/MM/dd").parse(DLDate);
            Date date2 = new SimpleDateFormat("yyyy/MM/dd").parse(CurrentDate);

            long diff = date2.getTime() - date1.getTime();
            long seconds = diff / 1000;
            long minutes = seconds / 60;
            long hours = minutes / 60;
            long days = hours / 24;
            Days = days;
            return Days;
        } catch (Exception ex) {
            return Days;
        }

    }

    public boolean CheckPayDataNAmount(String Amount, String PayMode) {
        Boolean Result = false;
        try {

            // Date Check
            int DayCount = 0;
            String DataDLDate = "";
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            String Sql = "select  substr(EntryDate,1,4) || '/' || substr(EntryDate,5,2) || '/' || substr(EntryDate,7,2) EntryDate  from paymentData order by EntryDate desc LIMIT 1";
            Cursor c = db.GetData(Sql);
            if (c.moveToFirst()) {
                do {
                    DataDLDate = c.getString(c.getColumnIndex("EntryDate"));
                } while (c.moveToNext());//Move the cursor to the next row.

                long Days = DayBetween(DataDLDate);
                Date date1 = new SimpleDateFormat("yyyy/MM/dd").parse(DataDLDate);
                if (Days > 5) {
                    for (int i = 0; i < 5; i++) {
                        Calendar cal = Calendar.getInstance();
                        cal.setTime(date1);
                        cal.add(Calendar.DATE, i);
                        SimpleDateFormat mdformat = new SimpleDateFormat("yyyy/MM/dd ");
                        String CurrentDate = mdformat.format(cal.getTime());
                        Date date2 = new SimpleDateFormat("yyyy/MM/dd").parse(CurrentDate);
                        SimpleDateFormat outFormat = new SimpleDateFormat("EEEE");
                        String goal = outFormat.format(date2);
                        if (goal.equals("Saturday"))
                            Days = Days - 1;
                        if (goal.equals("Sunday"))
                            Days = Days - 1;

                    }
                    if (Days > 5) {
                        Toast.makeText(getApplicationContext(), "No more payment collection is allowed as 5 days limit has exceeded. Download updated payment data!!", Toast.LENGTH_SHORT).show();
                        return false;
                    }

                }

            }
            // Amount Check

            String AmountPaid = "";
            if (PayMode.equals("Cash")) {
                Sql = "select ifnull(sum(AmountPaid),0) AmountPaid from PaymentDetails where PaymentMode='Cash'";
                c = db.GetData(Sql);
                if (c.moveToFirst()) {
                    do {
                        AmountPaid = c.getString(c.getColumnIndex("AmountPaid"));
                    } while (c.moveToNext());
                }
                long TotalAmoutPaid = Long.parseLong(AmountPaid) + Long.parseLong(Amount);
                if (TotalAmoutPaid > 200000) {
                    Toast.makeText(getApplicationContext(), "This amount is not allowed as it exceedes maximum cash limit!!", Toast.LENGTH_SHORT).show();
                    return false;
                }

            }


            return true;
        } catch (Exception ex) {
            return false;
        }

    }

    public boolean IsDataValid()
    {
        boolean IsValid= false;
        try
        {
            String Sql="select EntryDate from PaymentData";

            return IsValid;
        }
        catch (Exception ex)
        {
            return IsValid;
        }
    }

    public boolean AmountCheck()
    {
        boolean IsClear = false;
        double AmountPaidVal =0;
        String AmountPaid="0";
        try
        {
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            String Sql ="select ifnull(sum(AmountPaid),0) AmountPaid from PaymentDetails where PaymentMode='Cash'";
            Cursor c = db.GetData(Sql);
            int i= c.getCount();
            if (c.getCount() >0) {
                if (c.moveToFirst()) {
                    do {
                        AmountPaid = c.getString(c.getColumnIndex("AmountPaid"));

                    } while (c.moveToNext());//Move the cursor to the next row.
                    AmountPaidVal = Double.parseDouble(AmountPaid);
                }


            }
            if (AmountPaidVal > 199999)
                IsClear = false;
            else
                IsClear = true;

            return IsClear;
        }
        catch (Exception ex)
        {

            return IsClear;
        }
    }

    private void getHeaderToken(JsonObject onGateRequestModel) {
        showProgressDialog("Loading please wait.....");
        Call<JsonObject> onGateAPICALL = apiInterface.getheaderToken(onGateRequestModel);
        onGateAPICALL.enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(Call<JsonObject> call, Response<JsonObject> response) {
                dismissProgressDialog();
                if (response.isSuccessful() && response.body() != null) {
                    if (response.body() != null) {
                        if (response.code() >= 200 && response.code() < 210) {
                            try {
                                Toast.makeText(Payment.this, "token APi response "+response.toString(), Toast.LENGTH_LONG).show();
                                JSONObject jsonObject = new JSONObject(response.toString());
                                token= jsonObject.optString("token");

                            } catch (JSONException e) {
                                throw new RuntimeException(e);
                            }
                        }else if(response.code() == 500){
                            Toast.makeText(Payment.this, "token 500 error "+response.toString(), Toast.LENGTH_LONG).show();
                        }

                        else {
                            BufferedReader reader = null;
                            StringBuilder sb = new StringBuilder();
                            try {
                                reader = new BufferedReader(new InputStreamReader(response.errorBody().byteStream()));
                                String line = reader.readLine();
                                try {
                                    if (line != null) {
                                        sb.append(line);
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            } finally {
                                if (reader != null) {
                                    try {
                                        reader.close();
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }

                            try {
                                String finallyError = sb.toString();
                                //JSONObject jsonObjectError = new JSONObject(finallyError);
                              //  String message = jsonObjectError.optString("msg");
                                Toast.makeText(Payment.this, "token finally error "+finallyError, Toast.LENGTH_LONG).show();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }


                    } else {

                    }
                }
            }

            @Override
            public void onFailure(Call<JsonObject> call, Throwable t) {
                dismissProgressDialog();
                Toast.makeText(Payment.this,t.getMessage(),Toast.LENGTH_LONG).show();
                /*Utils.showToast(OnGateRequestActivity.this, t.getMessage());
                customProgressDialog.progressDialogDismiss();
                failedBottomSheet();*/
            }
        });
    }
    private void showProgressDialog(String message) {
        if (progressDialog == null) {
            progressDialog = new ProgressDialog(this);
            progressDialog.setCancelable(false); // Prevent user from dismissing the dialog
        }
        progressDialog.setMessage(message);
        progressDialog.show();
    }

    private void dismissProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }

    private void refreshTotalAmount(JsonObject onGateRequestModel) {
        showProgressDialog("Loading please wait.....");
        Call<JsonObject> onGateAPICALL = apiInterface.refreshPayment("Npcl1234",Cons);
        onGateAPICALL.enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(Call<JsonObject> call, Response<JsonObject> response) {
                dismissProgressDialog();
                if (response.isSuccessful() && response.body() != null) {
                    if (response.body() != null) {
                        if (response.code() >= 200 && response.code() < 210) {
                            try {
                                Toast.makeText(Payment.this, "amount response "+response.toString(), Toast.LENGTH_LONG).show();
                                JSONObject jsonObject = new JSONObject(response.body().toString());
                                String totalAmount = jsonObject.optString("outstandingAmt");
                                lblCurrBillAmt.setText(totalAmount);
                            } catch (JSONException e) {
                                throw new RuntimeException(e);
                            }
                        } else if (response.code() == 500) {
                            Toast.makeText(Payment.this, "amount 500 error "+response.toString(), Toast.LENGTH_LONG).show();
                        } else {
                            BufferedReader reader = null;
                            StringBuilder sb = new StringBuilder();
                            try {
                                reader = new BufferedReader(new InputStreamReader(response.errorBody().byteStream()));
                                String line = reader.readLine();
                                try {
                                    if (line != null) {
                                        sb.append(line);
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            } finally {
                                if (reader != null) {
                                    try {
                                        reader.close();
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }

                            try {
                                String finallyError = sb.toString();
                                //JSONObject jsonObjectError = new JSONObject(finallyError);
                                //  String message = jsonObjectError.optString("msg");
                                Toast.makeText(Payment.this, "amount "+finallyError, Toast.LENGTH_LONG).show();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }


                    } else {

                    }
                }
            }

            @Override
            public void onFailure(Call<JsonObject> call, Throwable t) {
                dismissProgressDialog();
                Toast.makeText(Payment.this,t.getMessage(),Toast.LENGTH_LONG).show();
                /*Utils.showToast(OnGateRequestActivity.this, t.getMessage());
                customProgressDialog.progressDialogDismiss();
                failedBottomSheet();*/
            }
        });
    }

    public  Bitmap generateQRCode(String text) {
        try {
            BarcodeEncoder barcodeEncoder = new BarcodeEncoder();
            BitMatrix bitMatrix = barcodeEncoder.encode(text, BarcodeFormat.QR_CODE, 500, 500);
            return barcodeEncoder.createBitmap(bitMatrix);
        } catch (WriterException e) {
            e.printStackTrace();
            return null;
        }
    }
}



