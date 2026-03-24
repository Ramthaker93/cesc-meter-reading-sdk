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

public class LoginDetail {

    String namespace = "http://npcl.com/";
    private String url = "https://iwebapps.noidapower.com:8032/VCPWebservices.asmx";

    String SOAP_ACTION;
    SoapObject request = null, objMessages = null;
    SoapSerializationEnvelope envelope;
    HttpTransportSE androidHttpTransport;
    LoginDetail()
    {}
    protected void SetEnvelope() {

        try {

            // Creating SOAP envelope
            envelope = new SoapSerializationEnvelope(SoapEnvelope.VER11);

            //You can comment that line if your web service is not .NET one.
            envelope.dotNet = true;

            envelope.setOutputSoapObject(request);
            androidHttpTransport = new HttpTransportSE(url,60000);
            androidHttpTransport.debug = true;

        } catch (Exception e) {
            System.out.println("Soap Exception---->>>" + e.toString());
        }
    }
    public String getLogin(String MethodName,String IEMINO, Context context)
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
            request.addProperty("IEMINO",  IEMINO);

            SetEnvelope();

            try {

                //SOAP calling webservice
                androidHttpTransport.call(SOAP_ACTION, envelope);

                //Got Webservice response
                String result = envelope.getResponse().toString();
                if (result.equals(""))
                    return "-1";
                String status=SaveLoginDetail(result,IEMINO,context);
                return status;

            } catch (SocketTimeoutException ex) {
                return ex.toString();

            }
            catch (Exception e) {
                // TODO: handle exception
                return e.getMessage();

            }
        } catch (Exception e) {
            // TODO: handle exception
            String err= e.toString();
            return "-1";
        }

    }
    public String SaveLoginDetail(String Data,String IEMINO ,Context context)
    {




        Boolean Result;

        try
        {

            DatabaseHandler Obj=new DatabaseHandler(context);
            String[] Temp = Data.split("#");
            if (Data.length() >5) {
                String Sql="delete from Login";
                Obj.ExecuteQry(Sql);
            }
            String EntryDate="";

            SimpleDateFormat dateFormatter = new SimpleDateFormat("ddMMyyyyhhmmss");
            dateFormatter.setLenient(false);
            Date today = new Date();
            EntryDate = dateFormatter.format(today);
            String Userid="";
            String Role="";
            for (int i = 0; i < Temp.length; i++)
            {
                String[] Temp1 = Temp[i].split("~");
                Userid= Temp1[3].toString();
                Role= Temp1[1].toString();
                String qry ="delete from Login where IEMINO='356922094804163')";
                boolean Result1= Obj.ExecuteQry(qry);
                String Qry = "insert into Login (Userid,Password,IsActive,Entrydate,Role,IEMINO) values ('"+ Userid +"','12345','Y','"+ EntryDate +"','"+ Role  +"','" +IEMINO +"')";




                Result= Obj.ExecuteQry(Qry);
                MakeDataFile("Login",Qry +"Result"+Result + "~" +Result1);
                if (Result== false)
                    return  "-1";
            }
            return  "1";
        }
        catch (Exception e)
        {
            //return Data;
            return  e.getMessage();

        }
    }
    public void MakeDataFile(String FileName, String Data)
    {



        File logFile = new File(Environment.getExternalStorageDirectory()+ "/" +FileName +".Log");
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
}
