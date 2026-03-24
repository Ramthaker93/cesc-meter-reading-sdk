package com.npcl.com.vcpopdl;



import android.content.Context;
import android.os.Environment;

import org.ksoap2.SoapEnvelope;
import org.ksoap2.serialization.PropertyInfo;
import org.ksoap2.serialization.SoapObject;
import org.ksoap2.serialization.SoapSerializationEnvelope;
import org.ksoap2.transport.HttpTransportSE;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class mrodownload {

    String namespace = "http://npcl.com/";
    private String url = "https://iwebapps.noidapower.com:8032/VCPWebservices.asmx";

    String SOAP_ACTION;
    SoapObject request = null, objMessages = null;
    SoapSerializationEnvelope envelope;
    HttpTransportSE androidHttpTransport;
    mrodownload() {
    }
    protected void SetEnvelope() {

        try {

            // Creating SOAP envelope
            envelope = new SoapSerializationEnvelope(SoapEnvelope.VER11);

            //You can comment that line if your web service is not .NET one.
            envelope.dotNet = true;

            envelope.setOutputSoapObject(request);
            androidHttpTransport = new HttpTransportSE(url,600000);
            androidHttpTransport.debug = true;

        } catch (Exception e) {
            System.out.println("Soap Exception---->>>" + e.toString());
        }
    }

    public String SaveMRO (String Data, String UserId , Context context)
    {
        String Qry="";
        String AllQry="";
        try
        {
            String  FileName="Webservices";
            String [] Row = Data.split("#");
            String Cols = " (ConsumerNo,MeterNo,Sch_MR_Date,Mrroute,PoleNo,Serge,Name,Co,HouseNo,Street,City,ExpectedReading,ExpectedReading1,tolerance,CurrentReading,PR_MR_Date,Ablbelnr,Pre_Decimal,Post_Decimal,Unit,Billmonthyear,Ableinh ,Register,Portion,LastPmt,LastPmtDate,LastBillAmt,Status,Arrear,MobileNo,RateCat,EntryDate,UserId,FileName ,ISDLMS, Last_Read_Data, LAST_Date_Date) ";
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());

            String SDate=timeStamp.toString().split("_")[0].toString();
            DatabaseHandler Obj = new DatabaseHandler(context);
            for(int i=0;i< Row.length ;i++)
            {
                String Vals="";
                String[] temp = Row[i].split("~");
                for(int j=0;j < temp.length ;j++)
                {
                    Vals = Vals + "'" + temp[j].toString() + "',";
                }
                Vals = Vals + "'" + SDate + "',";
                Vals = Vals + "'" + UserId + "',";
                Vals = Vals + "'" + FileName + "'";
                Qry = "insert into MRO_Detail " + Cols + "values (" + Vals + ");";
               // String Qry = "insert into mro_Detail (ConsumerNo,PoleNo) values ('20000001','xxx')";
                AllQry = AllQry + "#" + Qry;

                java.lang.Thread.sleep(12);

              //  appendLog(AllQry);
                Obj.ExecuteQry(Qry) ;
            }

                return "1";
        }
        catch (Exception e) {
            return "In Save MRO"+ e.getMessage();
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
           // Toast.makeText(getApplicationContext(), e.getMessage().toString() , Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    public String getMro(String MethodName, String Billmonthyear , String VcpId,String Flag, Context context)
    {


        try {
            SOAP_ACTION = namespace + MethodName;

            //Adding values to request object
            request = new SoapObject(namespace, MethodName);

            //Adding Double value to request object
            PropertyInfo weightProp =new PropertyInfo();
            //weightProp.setName("NoValue");
            //weightProp.setValue(NoValue);
            //weightProp.setType(String.class);
            //request.addProperty(weightProp);

            //Adding String value to request object
            request.addProperty("Billmonthyear", "" + Billmonthyear);
            request.addProperty("VcpId", "" + VcpId);
            request.addProperty("Flag", "" + Flag);


            SetEnvelope();

            try {

                //SOAP calling webservice
                androidHttpTransport.call(SOAP_ACTION, envelope);

                //Got Webservice response
                String result = envelope.getResponse().toString();

                String [] Row = result.split("#");
                String status=SaveMRO(result,VcpId,context );
                return status;

            } catch (SocketTimeoutException ex) {
                return ex.toString();

            }
            catch (Exception e) {
                // TODO: handle exception
                return "MRO Not Downloaded"+ e.toString();
            }
        } catch (Exception e) {
            // TODO: handle exception
            return "MRO Not Downloaded"+ e.toString();
        }

    }
}

