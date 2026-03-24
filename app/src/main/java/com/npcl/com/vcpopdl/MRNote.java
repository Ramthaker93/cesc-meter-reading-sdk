package com.npcl.com.vcpopdl;

import android.content.Context;

import org.ksoap2.SoapEnvelope;
import org.ksoap2.serialization.PropertyInfo;
import org.ksoap2.serialization.SoapObject;
import org.ksoap2.serialization.SoapSerializationEnvelope;
import org.ksoap2.transport.HttpTransportSE;
import java.net.SocketTimeoutException;

public class MRNote {

    String namespace = "http://npcl.com/";
    private String url = "https://iwebapps.noidapower.com:8032/VCPWebservices.asmx";

    String SOAP_ACTION;
    SoapObject request = null, objMessages = null;
    SoapSerializationEnvelope envelope;
    HttpTransportSE androidHttpTransport;
    MRNote(){    }
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

    public String getMRNote(String MethodName, String VCPID,  Context context)
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
            request.addProperty("Flag", VCPID);

            SetEnvelope();

            try {

                //SOAP calling webservice
                androidHttpTransport.call(SOAP_ACTION, envelope);

                //Got Webservice response
                String result = envelope.getResponse().toString();
                String status=SaveMRNote(result,context);
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

    public String SaveMRNote(String Data, Context context)
    {
        Boolean Result;
        try
        {
            DatabaseHandler Obj=new DatabaseHandler(context);
            String[] Temp = Data.split("#");
            if (Temp.length >3) {
                String Sql="delete from Note_type";
                Obj.ExecuteQry(Sql);
            }
            for (int i = 0; i < Temp.length; i++)
            {
                String[] Temp1 = Temp[i].split("~");
                String Qry = "Insert into Note_type values ('" + Temp1[0].toString() + "','" + Temp1[1].toString() + "')";
                Result= Obj.ExecuteQry(Qry);
                if (Result== false)
                    return  "Error";
            }
            return  "1";
        }
        catch (Exception e)
        {
            return e.getMessage();

        }
    }


}

