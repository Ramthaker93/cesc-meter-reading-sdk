package com.npcl.com.vcpopdl;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;

import org.apache.commons.net.PrintCommandListener;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;



public class FTPFunctions {

    // Creating FTP Client instance
    FTPClient ftp = null;

    // Constructor to connect to the FTP Server
    public FTPFunctions(String host, int port, String username, String password) throws Exception{
    ftp = new FTPClient();
    ftp.addProtocolCommandListener(new PrintCommandListener(new PrintWriter(System.out)));
    int reply;
    ftp.connect(host, port);
    System.out.println("FTP URL is:" + ftp.getDefaultPort());
    reply = ftp.getReplyCode();
    if (!FTPReply.isPositiveCompletion(reply)) {
        ftp.disconnect();
        throw new Exception("Exception in connecting to FTP Server");
    }
    ftp.login(username, password);
    ftp.setFileType(FTP.BINARY_FILE_TYPE);
    ftp.enterLocalPassiveMode();


    }

    // Method to upload the File on the FTP Server
    public boolean uploadFTPFile(String localFileFullName, String fileName, String hostDir)
            throws Exception
    {
        try {
            InputStream input = new FileInputStream(new File(localFileFullName));

            this.ftp.storeFile(hostDir + fileName, input);
            return  true;
        }
        catch(Exception e){
            return  false;
        }
    }

    public boolean uploadFTPFilemy(String localFileFullName, String fileName, String hostDir)
    {
        try {
            InputStream inputStream = new FileInputStream(new File(localFileFullName));
            OutputStream outputStream = ftp.storeFileStream(hostDir + fileName);
            byte[] bytesIn = new byte[4096];
            int read = 0;
            while ((read = inputStream.read(bytesIn)) != -1)
            {
            outputStream.write(bytesIn, 0, read);
            }
            inputStream.close();
            outputStream.close();
            return  true;
        }
        catch(Exception e){
            return  false;
        }
    }


    // Download the FTP File from the FTP Server
    public void downloadFTPFile(String source, String destination) {
        try (FileOutputStream fos = new FileOutputStream(destination)) {
            this.ftp.retrieveFile(source, fos);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // list the files in a specified directory on the FTP
    public boolean listFTPFiles(String directory, String fileName) throws IOException {
        // lists files and directories in the current working directory
        boolean verificationFilename = false;
        FTPFile[] files = ftp.listFiles(directory);
        for (FTPFile file : files) {
            String details = file.getName();
            System.out.println(details);
            if(details.equals(fileName))
            {
                System.out.println("Correct Filename");
                verificationFilename=details.equals(fileName);
                //assertTrue("Verification Failed: The filename is not updated at the CDN end.",details.equals(fileName));
            }
        }

        return verificationFilename;
    }

    // Disconnect the connection to FTP
    public void disconnect(){
        if (this.ftp.isConnected()) {
            try {
                this.ftp.logout();
                this.ftp.disconnect();
            } catch (IOException f) {
                // do nothing as file is already saved to server
            }
        }
    }

    // Main method to invoke the above methods
    public static void main(String[] args) {
        try {
            FTPFunctions ftpobj = new FTPFunctions("abodeqa4.noid.blog.int", 2121, "shruti", "password");
            ftpobj.uploadFTPFile("C:\\Users\\shruti\\Shruti.txt", "Shruti.txt", "/");
            ftpobj.downloadFTPFile("Shruti.txt", "/users/shruti/Shruti.txt");
            System.out.println("FTP File downloaded successfully");
            boolean result = ftpobj.listFTPFiles("/users/shruti", "shruti.txt");
            System.out.println(result);
            ftpobj.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
