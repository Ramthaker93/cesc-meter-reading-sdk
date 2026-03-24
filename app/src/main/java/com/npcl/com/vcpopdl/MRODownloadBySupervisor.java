package com.npcl.com.vcpopdl;

import android.content.Context;

import org.ksoap2.SoapEnvelope;
import org.ksoap2.serialization.PropertyInfo;
import org.ksoap2.serialization.SoapObject;
import org.ksoap2.serialization.SoapSerializationEnvelope;
import org.ksoap2.transport.HttpTransportSE;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MRODownloadBySupervisor {

    String namespace = "http://npcl.com/";
    private String url = "https://iwebapps.noidapower.com:8032/VCPWebservices.asmx";

    String SOAP_ACTION;
    SoapObject request = null, objMessages = null;
    SoapSerializationEnvelope envelope;
    HttpTransportSE androidHttpTransport;
    MRODownloadBySupervisor() {
    }
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

    public String getMroSupervisor(String MethodName, String Billmonthyear , String VcpId, Context context)
    {


        try {
            SOAP_ACTION = namespace + MethodName;

            //Adding values to request object
            request = new SoapObject(namespace, MethodName);

            //Adding Double value to request object
            PropertyInfo weightProp =new PropertyInfo();
            //Adding String value to request object
            request.addProperty("Billmonthyear", "" + Billmonthyear);
            request.addProperty("VcpId", "" + VcpId);



            SetEnvelope();

            try {

                //SOAP calling webservice
                androidHttpTransport.call(SOAP_ACTION, envelope);

                //Got Webservice response
                String result = envelope.getResponse().toString();

                String [] Row = result.split("#");
                String status=SaveMROSupervisor(result,VcpId,context );
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

    public String SaveMROSupervisor (String Data, String UserId , Context context)
    {
        String Qry="";
        String AllQry="";
        try
        {
            String  FileName="Webservices";
            String [] Row = Data.split("#");
            String Cols = " (ConsumerNo,MeterNo,PR_MR_Date,PoleNo,Serge,Name,Co,HouseNo,Street,City,ExpectedReading,ExpectedReading1,tolerance,Sch_MR_Date,Ablbelnr,Pre_Decimal,Post_Decimal,Ableinh,Billmonthyear,Unit,Portion,photoid,mreg1,mreg2,mreg3,mreg4,TabId ) ";
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
                Vals = Vals + "'" + UserId + "'";

                Qry = "insert into MRO_Detail_Supervisor " + Cols + "values (" + Vals + ");";
                //String Qry = "insert into mro_Detail (ConsumerNo,PoleNo) values ('20000001','xxx')";

                java.lang.Thread.sleep(12);


                Obj.ExecuteQry(Qry) ;
            }

            return "1";
        }
        catch (Exception e) {
            return "In Save MRO"+ e.getMessage();
        }

    }
}
