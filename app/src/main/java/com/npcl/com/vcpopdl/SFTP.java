package com.npcl.com.vcpopdl;

import android.os.Environment;
import android.widget.Toast;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class SFTP {


    public static boolean  uploadFile(String localFileFullName, String varfileName, String hostDir, boolean IsCescRaj,String Billmonthyear) {
        boolean Result = false;
        String localFilePath = localFileFullName;

        String fileName = varfileName;
        String year=Billmonthyear.substring(0,4).trim();
        String remoteFilePath = "/HHD/"+year+"/" + fileName;
        boolean conStatus = false;
        Session session = null;
        Channel channel = null;
        String HOST_ADDRESS="";
        if (IsCescRaj == false) {
            //   HOST_ADDRESS = "14.142.33.238"; //old
            HOST_ADDRESS="14.142.33.231";
        }
        else
        {
            //  HOST_ADDRESS = "192.158.14.79";  //old
            HOST_ADDRESS="172.16.150.53";
        }

        //  String HOST_USER="hhdphoto";   old
        String HOST_USER="HHD";
        String HOST_PORT="2222";
        java.util.Properties config = new java.util.Properties();
        config.put("StrictHostKeyChecking", "no");
        JSch ssh = new JSch();
        try {
            session = ssh.getSession(HOST_USER, HOST_ADDRESS, 2222);
            session.setPassword("Hhdphoto@789");
            session.setConfig(config);
            session.connect();
            conStatus = session.isConnected();
            channel = session.openChannel("sftp");
            channel.connect();
            ChannelSftp sftp = (ChannelSftp) channel;
            sftp.put(localFilePath, remoteFilePath);
            return true;
        }
        catch (Exception e) {
            e.printStackTrace();
            Result = false;
            sheelendralogLog("Fn_uploadFile: "+e.getMessage().toString());
            return Result;
        }
    }

    public static boolean  uploadMeterFile(String localFileFullName, String varfileName, String hostDir, boolean IsCescRaj) {
        boolean Result = false;
        String localFilePath = localFileFullName;

        String fileName = varfileName;

        //  String remoteFilePath = "/METERDATA/" + fileName;
        String remoteFilePath = "/meterdata/" + fileName;
        boolean conStatus = false;
        Session session = null;
        Channel channel = null;
        String HOST_ADDRESS="";
        if (IsCescRaj == false) {
            //  HOST_ADDRESS = "14.142.33.238"; old
            HOST_ADDRESS="14.142.33.231";
        }
        else
        {
            // HOST_ADDRESS = "192.158.14.79";  old
            HOST_ADDRESS="172.16.150.53";
        }

        String HOST_USER="meterdata";
        String HOST_PORT="2222";
        java.util.Properties config = new java.util.Properties();
        config.put("StrictHostKeyChecking", "no");
        JSch ssh = new JSch();
        try {
            session = ssh.getSession(HOST_USER, HOST_ADDRESS, 2222);
            session.setPassword("Meterdata@789");
            session.setConfig(config);
            session.connect();
            conStatus = session.isConnected();
            channel = session.openChannel("sftp");
            channel.connect();
            ChannelSftp sftp = (ChannelSftp) channel;
            sftp.put(localFilePath, remoteFilePath);
            return true;

        }
        catch (Exception e) {
            e.printStackTrace();
            Result = false;
            sheelendralogLog("Fn_uploadMeterFile: "+e.getMessage().toString());
            return Result;
            //return e.getMessage();
        }
    }
   /* public static String  uploadFile(String localFileFullName, String varfileName, String hostDir)
    {
        String  Result="";

        String host = "sftp://14.142.33.238";

        String username = "hhdphoto";
        String password = "Hhdphoto@789";


        String localFilePath = localFileFullName;

        String fileName = varfileName;

        String remoteFilePath = "/HHD/" + fileName;


        JSch jsch = new JSch();
        Session session = null;
        try {
            session = jsch.getSession(username, host, 2222);
            session.setConfig("StrictHostKeyChecking", "no");
            session.setPassword(password);
            session.connect();

            Channel channel = session.openChannel("sftp");
            channel.connect();
            ChannelSftp sftpChannel = (ChannelSftp) channel;
            sftpChannel.put(localFilePath, remoteFilePath);
            sftpChannel.exit();
            session.disconnect();
            Result= "111";
            return "111";
        } catch (JSchException e) {

            e.printStackTrace();

            Result=e.getMessage();
            return Result;
        } catch (SftpException e) {
            e.printStackTrace();
            Result=e.getMessage();
            return Result;
        }
    }*/

    public static void sheelendralogLog(String text)
    {
        File logFile = new File(Environment.getExternalStorageDirectory()+ "/" + "Developerlog.txt");
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
            e.printStackTrace();
        }
    }
}
